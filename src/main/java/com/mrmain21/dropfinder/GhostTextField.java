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

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * A text field that shows an inline autocomplete suggestion: the completion is
 * drawn in a dim (but still legible) colour immediately after what the user has
 * typed, like shell/IDE ghost text. Pressing Tab or the Right arrow (with the
 * caret at the end) accepts it; Enter runs the search.
 */
class GhostTextField extends JTextField
{
	/** Colour of the ghost completion text -- grey, but light enough to read. */
	private static final Color GHOST_COLOR = new Color(140, 140, 140);

	/** Given the current typed text, returns the full completed word, or null. */
	private final transient Function<String, String> completer;

	/** Called with the chosen text when the user commits a search (Enter/accept). */
	private final transient Consumer<String> onSearch;

	/** The suffix currently drawn as ghost text (never null). */
	private transient String ghost = "";

	GhostTextField(Function<String, String> completer, Consumer<String> onSearch)
	{
		this.completer = completer;
		this.onSearch = onSearch;

		// Let Tab reach us instead of moving focus, so it can accept the ghost.
		setFocusTraversalKeysEnabled(false);

		final int cond = JComponent.WHEN_FOCUSED;
		getInputMap(cond).put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "acceptGhost");
		getInputMap(cond).put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "acceptGhostAtEnd");
		getInputMap(cond).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "search");

		getActionMap().put("acceptGhost", new AbstractAction()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				acceptGhost();
			}
		});
		getActionMap().put("acceptGhostAtEnd", new AbstractAction()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				// Right-arrow accepts only when the caret is already at the
				// end; otherwise it should just move the caret as usual.
				if (getCaretPosition() == getText().length() && hasGhost())
				{
					acceptGhost();
				}
				else if (getCaretPosition() < getText().length())
				{
					setCaretPosition(getCaretPosition() + 1);
				}
			}
		});
		getActionMap().put("search", new AbstractAction()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				fireSearch();
			}
		});

		getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				recompute();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				recompute();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				recompute();
			}
		});
	}

	/** Recomputes the ghost suffix from the current text and repaints. */
	private void recompute()
	{
		final String typed = getText();
		ghost = "";

		if (!typed.isEmpty())
		{
			final String full = completer.apply(typed);
			// Only show the completion when it genuinely extends the typed
			// text as a prefix (case-insensitive) -- otherwise the ghost tail
			// wouldn't line up with what's been typed.
			if (full != null
				&& full.length() > typed.length()
				&& full.toLowerCase().startsWith(typed.toLowerCase()))
			{
				ghost = full.substring(typed.length());
			}
		}

		repaint();
	}

	/** The full word the field would commit to right now (typed + ghost). */
	String getCompletedText()
	{
		return getText() + ghost;
	}

	boolean hasGhost()
	{
		return !ghost.isEmpty();
	}

	/** Accept the ghost suggestion, filling the field with the full word. */
	void acceptGhost()
	{
		if (!ghost.isEmpty())
		{
			setText(getCompletedText());
		}
	}

	void fireSearch()
	{
		onSearch.accept(getText().trim());
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		super.paintComponent(g);

		if (ghost.isEmpty())
		{
			return;
		}

		final Font font = getFont();
		final FontMetrics fm = g.getFontMetrics(font);
		final Insets insets = getInsets();

		// Draw the ghost right after the typed text, sharing the same baseline.
		final int typedWidth = fm.stringWidth(getText());
		final int x = insets.left + typedWidth;
		final int y = insets.top + fm.getAscent()
			+ (getHeight() - insets.top - insets.bottom - fm.getHeight()) / 2;

		g.setColor(GHOST_COLOR);
		g.setFont(font);
		g.drawString(ghost, x, y);
	}
}
