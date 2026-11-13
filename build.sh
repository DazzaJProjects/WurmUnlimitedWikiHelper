#!/bin/bash
# Builds mods/wikihelper/wikihelper.jar. Needs a JDK (not just a JRE).
# Put client.jar, common.jar and modlauncher.jar from your WurmLauncher folder into ./libs yourself (once).
# Nothing here writes to the game folder. (If you want the jars copied for you, run:  WURM_DIR="/c/path/to/WurmLauncher" ./build.sh)
cd "$(dirname "$0")"
mkdir -p libs
{
  echo "Working folder: $(pwd)"
  if [ -n "$WURM_DIR" ] && ! ls libs/*.jar >/dev/null 2>&1 && [ -f "$WURM_DIR/client.jar" ]; then
    echo "libs is empty. Copying the game jars (read-only) from: $WURM_DIR"
    for j in client.jar common.jar modlauncher.jar; do [ -f "$WURM_DIR/$j" ] && cp "$WURM_DIR/$j" libs/; done
  fi
  echo "Jars in libs: $(ls libs 2>&1 | tr '\n' ' ')"
  if ! ls libs/*.jar >/dev/null 2>&1; then
    echo "FAILED: no game jars in ./libs. Copy client.jar, common.jar and modlauncher.jar from your WurmLauncher folder into it."
  else
    echo "Sources found: $(find src/main/java -name '*.java' 2>&1 | wc -l)"
    rm -rf build && mkdir build
    if javac -source 8 -target 8 -cp "libs/*" -d build $(find src/main/java -name '*.java'); then
      mkdir -p mods/wikihelper
      jar cf mods/wikihelper/wikihelper.jar -C build . && echo "SUCCESS: built mods/wikihelper/wikihelper.jar"
    else
      echo "FAILED: see errors above"
    fi
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
} 2>&1 | tee build.log
echo
echo "Output also saved to build.log in this folder."
read -n 1 -s -r -p "Press any key to close..."
