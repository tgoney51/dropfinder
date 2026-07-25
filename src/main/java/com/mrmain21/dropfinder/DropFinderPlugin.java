/*
 * Copyright (c) 2026
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

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.HotkeyListener;
import net.runelite.client.util.ImageUtil;

@PluginDescriptor(
	name = "Drop Finder",
	description = "Search an item to see every monster that drops it, with combat levels and drop rates",
	tags = {"drop", "drops", "item", "monster", "npc", "loot", "search", "table"}
)
public class DropFinderPlugin extends Plugin
{
	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ItemManager itemManager;

	@Inject
	private WikiDropClient wikiClient;

	@Inject
	private KeyManager keyManager;

	@Inject
	private DropFinderConfig config;

	private NavigationButton navButton;
	private DropFinderPanel panel;

	private final HotkeyListener searchHotkey = new HotkeyListener(() -> config.searchHotkey())
	{
		@Override
		public void hotkeyPressed()
		{
			openSearch();
		}
	};

	@Provides
	DropFinderConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(DropFinderConfig.class);
	}

	@Override
	protected void startUp()
	{
		panel = new DropFinderPanel(wikiClient, itemManager, config);
		final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "drop_finder_icon.png");

		navButton = NavigationButton.builder()
			.tooltip("Drop Finder")
			.icon(icon)
			.priority(7)
			.panel(panel)
			.build();

		clientToolbar.addNavigation(navButton);

		keyManager.registerKeyListener(searchHotkey);
	}

	@Override
	protected void shutDown()
	{
		clientToolbar.removeNavigation(navButton);
		keyManager.unregisterKeyListener(searchHotkey);
		navButton = null;
		panel = null;
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if ("dropfinder".equals(event.getGroup()) && panel != null)
		{
			SwingUtilities.invokeLater(panel::refresh);
		}
	}

	/** Opens the Drop Finder side panel (with the search bar) on the Swing thread. */
	void openSearch()
	{
		SwingUtilities.invokeLater(() -> clientToolbar.openPanel(navButton));
	}
}
