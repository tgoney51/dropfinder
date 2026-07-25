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

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Image;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.LinkBrowser;
import net.runelite.http.api.item.ItemPrice;

/**
 * Drop Finder side panel (live edition): item names come from the game client
 * ({@link ItemManager}) and drop sources are fetched on demand from the OSRS
 * Wiki via {@link WikiDropClient}. Nothing is bundled.
 */
class DropFinderPanel extends PluginPanel
{
	private final WikiDropClient wikiClient;
	private final ItemManager itemManager;
	private final DropFinderConfig config;

	private final GhostTextField searchField;
	private final JPanel resultsPanel = new JPanel();
	private final JLabel statusLabel = new JLabel();

	private String lastQuery = "";
	/** Guards against stale async responses overwriting a newer search. */
	private int searchSeq = 0;

	/** Source rows awaiting their wiki thumbnail, rebuilt each render. */
	private final List<IconTarget> iconTargets = new ArrayList<>();

	private static final class IconTarget
	{
		private final String name;
		private final JLabel label;

		IconTarget(String name, JLabel label)
		{
			this.name = name;
			this.label = label;
		}
	}

	DropFinderPanel(WikiDropClient wikiClient, ItemManager itemManager, DropFinderConfig config)
	{
		super(false);
		this.wikiClient = wikiClient;
		this.itemManager = itemManager;
		this.config = config;

		setLayout(new BorderLayout(0, 8));
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		searchField = new GhostTextField(this::bestCompletion, this::search);
		searchField.setToolTipText("Type an item, e.g. \"Dragon boots\"");
		searchField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchField.setForeground(Color.WHITE);
		searchField.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			BorderFactory.createEmptyBorder(4, 6, 4, 6)));
		searchField.setPreferredSize(new Dimension(0, 26));

		resultsPanel.setLayout(new BoxLayout(resultsPanel, BoxLayout.Y_AXIS));

		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		statusLabel.setHorizontalAlignment(SwingConstants.CENTER);
		statusLabel.setText("Search an item to see its drop sources.");

		final JPanel resultsWrapper = new JPanel(new BorderLayout());
		resultsWrapper.add(resultsPanel, BorderLayout.NORTH);

		final JPanel center = new JPanel(new BorderLayout(0, 6));
		center.add(statusLabel, BorderLayout.NORTH);
		center.add(resultsWrapper, BorderLayout.CENTER);

		final JScrollPane scroll = new JScrollPane(center,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getVerticalScrollBar().setUnitIncrement(16);

		add(searchField, BorderLayout.NORTH);
		add(scroll, BorderLayout.CENTER);
	}

	@Override
	public void onActivate()
	{
		searchField.requestFocusInWindow();
	}

	/** Alphabetically-first item name (from the client) that starts with the text. */
	private String bestCompletion(String typed)
	{
		final String lower = typed.toLowerCase();
		String best = null;
		for (final ItemPrice ip : itemManager.search(typed))
		{
			final String n = ip.getName();
			if (n.toLowerCase().startsWith(lower) && (best == null || n.compareToIgnoreCase(best) < 0))
			{
				best = n;
			}
		}
		return best;
	}

	/** Distinct item names (from the client) whose name contains the query. */
	private List<String> matchingItemNames(String query)
	{
		final String lower = query.toLowerCase();
		final Set<String> names = new LinkedHashSet<>();
		for (final ItemPrice ip : itemManager.search(query))
		{
			if (ip.getName().toLowerCase().contains(lower))
			{
				names.add(ip.getName());
			}
		}
		return new ArrayList<>(names);
	}

	void refresh()
	{
		search(lastQuery);
	}

	private void search(String rawQuery)
	{
		final String query = rawQuery == null ? "" : rawQuery.trim();
		lastQuery = query;
		resultsPanel.removeAll();

		if (query.isEmpty())
		{
			statusLabel.setText("Search an item to see its drop sources.");
			revalidateResults();
			return;
		}

		final List<String> names = matchingItemNames(query);
		if (names.isEmpty())
		{
			statusLabel.setText("<html>No item matches <b>" + escape(query) + "</b>.</html>");
			revalidateResults();
			return;
		}

		String exact = null;
		for (final String n : names)
		{
			if (n.equalsIgnoreCase(query))
			{
				exact = n;
				break;
			}
		}
		final boolean single = exact != null || names.size() == 1;
		final List<String> toFetch = single
			? List.of(exact != null ? exact : names.get(0))
			: names;

		final int seq = ++searchSeq;
		statusLabel.setText("Looking up the wiki…");
		revalidateResults();

		wikiClient.fetchSources(toFetch,
			map -> SwingUtilities.invokeLater(() ->
			{
				if (seq == searchSeq)
				{
					renderResults(query, single, toFetch, map);
				}
			}),
			err -> SwingUtilities.invokeLater(() ->
			{
				if (seq == searchSeq)
				{
					resultsPanel.removeAll();
					statusLabel.setText("<html>" + escape(err) + "</html>");
					revalidateResults();
				}
			}));
	}

	private void renderResults(String query, boolean single, List<String> items,
		Map<String, List<WikiDropClient.Drop>> map)
	{
		resultsPanel.removeAll();
		iconTargets.clear();
		if (single)
		{
			final String item = items.get(0);
			showSingleItem(item, map.getOrDefault(item, List.of()));
		}
		else
		{
			showGrouped(query, items, map);
		}
		revalidateResults();
		loadSourceIcons(searchSeq);
	}

	/** Fetch + apply the little wiki thumbnail for each source row, async. */
	private void loadSourceIcons(int seq)
	{
		if (iconTargets.isEmpty())
		{
			return;
		}
		final Set<String> names = new LinkedHashSet<>();
		for (final IconTarget t : iconTargets)
		{
			names.add(t.name);
		}
		wikiClient.fetchIconUrls(names, () -> SwingUtilities.invokeLater(() ->
		{
			if (seq != searchSeq)
			{
				return;
			}
			for (final IconTarget t : iconTargets)
			{
				final JLabel label = t.label;
				wikiClient.loadIcon(t.name, img -> SwingUtilities.invokeLater(() ->
				{
					if (seq == searchSeq && img != null)
					{
						label.setIcon(new ImageIcon(scaleIcon(img)));
					}
				}));
			}
		}));
	}

	private static Image scaleIcon(BufferedImage img)
	{
		final int max = 22;
		final double s = Math.min((double) max / img.getWidth(), (double) max / img.getHeight());
		final int w = Math.max(1, (int) Math.round(img.getWidth() * s));
		final int h = Math.max(1, (int) Math.round(img.getHeight() * s));
		return img.getScaledInstance(w, h, Image.SCALE_SMOOTH);
	}

	// ---------- collapsible category sections ----------

	private static final String[] SECTION_ORDER =
		{"NPCs", "Chests", "Clue scrolls", "Minigames", "Events", "Other"};

	private static String displayCategory(String sourceName, int level)
	{
		if (level > 0)
		{
			return "NPCs";
		}
		switch (category(sourceName))
		{
			case CHEST:
				return "Chests";
			case CLUE:
				return "Clue scrolls";
			case MINIGAME:
				return "Minigames";
			case EVENT:
				return "Events";
			default:
				return "Other";
		}
	}

	private void renderSections(Map<String, List<JPanel>> sections)
	{
		for (final String cat : SECTION_ORDER)
		{
			final List<JPanel> rows = sections.get(cat);
			if (rows != null && !rows.isEmpty())
			{
				addSection(cat, rows);
			}
		}
	}

	/**
	 * Adds a collapsible category section: a large coloured header that toggles
	 * the visibility of its rows. Sections start expanded.
	 */
	private void addSection(String title, List<JPanel> rows)
	{
		final JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setOpaque(false);
		content.setAlignmentX(Component.LEFT_ALIGNMENT);
		for (final JPanel r : rows)
		{
			content.add(r);
		}

		final JLabel header = new JLabel();
		header.setFont(FontManager.getRunescapeBoldFont().deriveFont(16f));
		header.setForeground(config.headerColor());
		header.setBorder(BorderFactory.createEmptyBorder(7, 2, 3, 2));

		final boolean[] open = {true};
		final Runnable relabel = () ->
			header.setText((open[0] ? "[-] " : "[+] ") + title + "  (" + rows.size() + ")");
		relabel.run();

		final MouseAdapter toggle = new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				open[0] = !open[0];
				content.setVisible(open[0]);
				relabel.run();
				resultsPanel.revalidate();
				resultsPanel.repaint();
			}
		};

		final JPanel headerRow = new JPanel(new BorderLayout());
		headerRow.setOpaque(false);
		headerRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		headerRow.setCursor(new Cursor(Cursor.HAND_CURSOR));
		headerRow.add(header, BorderLayout.WEST);
		headerRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, header.getPreferredSize().height + 10));
		headerRow.addMouseListener(toggle);
		header.addMouseListener(toggle);

		resultsPanel.add(headerRow);
		resultsPanel.add(content);
	}

	private void showSingleItem(String item, List<WikiDropClient.Drop> allDrops)
	{
		final List<WikiDropClient.Drop> drops = new ArrayList<>();
		for (final WikiDropClient.Drop drop : allDrops)
		{
			if (sourceAllowed(drop.getMonster(), drop.getCombatLevel()))
			{
				drops.add(drop);
			}
		}
		// NPCs first, then chests/objects; the list is already rarity-sorted, and
		// this stable sort keeps that order within each group.
		drops.sort(Comparator.comparingInt(d -> d.getCombatLevel() > 0 ? 0 : 1));
		statusLabel.setText("<html><b>" + escape(item) + "</b> — "
			+ drops.size() + (drops.size() == 1 ? " source" : " sources") + "</html>");

		resultsPanel.add(buildHeader(item));
		final Map<String, List<JPanel>> sections = new LinkedHashMap<>();
		for (final WikiDropClient.Drop drop : drops)
		{
			sections.computeIfAbsent(displayCategory(drop.getMonster(), drop.getCombatLevel()),
				k -> new ArrayList<>()).add(buildRow(drop));
		}
		renderSections(sections);
	}

	private void showGrouped(String query, List<String> items, Map<String, List<WikiDropClient.Drop>> map)
	{
		final Map<String, Group> groups = new LinkedHashMap<>();
		for (final String item : items)
		{
			for (final WikiDropClient.Drop drop : map.getOrDefault(item, List.of()))
			{
				if (!sourceAllowed(drop.getMonster(), drop.getCombatLevel()))
				{
					continue;
				}
				final String key = drop.getMonster() + " | " + drop.getCombatLevel();
				final Group g = groups.computeIfAbsent(key,
					k -> new Group(drop.getMonster(), drop.getCombatLevel()));
				g.add(item, drop.getRarity());
			}
		}

		final List<Group> sorted = new ArrayList<>(groups.values());
		sorted.sort((a, b) ->
		{
			final int ca = a.level > 0 ? 0 : 1;
			final int cb = b.level > 0 ? 0 : 1;
			if (ca != cb)
			{
				return Integer.compare(ca, cb);
			}
			if (a.level != b.level)
			{
				return Integer.compare(a.level, b.level);
			}
			return a.name.compareToIgnoreCase(b.name);
		});

		if (sorted.isEmpty())
		{
			statusLabel.setText("<html>No sources for items matching <b>" + escape(query) + "</b>.</html>");
			return;
		}

		statusLabel.setText("<html><b>" + escape(query) + "</b> — " + items.size()
			+ " items, " + sorted.size() + " sources</html>");
		final Map<String, List<JPanel>> sections = new LinkedHashMap<>();
		for (final Group g : sorted)
		{
			sections.computeIfAbsent(displayCategory(g.name, g.level),
				k -> new ArrayList<>()).add(buildGroupRow(g));
		}
		renderSections(sections);
	}

	/** Aggregates the matched item types dropped by one source. */
	private static final class Group
	{
		private final String name;
		private final int level;
		private final Map<String, String> types = new LinkedHashMap<>();

		Group(String name, int level)
		{
			this.name = name;
			this.level = level;
		}

		void add(String item, String rarity)
		{
			types.putIfAbsent(item, rarity);
		}
	}

	// ---------- rendering ----------

	private JPanel buildHeader(String itemName)
	{
		final JPanel header = new JPanel(new BorderLayout(8, 0));
		header.setBorder(BorderFactory.createEmptyBorder(4, 2, 6, 2));
		header.setAlignmentX(Component.LEFT_ALIGNMENT);
		header.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		final int id = itemId(itemName);
		if (id > 0)
		{
			final JLabel icon = new JLabel();
			icon.setPreferredSize(new Dimension(32, 32));
			final AsyncBufferedImage img = itemManager.getImage(id);
			img.addTo(icon);
			header.add(icon, BorderLayout.WEST);
		}

		final JLabel name = new JLabel(itemName);
		name.setForeground(Color.WHITE);
		name.setFont(FontManager.getRunescapeBoldFont());
		header.add(name, BorderLayout.CENTER);

		attachInteractions(header, itemName);
		return header;
	}

	private JPanel buildRow(WikiDropClient.Drop drop)
	{
		final JPanel row = newRow();
		final JLabel icon = newIconLabel();
		iconTargets.add(new IconTarget(drop.getMonster(), icon));
		row.add(nameLevelLine(icon, drop.getMonster(), drop.getCombatLevel()));

		final String detailText = (formatRarity(drop.getRarity()) + qtySuffix(drop.getQuantity())).trim();
		if (!detailText.isEmpty())
		{
			final JLabel detail = new JLabel(detailText);
			detail.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			detail.setFont(FontManager.getRunescapeSmallFont());
			detail.setAlignmentX(Component.LEFT_ALIGNMENT);
			row.add(detail);
		}

		finishRow(row, drop.getMonster());
		return row;
	}

	private JPanel buildGroupRow(Group g)
	{
		final JPanel row = newRow();
		final JLabel icon = newIconLabel();
		iconTargets.add(new IconTarget(g.name, icon));
		row.add(nameLevelLine(icon, g.name, g.level));

		for (final Map.Entry<String, String> e : g.types.entrySet())
		{
			final String rar = formatRarity(e.getValue());
			final String text = rar.isEmpty() ? e.getKey() : e.getKey() + "  —  " + rar;
			final JLabel type = new JLabel(text);
			type.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			type.setFont(FontManager.getRunescapeSmallFont());
			type.setAlignmentX(Component.LEFT_ALIGNMENT);
			row.add(type);
		}

		finishRow(row, g.name);
		return row;
	}

	private static final Color ROW_BG = ColorScheme.DARKER_GRAY_COLOR;
	private static final Color ROW_HOVER = new Color(120, 32, 32);

	private static JPanel newRow()
	{
		final JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.DARK_GRAY_COLOR),
			BorderFactory.createEmptyBorder(4, 4, 5, 4)));
		row.setBackground(ROW_BG);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		return row;
	}

	private static JPanel nameLevelLine(JLabel icon, String sourceName, int level)
	{
		final JPanel line = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		line.setOpaque(false);
		line.setAlignmentX(Component.LEFT_ALIGNMENT);

		line.add(icon);

		final JLabel name = new JLabel(sourceName);
		name.setForeground(Color.WHITE);
		line.add(name);

		if (level > 0)
		{
			final JLabel lvl = new JLabel("Lvl " + level);
			lvl.setForeground(new Color(0xC8, 0xC8, 0xC8));
			line.add(lvl);
		}
		return line;
	}

	/** A fixed-size placeholder label the source thumbnail loads into. */
	private static JLabel newIconLabel()
	{
		final JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(24, 24));
		icon.setHorizontalAlignment(SwingConstants.CENTER);
		return icon;
	}

	private void finishRow(JPanel row, String wikiTarget)
	{
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		attachInteractions(row, wikiTarget);
	}

	private void attachInteractions(JPanel row, String wikiTarget)
	{
		final MouseAdapter adapter = new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				row.setBackground(ROW_HOVER);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				final Point p = SwingUtilities.convertPoint((Component) e.getSource(), e.getPoint(), row);
				if (!row.contains(p))
				{
					row.setBackground(ROW_BG);
				}
			}

			@Override
			public void mouseClicked(MouseEvent e)
			{
				LinkBrowser.browse(wikiUrl(wikiTarget));
			}
		};
		addListenerDeep(row, adapter);
		row.setCursor(new Cursor(Cursor.HAND_CURSOR));
	}

	private static void addListenerDeep(Component comp, MouseAdapter l)
	{
		comp.addMouseListener(l);
		if (comp instanceof Container)
		{
			for (final Component child : ((Container) comp).getComponents())
			{
				addListenerDeep(child, l);
			}
		}
	}

	private static String wikiUrl(String pageName)
	{
		return "https://oldschool.runescape.wiki/w/" + pageName.trim().replace(' ', '_');
	}

	/** Item id for its icon, resolved from the client's item list. */
	private int itemId(String name)
	{
		for (final ItemPrice ip : itemManager.search(name))
		{
			if (ip.getName().equalsIgnoreCase(name))
			{
				return ip.getId();
			}
		}
		return -1;
	}

	/**
	 * Whether a source passes the current category filters. Monsters (combat
	 * level &gt; 0) are never hidden here; only non-combat sources (chests,
	 * clue caskets, minigame rewards, event drops) are classified and filtered.
	 * Classification is name-heuristic and easy to extend.
	 */
	private boolean sourceAllowed(String sourceName, int level)
	{
		if (level > 0)
		{
			return true;
		}
		switch (category(sourceName))
		{
			case CLUE:
				return config.showClues();
			case CHEST:
				return config.showChests();
			case MINIGAME:
				return config.showMinigames();
			case EVENT:
				return config.showEvents();
			default:
				return true;
		}
	}

	private enum Cat { CHEST, CLUE, MINIGAME, EVENT, OTHER }

	private static final String[] EVENT_WORDS = {
		"event", "christmas", "halloween", "easter", "birthday", "valentine",
		"yule", "anniversary", "holiday", "diwali"
	};

	private static final String[] MINIGAME_WORDS = {
		"barbarian assault", "pest control", "nightmare zone", "fishing trawler",
		"tempoross", "wintertodt", "guardians of the rift", "soul wars", "gauntlet",
		"fight cave", "inferno", "castle wars", "last man standing", "mahogany homes",
		"volcanic mine", "trouble brewing", "gnome restaurant", "rogues' den",
		"hallowed sepulchre", "sepulchre", "agility dispenser", "pyramid plunder",
		"tithe farm", "temple trek", "shades of mort", "mage arena", "brimhaven agility",
		"giants' foundry", "giants foundry", "blast furnace", "colosseum"
	};

	private static Cat category(String sourceName)
	{
		final String n = sourceName.toLowerCase();
		if (n.contains("clue") || n.contains("casket"))
		{
			return Cat.CLUE;
		}
		if (n.contains("chest"))
		{
			return Cat.CHEST;
		}
		if (containsAny(n, EVENT_WORDS))
		{
			return Cat.EVENT;
		}
		if (containsAny(n, MINIGAME_WORDS))
		{
			return Cat.MINIGAME;
		}
		return Cat.OTHER;
	}

	private static boolean containsAny(String haystack, String[] needles)
	{
		for (final String needle : needles)
		{
			if (haystack.contains(needle))
			{
				return true;
			}
		}
		return false;
	}

	private static String qtySuffix(String qty)
	{
		if (qty == null || qty.isEmpty() || qty.equals("1"))
		{
			return "";
		}
		return "  ·  ×" + qty;
	}

	private static final Pattern FRACTION = Pattern.compile("(\\d+(?:\\.\\d+)?)/(\\d+(?:\\.\\d+)?)");

	private static String formatRarity(String rarity)
	{
		if (rarity == null || rarity.isEmpty())
		{
			return "";
		}
		final boolean approx = rarity.startsWith("~");
		final String body = approx ? rarity.substring(1) : rarity;
		if (body.equalsIgnoreCase("Always"))
		{
			return "Always";
		}
		final Matcher m = FRACTION.matcher(body);
		if (m.matches())
		{
			final double num = Double.parseDouble(m.group(1));
			final double den = Double.parseDouble(m.group(2));
			if (num > 0)
			{
				return (approx ? "~1/" : "1/") + Math.round(den / num);
			}
		}
		return rarity;
	}

	private void revalidateResults()
	{
		resultsPanel.revalidate();
		resultsPanel.repaint();
	}

	private static String escape(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
