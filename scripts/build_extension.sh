#!/bin/zsh
# Build one or more GAMA extensions into jars for the native Android app.
#
# Each GAMA extension is a standalone project (like gama.extension.androidsensor/)
# with a standard layout:
#   <project>/
#     src/...                       Java sources (skill, helpers, ...)
#     src/gaml/additions/<short>/GamlAdditions.java   GAML additions loader
#     gaml/                        (optional) extra GAML resource files
#     models/                      (optional) example models
#
# The script compiles the extension against the app's own GAMA jars and emits
#   native-app/app/libs/gama.extension.<short>.jar
# which the app picks up automatically (app/build.gradle line 307 fileTree) and
# which GamaNativeBootstrap.pluginNames must list as "gama.extension.<short>".
#
# Usage:
#   scripts/build_extension.sh [project-dir ...]
#   scripts/build_extension.sh --clean [project-dir ...]
#   (no args)          -> build every gama.extension.* project at repo root
#   project-dir(s)     -> build only the given project(s)
#
# Requirements: JDK 17+ (the app compiles with --release 17), jar on PATH.
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"            # repo root
APP="$ROOT/native-app"
LIBS="$APP/app/libs"
BUILD_DIR="${TMPDIR:-/tmp}/gama_ext_build"

# Android framework jar (needed only by extensions that call android.* directly;
# harmless otherwise).
ANDROID_JAR=""
for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk"; do
  if [ -n "$sdk" ] && [ -d "$sdk/platforms" ]; then
    ANDROID_JAR="$(ls "$sdk"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)"
    [ -n "$ANDROID_JAR" ] && break
  fi
done

CLEAN=0
PROJECTS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --clean) CLEAN=1; shift;;
    -*) echo "unknown arg: $1" >&2; exit 1;;
    *) PROJECTS+=("$1"); shift;;
  esac
done

if [[ ${#PROJECTS[@]} -eq 0 ]]; then
  PROJECTS=( "$ROOT"/gama.extension.* )
fi

if [[ $CLEAN -eq 1 ]]; then
  for p in "${PROJECTS[@]}"; do
    short="${p##*/}"
    short="${short#gama.extension.}"
    rm -f "$LIBS/gama.extension.$short.jar"
    echo "removed $LIBS/gama.extension.$short.jar"
  done
  exit 0
fi

mkdir -p "$BUILD_DIR"

CP="$(echo "$LIBS"/*.jar | tr ' ' ':')"
if [ -n "$ANDROID_JAR" ]; then
  CP="$ANDROID_JAR:$CP"
fi

for p in "${PROJECTS[@]}"; do
  if [ ! -d "$p/src" ]; then
    echo "SKIP $p (no src/)" >&2
    continue
  fi
  name="${p##*/}"
  short="${name#gama.extension.}"
  out="$BUILD_DIR/$name"
  rm -rf "$out"
  mkdir -p "$out"

  SRC="$(find "$p/src" -name '*.java')"
  if [ -z "$SRC" ]; then
    echo "SKIP $p (no java sources)" >&2
    continue
  fi

# The app registers GAML additions by loading gaml/additions/<short>/GamlAdditions
# by name (see GamaNativeBootstrap), so each extension must ship that class. GAMA's
# annotation processor (gama.processor.jar) is auto-discovered on the classpath and
# would otherwise also generate a stray additions class; -proc:none disables it.
  echo "== Building $name -> app/libs/gama.extension.$short.jar"
  if ! javac --release 17 -nowarn -proc:none -classpath "$CP" -d "$out" ${=SRC} > "$BUILD_DIR/$name.log" 2>&1; then
    echo "FAILED: javac errors (see $BUILD_DIR/$name.log)" >&2
    sed -n '1,25p' "$BUILD_DIR/$name.log" >&2
    continue
  fi

  # Ship optional GAML/model resources alongside the classes.
  for extra in gaml models; do
    [ -d "$p/$extra" ] && cp -R "$p/$extra" "$out/"
  done

  if ! jar cf "$LIBS/gama.extension.$short.jar" -C "$out" .; then
    echo "FAILED: jar packaging" >&2
    continue
  fi
  echo "OK: $LIBS/gama.extension.$short.jar ($(du -h "$LIBS/gama.extension.$short.jar" | cut -f1))"
done

echo
echo "Next: ensure GamaNativeBootstrap.pluginNames lists each 'gama.extension.<short>', then rebuild the app."
