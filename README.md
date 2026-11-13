# Wiki Helper

**The Wurmpedia, inside Wurm Unlimited.** Wiki Helper is a client mod that puts a searchable copy of the wiki in the
game, so you can look up a recipe, a skill or a fighting mechanic without alt-tabbing to a browser.

Press **Esc**, choose **Wiki Helper**, type `bsb`, and get the Bulk storage bin page: what it is made from, the skill it
uses, and the full article, laid out like the wiki and styled to fit the game's own UI.

<!-- Add screenshots here, for example:
![The search tab](docs/search.png)
![A page with contents and a table](docs/page.png)
-->

## Features

- **In the ESC menu.** Opens as a normal movable, resizable window.
- **Search** by item name, nickname (`bsb`), ingredient (`iron ribbon`), guide or skill. Results update as you type.
- **Wiki-style pages.** Title plate, picture, an **Examine / Creation / Total materials / Result / Skill** item box,
  then the whole article: headings, paragraphs, bullet and numbered lists, and striped tables that fit the window.
- **Clickable everything.** Click an ingredient to jump straight to its page, then **< Back**. Links inside articles and
  tables work the same way.
- **Contents list.** Every long page starts with a numbered contents box. Click an entry to scroll to that section, and
  use **[top]** on any section bar to return.
- **Find in Wiki.** Right-click an item or object in the world or your inventory and choose **Find in Wiki**.
- **Most searched and Recent tabs** remember what you actually look up.
- **Works offline.** The wiki is copied to your machine once, and pictures are cached after the first view.
- **Your own notes.** Add or correct entries in `custom.txt`; they are never overwritten by an import.

## Requirements

- Wurm Unlimited (client)
- [Wurm Unlimited Client Mod Launcher](https://github.com/ago1024/WurmClientModLauncher) installed and working
- A **JDK** (not just a JRE), version 8 or newer, to build the mod
- Windows with Git Bash is what the scripts were written for. They are plain bash and should work elsewhere.

## Getting the game libraries (`libs/`): required, and not included

To compile, this project needs three jar files from your own Wurm Unlimited installation. They are copyrighted game
files, so **they are not in this repository and you must supply them yourself.**

1. Create the folder `libs/` in the project root if it is not there.
2. Copy these three files from your game's `WurmLauncher` folder into `libs/`:

   | File | What it is |
   |---|---|
   | `client.jar` | the game client |
   | `common.jar` | shared game classes |
   | `modlauncher.jar` | the client mod launcher |

   On a default Steam install the folder is
   `C:\Program Files (x86)\Steam\steamapps\common\Wurm Unlimited\WurmLauncher`.
3. Do not commit them. The included `.gitignore` keeps `libs/*.jar` out of git.

The build only reads these files. Nothing in this project writes to your game folder.

## Build

From the project folder (Git Bash):

```bash
./build.sh
```

It compiles the mod and writes `mods/wikihelper/wikihelper.jar`. Output is also saved to `build.log`, and the window
waits for a keypress so you can read any errors. If you see `Jars in libs:` followed by nothing, the `libs/` step above
has not been done.

## Fill it with the wiki (one-off import)

The mod is only as complete as its data. A built-in importer copies pages from Wurmpedia into the mod's own format.
Run these in order:

```bash
# 1. Check one page is understood correctly (writes nothing)
./import.sh --only "Skills" --tree

# 2. Import a small real test set
./import.sh --titles "Skills,Fighting,Bulk storage bin,Iron ribbon,Large nails"

# 3. Safe trial of the first 40 pages (writes pages.trial.txt, which the game ignores)
./import.sh --limit 40

# 4. The full import (can be stopped and re-run; downloaded pages are cached in wiki-cache/)
./import.sh
```

Add `--images mods/wikihelper/images` to the full import to also pre-download every picture (this is large). It waits a
quarter of a second between requests to be polite to the site, and prints an estimate of the time left.

The importer writes `mods/wikihelper/pages.txt` and one article file per page into `mods/wikihelper/articles/`.
Pages are read from the wiki's rendered HTML, so the item box, article text, tables, links and redirects all come across.
Redirects (like *BSB*) become search nicknames.

## Install

You can copy the finished mod into the game yourself (Release or from source); none of the scripts touch your game folder.

The project's own **`mods/` folder is the finished mod**. Both `build.sh` and `import.sh` end with a
**READY-TO-COPY PACKAGE** summary showing whether the jar is built and how many pages and articles it holds.

Copy these two things from the project's `mods/` folder into your `WurmLauncher\mods` folder, replacing what is there:

```
wikihelper.properties
wikihelper\          (the whole folder: wikihelper.jar, pages.txt, custom.txt, articles\)
```

Then start the game through the mod launcher. The client log should say `Wiki: loaded N entries`, and **Wiki Helper**
should appear in the **Esc** menu. Copy again after every new build or import: the game only reads the copy in
`WurmLauncher\mods`.

## Configuration

`mods/wikihelper.properties`:

| Setting | Default | What it does |
|---|---|---|
| `downloadImages` | `true` | Fetch an entry's picture the first time it is opened, then cache it on disk |
| `popularCount` | `10` | How many entries the **Most searched** tab shows |
| `recentCount` | `5` | How many entries the **Recent** tab shows |
| `contentsAtTop` | `true` | Contents list under the title (`true`) or after the item box (`false`) |
| `onlyWhenFound` | `true` | Only show **Find in Wiki** for things that exist in the database |
| `debug` | `false` | Log popup details; use this if **Find in Wiki** does not appear |

## Your own entries (`custom.txt`)

`mods/wikihelper/custom.txt` is for your notes and corrections. An entry here replaces the imported entry with the same
name, and the file is never overwritten by an import:

```
@page My storage notes
@alias msn
@body
H2 Where things go
P Bulk items live in [[Bulk storage bin|the bin]].
L Tools in the chest
L2 Hammers on the left
TH Item	Count
TR Planks	200
```

Article lines are tagged: `H2`/`H3` headings, `P` paragraph, `L`/`L2` bullets, `N`/`N2` numbered items, `TH`/`TR` table
header/rows (cells separated by **tabs**). `[[Target|shown text]]` makes a clickable link to another entry.

## Project layout

```
src/            the mod (Java): window, page renderer, search, history, image cache
tools/          WikiImporter.java: the one-off Wurmpedia importer
libs/           YOU add client.jar, common.jar, modlauncher.jar here (not in git)
mods/           the finished mod, ready to copy into WurmLauncher\mods
build.sh        compile the mod
import.sh        run the importer
```
Mod created using Claude
