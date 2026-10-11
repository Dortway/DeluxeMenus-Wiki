#!/usr/bin/env bash
# Linux/macOS build.
#   ./build.sh --server /path/to/server   compile against the real server jars (production build)
#   ./build.sh --stubs                    offline build against stubs/ (CI / environments without Paper)
# Both run the unit tests. Needs JDK 25 (JAVA_HOME or javac on PATH).
set -euo pipefail
cd "$(dirname "$0")"
VERSION=1.0.0
MODE="${1:---stubs}"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}"
EXOSKILLS_JAR="${EXOSKILLS_JAR:-lib/test/ExoSkills-1.0.1.jar}"
rm -rf build && mkdir -p build/classes build/test-classes build/stubs lib/test

fetch() { # group/artifact/version file
  [ -s "lib/test/$2" ] || curl -sSfL -o "lib/test/$2" "https://repo1.maven.org/maven2/$1/$2"
}

case "$MODE" in
  --server)
    SERVER="${2:?usage: ./build.sh --server /path/to/server}"
    CP="$(find "$SERVER/libraries" -name '*.jar' | tr '\n' ':')"
    for p in 'ExcellentEconomy*.jar' 'nightcore*.jar' 'NightCore*.jar' 'PlaceholderAPI*.jar' 'Vault*.jar'; do
      CP="$CP$(find "$SERVER/plugins" -maxdepth 1 -name "$p" | tr '\n' ':')"
    done
    echo "$CP" | grep -q ExcellentEconomy || { echo "ExcellentEconomy jar not found in $SERVER/plugins" >&2; exit 1; }
    TEST_CP="$CP"
    ;;
  --stubs)
    python3 -I stubs/generate_stubs.py >/dev/null
    "${JAVA_BIN}javac" --release 25 -nowarn -d build/stubs $(find stubs/src -name '*.java')
    CP="build/stubs"
    fetch org/xerial/sqlite-jdbc/3.53.4.0 sqlite-jdbc-3.53.4.0.jar
    fetch org/slf4j/slf4j-api/1.7.36 slf4j-api-1.7.36.jar
    TEST_CP="build/stubs:lib/test/sqlite-jdbc-3.53.4.0.jar:lib/test/slf4j-api-1.7.36.jar"
    ;;
  *) echo "usage: ./build.sh [--stubs | --server DIR]" >&2; exit 2 ;;
esac

"${JAVA_BIN}javac" --release 25 -encoding UTF-8 -Xlint:all,-serial,-processing,-try -cp "$CP" -d build/classes $(find src/main/java -name '*.java')
cp -r src/main/resources/. build/classes/
sed -i.bak "s/\${version}/$VERSION/" build/classes/plugin.yml && rm -f build/classes/plugin.yml.bak
mkdir -p dist
"${JAVA_BIN}jar" --create --file "dist/ExoEconomy-$VERSION.jar" -C build/classes .
"${JAVA_BIN}javac" --release 25 -encoding UTF-8 -cp "build/classes:$CP" -d build/test-classes $(find src/test/java -name '*.java')
ARGS=(src/main/resources/prices.yml)
[ -f "$EXOSKILLS_JAR" ] && ARGS+=("$EXOSKILLS_JAR")
"${JAVA_BIN}java" -ea --enable-native-access=ALL-UNNAMED -cp "build/classes:build/test-classes:$TEST_CP" net.exoeconomy.CoreTests "${ARGS[@]}"
sha256sum "dist/ExoEconomy-$VERSION.jar" 2>/dev/null || shasum -a 256 "dist/ExoEconomy-$VERSION.jar"
[ "$MODE" = "--stubs" ] && echo "NOTE: built against compile-only stubs. Rebuild with --server (or build.ps1) against your real server jars before production."
exit 0
