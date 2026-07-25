# Drop Finder

Search for an item and instantly see **every source that drops it** — monsters,
raid/reward chests, and caskets — each with its combat level and drop rate,
without leaving the client.

Drop data is sourced from the [Old School RuneScape Wiki](https://oldschool.runescape.wiki/).

## Features

- **Item search** — type an item (e.g. `Dragon boots`) and get the full list of
  sources that drop it, with drop rate and quantity.
- **Grayed-out inline autocomplete** — as you type, the closest matching item is
  previewed in dim ghost text inside the search box. Press **Tab** or **→** to
  accept it, **Enter** to search.
- **Vague / partial search** — search a term like `chainbody` and results are
  **grouped by source**, listing the specific matching item *types* in a small
  font beneath each source name (e.g. every NPC that drops any kind of chainbody,
  and which ones).
- **Combat level beside the name** — each monster shows its level inline; chests
  and objects (no combat level) simply omit it.
- **Click through to the Wiki** — hovering a result highlights it; clicking opens
  that monster/chest's OSRS Wiki page in your browser. The item header links to
  the item's own page.
- **Two ways to open it** — a toolbar panel button, plus a rebindable hotkey
  (default **Ctrl+P**) and an optional in-game button below the minimap.
- **Source filters** — toggle categories of source on/off (currently: **Chests**).

## Usage

Open the search panel any of these ways:

- Click the **Drop Finder** icon in the RuneLite side toolbar, **or**
- Press the hotkey (**Ctrl+P** by default), **or**
- Click the lookup button below the minimap (if enabled).

Then type an item name. An exact item shows its full source list; a partial term
shows sources grouped by monster/chest with the matching item types underneath.

## Configuration

Found under **Configuration → Drop Finder**:

| Setting | Default | Description |
| --- | --- | --- |
| **Search hotkey** | `Ctrl+P` | Opens the search panel and focuses the search box. Rebindable to any key/combo. |
| **Show minimap button** | On | Shows the clickable lookup button below the minimap. |
| **Chests** (Source filters) | On | When unchecked, chest / reward-casket sources are excluded from results. |

Toggling a filter re-renders the current search immediately.

## Data & attribution

**Nothing is bundled.** Drop data is fetched **live** from the **Old School
RuneScape Wiki** at search time (the `{{Drop sources}}` / "Item sources" tables,
via the MediaWiki parse API) and cached in memory for the session. Item names
and icons come from the game client via RuneLite's `ItemManager`.

Because results are always pulled fresh from the wiki, they stay current with
game updates automatically. Wiki content is licensed **CC BY-NC-SA 3.0**;
attribution is given here and results link back to the wiki.

Requires an internet connection to look up drops.

## Building

This is a standalone RuneLite external plugin (built against the published
client via Gradle):

```bash
./gradlew build   # compile + package
./gradlew run     # launch RuneLite (developer mode) with this plugin loaded
```

`run` starts the client with the plugin side-loaded; enable **Drop Finder** in
the plugin list, then open it from the toolbar or with the hotkey.

## Limitations

- Coverage is currently the Wiki's **tradeable** items. Non-tradeable-only drops
  (e.g. some clue/quest items) may not appear yet.
- Only tidy drop rates (`1/128`, `~1/9576`, `Always`) are shown; complex or
  point-based rates (e.g. raid unique tables) are listed **without** a rate
  rather than with a misleading number.
- Items that are *created* rather than dropped (e.g. crystal equipment) have no
  drop source and won't appear.
- "Chest" detection for the filter is name-based (`chest` / `casket`).

## License

BSD 2-Clause. See [LICENSE](LICENSE).
