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

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;

@ConfigGroup("dropfinder")
public interface DropFinderConfig extends Config
{
	@ConfigItem(
		keyName = "searchHotkey",
		name = "Search hotkey",
		description = "Opens the Drop Finder search panel and focuses the search box"
	)
	default Keybind searchHotkey()
	{
		return new Keybind(KeyEvent.VK_P, InputEvent.CTRL_DOWN_MASK);
	}

	@ConfigSection(
		name = "Source filters",
		description = "Choose which kinds of drop sources to show",
		position = 10
	)
	String filters = "filters";

	@ConfigItem(
		keyName = "showChests",
		name = "Chests",
		description = "Include chest / reward-casket sources in results",
		section = filters
	)
	default boolean showChests()
	{
		return true;
	}
}
