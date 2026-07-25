/*
 * Copyright (c) 2026, mrmain21
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES ARE DISCLAIMED.
 */
package com.mrmain21.dropfinder;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Fetches "who drops this item" data live from the OSRS Wiki instead of bundling
 * it. Renders the wiki's {@code {{Drop sources|<item>}}} template (the same
 * "Item sources" table shown on item pages) via the MediaWiki parse API, parses
 * the resulting HTML, and caches results in memory so repeat lookups are instant.
 */
@Singleton
@Slf4j
public class WikiDropClient
{
	private static final String API = "https://oldschool.runescape.wiki/api.php";
	private static final String USER_AGENT = "RuneLite-DropFinder/1.0 (plugin; live lookup)";
	/** Bound how many items one grouped query fetches, to keep requests sane. */
	static final int MAX_BATCH = 30;

	/** One source (monster / chest) that drops an item, mirroring the bundled model. */
	@Getter
	@RequiredArgsConstructor
	public static class Drop
	{
		private final String monster;
		private final int combatLevel;
		private final String rarity;
		private final String quantity;
	}

	private final OkHttpClient okHttpClient;
	private final Gson gson;
	private final Map<String, List<Drop>> cache = new ConcurrentHashMap<>();

	@Inject
	WikiDropClient(OkHttpClient okHttpClient, Gson gson)
	{
		this.okHttpClient = okHttpClient;
		this.gson = gson;
	}

	/**
	 * Look up sources for the given items. Cached items are returned immediately;
	 * any misses are fetched in a single request. {@code onDone} is invoked (on an
	 * OkHttp thread) with item name -> sources; {@code onError} on failure.
	 */
	public void fetchSources(List<String> items, Consumer<Map<String, List<Drop>>> onDone, Consumer<String> onError)
	{
		final Map<String, List<Drop>> result = new LinkedHashMap<>();
		final List<String> missing = new ArrayList<>();
		for (final String item : items)
		{
			final List<Drop> cached = cache.get(item.toLowerCase());
			if (cached != null)
			{
				result.put(item, cached);
			}
			else if (missing.size() < MAX_BATCH)
			{
				missing.add(item);
			}
		}

		if (missing.isEmpty())
		{
			onDone.accept(result);
			return;
		}

		final StringBuilder text = new StringBuilder();
		for (final String item : missing)
		{
			text.append("\n@@I@@").append(item).append("@@I@@\n{{Drop sources|").append(item).append("}}");
		}

		final Request request = new Request.Builder()
			.url(API)
			.header("User-Agent", USER_AGENT)
			.post(new FormBody.Builder()
				.add("action", "parse")
				.add("format", "json")
				.add("prop", "text")
				.add("contentmodel", "wikitext")
				.add("disablelimitreport", "1")
				.add("text", text.toString())
				.build())
			.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Drop lookup failed", e);
				onError.accept("Couldn't reach the wiki. Check your connection and try again.");
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (!r.isSuccessful() || r.body() == null)
					{
						onError.accept("Wiki returned an error (" + r.code() + ").");
						return;
					}
					final String body = r.body().string();
					final JsonObject root = gson.fromJson(body, JsonObject.class);
					final String html = root.getAsJsonObject("parse")
						.getAsJsonObject("text").get("*").getAsString();

					for (final Map.Entry<String, List<Drop>> e : splitAndParse(html).entrySet())
					{
						cache.put(e.getKey().toLowerCase(), e.getValue());
						result.put(e.getKey(), e.getValue());
					}
					// Cache empties too, so we don't re-hit the wiki for them.
					for (final String item : missing)
					{
						cache.putIfAbsent(item.toLowerCase(), new ArrayList<>());
						result.putIfAbsent(item, cache.get(item.toLowerCase()));
					}
					onDone.accept(result);
				}
				catch (Exception ex)
				{
					log.debug("Drop parse failed", ex);
					onError.accept("Couldn't read the wiki response.");
				}
			}
		});
	}

	// ---------- HTML parsing (mirrors the offline scraper) ----------

	private static final Pattern MARKER = Pattern.compile("@@I@@(.*?)@@I@@", Pattern.DOTALL);
	private static final Pattern TABLE = Pattern.compile("<table[^>]*item-drops.*?</table>", Pattern.DOTALL);
	private static final Pattern ROW = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL);
	private static final Pattern CELL = Pattern.compile("<td\\b[^>]*>(.*?)</td>", Pattern.DOTALL);
	private static final Pattern TITLE = Pattern.compile("title=\"([^\"]+)\"");
	private static final Pattern FRACTION = Pattern.compile("data-drop-fraction=\"([^\"]+)\"");
	private static final Pattern INT = Pattern.compile("\\d+");
	private static final Pattern TAGS = Pattern.compile("<[^>]+>");
	private static final Pattern TIDY = Pattern.compile("~?\\d+(?:\\.\\d+)?/\\d+(?:\\.\\d+)?");

	/** Splits the multi-template render by markers and parses each item's table. */
	private Map<String, List<Drop>> splitAndParse(String html)
	{
		final Map<String, List<Drop>> out = new LinkedHashMap<>();
		final Matcher m = MARKER.matcher(html);
		final List<String> names = new ArrayList<>();
		final List<Integer> segStart = new ArrayList<>();
		final List<Integer> markStart = new ArrayList<>();
		while (m.find())
		{
			names.add(unescape(m.group(1)).trim());
			segStart.add(m.end());
			markStart.add(m.start());
		}
		for (int i = 0; i < names.size(); i++)
		{
			final int start = segStart.get(i);
			final int end = (i + 1 < names.size()) ? markStart.get(i + 1) : html.length();
			out.put(names.get(i), parseTable(html.substring(start, end)));
		}
		return out;
	}

	private List<Drop> parseTable(String segment)
	{
		final List<Drop> drops = new ArrayList<>();
		final Matcher tm = TABLE.matcher(segment);
		if (!tm.find())
		{
			return drops;
		}
		final String table = tm.group();
		final Matcher rm = ROW.matcher(table);
		while (rm.find())
		{
			final String row = rm.group(1);
			if (row.contains("<th"))
			{
				continue;
			}
			final List<String> cells = new ArrayList<>();
			final Matcher cm = CELL.matcher(row);
			while (cm.find())
			{
				cells.add(cm.group(1));
			}
			if (cells.size() < 4)
			{
				continue;
			}

			final Matcher src = TITLE.matcher(cells.get(0));
			if (!src.find())
			{
				continue;
			}
			final String source = unescape(src.group(1)).trim();
			if (source.isEmpty() || "Combat level".equals(source))
			{
				continue;
			}

			final Matcher lv = INT.matcher(TAGS.matcher(cells.get(1)).replaceAll(" "));
			final int level = lv.find() ? Integer.parseInt(lv.group()) : 0;

			final String qty = trimOr(unescape(TAGS.matcher(cells.get(2)).replaceAll("")).trim(), "1");

			final Matcher fr = FRACTION.matcher(cells.get(3));
			String rarity = "";
			if (fr.find())
			{
				final String raw = unescape(fr.group(1)).replace(",", "").trim();
				if (TIDY.matcher(raw).matches() || raw.equalsIgnoreCase("Always"))
				{
					rarity = raw;
				}
			}

			drops.add(new Drop(source, level, rarity, qty));
		}

		drops.sort((a, b) -> Double.compare(chance(b.getRarity()), chance(a.getRarity())));
		return drops;
	}

	private static String trimOr(String s, String fallback)
	{
		return s == null || s.isEmpty() ? fallback : s;
	}

	private static double chance(String r)
	{
		if (r == null || r.isEmpty())
		{
			return -1;
		}
		String s = r.startsWith("~") ? r.substring(1) : r;
		if (s.equalsIgnoreCase("Always"))
		{
			return 1;
		}
		final int slash = s.indexOf('/');
		if (slash > 0)
		{
			try
			{
				final double num = Double.parseDouble(s.substring(0, slash));
				final double den = Double.parseDouble(s.substring(slash + 1));
				return den != 0 ? num / den : -1;
			}
			catch (NumberFormatException ignored)
			{
				return -1;
			}
		}
		return -1;
	}

	/** Minimal HTML entity decode for the few entities that appear in names. */
	private static String unescape(String s)
	{
		if (s.indexOf('&') < 0)
		{
			return s;
		}
		final StringBuilder out = new StringBuilder(s.length());
		final Matcher m = Pattern.compile("&#(\\d+);|&(amp|lt|gt|quot|nbsp|#39);").matcher(s);
		int last = 0;
		while (m.find())
		{
			out.append(s, last, m.start());
			if (m.group(1) != null)
			{
				out.append((char) Integer.parseInt(m.group(1)));
			}
			else
			{
				switch (m.group(2))
				{
					case "amp": out.append('&'); break;
					case "lt": out.append('<'); break;
					case "gt": out.append('>'); break;
					case "quot": out.append('"'); break;
					case "#39": out.append('\''); break;
					default: out.append(' '); break; // nbsp
				}
			}
			last = m.end();
		}
		out.append(s.substring(last));
		return out.toString();
	}
}
