#!/bin/bash
# Copies the Wurmpedia item pages into mods/wikihelper/pages.txt. Needs a JDK and an internet connection.
#
#   ./import.sh --only "Bulk storage bin" --tree    check ONE page and show how it was read (writes nothing)
#   ./import.sh --limit 40                          trial run of 40 pages -> mods/wikihelper/pages.trial.txt
#   ./import.sh --titles "Skills,Fighting,Bulk storage bin"   import only these pages (a quick real test set)
#   ./import.sh                                     the full import -> mods/wikihelper/pages.txt
#   ./import.sh --images mods/wikihelper/images     full import and pre-download every picture (large)
#
# Pages are cached in ./wiki-cache, so stopping and re-running picks up where it left off.
cd "$(dirname "$0")"
OUT=mods/wikihelper/pages.txt
for a in "$@"; do [ "$a" = "--limit" ] && OUT=mods/wikihelper/pages.trial.txt; done
{
  mkdir -p tools/out
  if javac -nowarn -d tools/out tools/WikiImporter.java; then
    java -Djava.awt.headless=true -cp tools/out WikiImporter --out "$OUT" --cache wiki-cache "$@"
  else
    echo "FAILED to compile the importer. Make sure a JDK (not just Java) is installed."
  fi

echo
echo "================ READY-TO-COPY PACKAGE ================"
echo "Folder: $(pwd)/mods"
[ -f mods/wikihelper.properties ] && echo "  wikihelper.properties ........ yes" || echo "  wikihelper.properties ........ MISSING"
[ -f mods/wikihelper/wikihelper.jar ] && echo "  wikihelper/wikihelper.jar .... yes (built $(date -r mods/wikihelper/wikihelper.jar '+%Y-%m-%d %H:%M'))" || echo "  wikihelper/wikihelper.jar .... MISSING (run ./build.sh)"
echo "  wikihelper/pages.txt ......... $(grep -c '^@page' mods/wikihelper/pages.txt 2>/dev/null || echo 0) pages"
echo "  wikihelper/articles .......... $(ls mods/wikihelper/articles 2>/dev/null | wc -l) article files"
echo "To install: copy wikihelper.properties and the wikihelper folder from that mods folder into WurmLauncher\\mods yourself."
echo "(These scripts never write to the game folder.)"
} 2>&1 | tee import.log
echo
echo "Output also saved to import.log in this folder."
read -n 1 -s -r -p "Press any key to close..."
