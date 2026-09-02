#!/usr/bin/env bash
#
# refresh_gama_deps.sh — pull a new GAMA engine build into the Android app.
#
# The APK runs the real GAMA engine jars (app/libs). Those jars are produced by
# gama-platform/gama; this script is the "update engine" step when that repo
# publishes a new release. It:
#
#   1. resolves the target release (default: latest stable; --tag to pin one),
#      and skips if native-app/gama.engine.version already matches it,
#   2. downloads the GAMA Linux distribution zip from the release assets,
#   3. extracts the engine OSGi bundle jars (plugins/*) into app/libs, both in
#      pristine/ and at the top level (mirroring what the deps bundle ships),
#   4. re-patches and rebuilds (patchGamaJars -> androidsensor extension ->
#      assembleDebug), optionally smoke-testing the APK on a connected device,
#   5. records the new engine in native-app/gama.engine.version.
#
# Non-GAMA jars in app/libs (maven deps, stubs, patched variants) are left as-is;
# a bundle that does not exist in the new release is kept and reported, never
# deleted. Run with --dry-run to see what would change.
#
# Usage:
#   scripts/refresh_gama_deps.sh [options]
#     --tag TAG      use a specific gama-platform/gama release tag
#     --dist-zip P   reuse a downloaded GAMA_*_Linux.zip instead of downloading
#     --dry-run      resolve + report only, change nothing
#     --device       also install the rebuilt APK on a connected device/emulator
#                    and smoke-test that the AndroidDigitalTwinMap model starts
#     --jdk DIR      JDK 21 home (else auto-detected)
#     -h, --help     show this help
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
APP_DIR="$REPO_DIR/native-app"
LIBS_DIR="$APP_DIR/app/libs"
PRISTINE_DIR="$LIBS_DIR/pristine"
VERSION_FILE="$APP_DIR/gama.engine.version"

GAMA_REPO="gama-platform/gama"
TAG=""
DIST_ZIP=""
DRY_RUN=0
DEVICE_TEST=0
JDK_DIR=""
BUNDLES=(
    gama.annotations gama.api gama.core gama.dependencies
    gama.extension.bdi gama.extension.database gama.extension.fipa
    gama.extension.image gama.extension.maths gama.extension.network
    gama.extension.pedestrian gama.extension.serialize gama.extension.stats
    gama.extension.traffic gama.headless gama.library gama.processor
    gama.ui.application gama.ui.display.java2d gama.ui.display.opengl
    gama.ui.editor gama.ui.experiment gama.ui.navigator gama.ui.shared
    gama.ui.viewers gama.workspace gaml.compiler
)
# gama.extension.batch uses a non-standard jar name (SNAPSHOT), handled separately.

usage() { sed -n '2,30p' "${BASH_SOURCE[0]}"; exit 0; }

while [[ $# -gt 0 ]]; do
    case "$1" in
        --tag) TAG="$2"; shift 2;;
        --dist-zip) DIST_ZIP="$2"; shift 2;;
        --dry-run) DRY_RUN=1; shift;;
        --device) DEVICE_TEST=1; shift;;
        --jdk) JDK_DIR="$2"; shift 2;;
        -h|--help) usage;;
        *) echo "unknown option: $1" >&2; usage >&2; exit 1;;
    esac
done

need() { command -v "$1" >/dev/null 2>&1 || { echo "ERROR: '$1' not found in PATH" >&2; exit 1; }; }
need curl; need unzip

py() { python3 -c "$1"; }

# Exit 3 means "engine already current" (a *no-op*, not an error); the caller
# can check $? to skip the rebuild while treating anything else as a failure.

echo "== GAMA engine refresh =="
echo "   repo: $REPO_DIR"

[[ -d "$LIBS_DIR" ]] || { echo "ERROR: no app/libs at $LIBS_DIR" >&2; exit 1; }

# --- 1. resolve target release -------------------------------------------------
current_tag="$(grep -v '^#' "$VERSION_FILE" 2>/dev/null | head -1 | awk '{print $1}')"
current_sha="$(grep -v '^#' "$VERSION_FILE" 2>/dev/null | head -1 | awk '{print $2}')"
echo "   current: ${current_tag:-<(unset)} @ ${current_sha:-<(unset)}"

if [[ -z "$DIST_ZIP" ]]; then
    if [[ -z "$TAG" ]]; then
        echo "== resolving latest stable release of $GAMA_REPO =="
        REL_JSON="$(curl -fsSL "https://api.github.com/repos/$GAMA_REPO/releases/latest")"
    else
        echo "== resolving release $TAG of $GAMA_REPO =="
        REL_JSON="$(curl -fsSL "https://api.github.com/repos/$GAMA_REPO/releases/tags/$TAG")" \
            || { echo "ERROR: release '$TAG' not found (wrong tag, or it is a prerelease)" >&2; exit 1; }
    fi
    RESOLVED_TAG="$(printf '%s' "$REL_JSON" | py 'import json,sys; print(json.load(sys.stdin)["tag_name"])')"
    RESOLVED_SHA="$(printf '%s' "$REL_JSON" | py 'import json,sys; print(json.load(sys.stdin)["target_commitish"])')"
else
    # --dist-zip: derive tag from the zip name and the current pin if possible.
    RESOLVED_TAG="$TAG"
    if [[ -z "$RESOLVED_TAG" ]]; then
        RESOLVED_TAG="$(basename "$DIST_ZIP" | sed -E 's/^GAMA_([^_]+)_.*/\1/')"
        echo "== deriving tag from zip name: $RESOLVED_TAG =="
    fi
    RESOLVED_SHA="$(basename "$DIST_ZIP" | grep -oE '[0-9a-f]{7}' | head -1 || echo unknown)"
fi

echo "   target:  ${RESOLVED_TAG:-<(unknown)} @ ${RESOLVED_SHA:-<(unknown)}"
if [[ "$RESOLVED_TAG" == "$current_tag" ]]; then
    echo "== already on $RESOLVED_TAG — nothing to do =="
    exit 3
fi

# --- pick the Linux zip asset --------------------------------------------------
ASSET_URL=""
if [[ -z "$DIST_ZIP" ]]; then
    ASSET_URL="$(printf '%s' "$REL_JSON" | py '
import json,sys
d=json.load(sys.stdin)
for a in d.get("assets",[]):
    n=a["name"]
    if "_Linux" in n and n.endswith(".zip") and "with_JDK" not in n and "Mac" not in n:
        print(a["browser_download_url"]); break
')"
    [[ -n "$ASSET_URL" ]] || { echo "ERROR: no Linux .zip asset found on release $RESOLVED_TAG" >&2; exit 1; }
    echo "   asset:  $ASSET_URL"
fi

if [[ "$DRY_RUN" -eq 1 ]]; then
    echo "== --dry-run: would download, swap $( for b in "${BUNDLES[@]}" gama.extension.batch; do echo -n "$b "; done) into app/libs, then rebuild =="
    exit 0
fi

# --- 2. download + extract ----------------------------------------------------
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

if [[ -n "$ASSET_URL" ]]; then
    ZIP="$WORK/gama.zip"
    echo "== downloading ${ASSET_URL##*/} =="
    curl -fL --retry 3 "$ASSET_URL" -o "$ZIP"
else
    ZIP="$DIST_ZIP"
    [[ -f "$ZIP" ]] || { echo "ERROR: $ZIP not found" >&2; exit 1; }
fi

echo "== extracting plugins/ from dist (this can take a minute) =="
unzip -q -o "$ZIP" -d "$WORK/dist"
PLUGINS_DIR="$(find "$WORK/dist" -type d -name plugins | head -1)"
[[ -n "$PLUGINS_DIR" ]] || { echo "ERROR: no plugins/ directory inside the dist zip" >&2; exit 1; }
echo "   plugins: $PLUGINS_DIR"

# --- 3. swap the engine bundles ------------------------------------------------
mkdir -p "$PRISTINE_DIR"
UPDATED=0
MISSING=()
for b in "${BUNDLES[@]}" gama.extension.batch; do
    if [[ "$b" == "gama.extension.batch" ]]; then
        NEW="$(find "$PLUGINS_DIR" -maxdepth 1 -type f \( -name 'gama.extension.batch-*.jar' -o -name 'gama.extension.batch_*.jar' \) | head -1 || true)"
    else
        NEW="$(find "$PLUGINS_DIR" -maxdepth 1 -type f -name "${b}_*.jar" ! -name '*.sources.jar' | head -1 || true)"
    fi
    if [[ -z "$NEW" ]]; then
        MISSING+=("$b")
        continue
    fi
    NEW_NAME="$(basename "$NEW")"
    OLD="$(ls "$PRISTINE_DIR"/"$b"*.jar 2>/dev/null | head -1 || true)"
    OLD_NAME="$(basename "$OLD" 2>/dev/null || echo "<none>")"
    # Some upstream jars are signed; strip signature files so the ASM patchers
    # (which open jars with java.util.jar verification) don't throw
    # "Invalid signature file digest" after the class-version rewrite.
    CLEANED="$WORK/$(basename "$NEW")"
    if unzip -l "$NEW" 2>/dev/null | grep -qE 'META-INF/.*\.(SF|RSA|DSA|EC)$'; then
        py "
import shutil, zipfile
src, dst = '$NEW', '$CLEANED'
def drop(name):
    n = name.upper()
    return n.startswith('META-INF/') and (n.endswith('.SF') or n.endswith('.RSA')
        or n.endswith('.DSA') or n.endswith('.EC') or n.endswith('MANIFEST.MF'))
zin = zipfile.ZipFile(src)
zout = zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED)
for i in zin.infolist():
    if drop(i.filename): continue
    zout.writestr(i, zin.read(i.filename))
zout.close(); zin.close()
print('stripped signatures from $(basename "$NEW")')
"
        NEW="$CLEANED"
    fi
    cp -f "$NEW" "$PRISTINE_DIR/$NEW_NAME"
    cp -f "$NEW" "$LIBS_DIR/$NEW_NAME"
    UPDATED=$((UPDATED+1))
    [[ "$OLD_NAME" != "$NEW_NAME" ]] && echo "   $b: $OLD_NAME -> $NEW_NAME"
done

echo "== swapped $UPDATED bundle jar(s)"
if [[ ${#MISSING[@]} -gt 0 ]]; then
    echo "!! bundles NOT found in $RESOLVED_TAG (old pristine kept): ${MISSING[*]}" >&2
fi

printf '%s %s\n' "${RESOLVED_TAG:-unknown}" "${RESOLVED_SHA:-unknown}" > "$VERSION_FILE"
echo "== wrote $VERSION_FILE =="

# --- 4. rebuild ----------------------------------------------------------------
echo "== running patchGamaJars (restores pristine + re-patches) =="
find_jdk() { local d="$1"; [[ -x "$d/bin/java" ]] && "$d/bin/java" -version 2>&1 | grep -q '"21' && echo "$d" && return 0; return 1; }
export JAVA_HOME="${JAVA_HOME:-}"
if [[ -n "$JDK_DIR" ]]; then
    JAVA_HOME="$(find_jdk "$JDK_DIR")" || { echo "ERROR: --jdk $JDK_DIR is not JDK 21" >&2; exit 1; }
elif [[ -z "$JAVA_HOME" ]]; then
    for d in /Library/Java/JavaVirtualMachines/*.jdk/Contents/Home "$HOME/.jdks"/* /usr/lib/jvm/java-21-*; do
        [[ -d "$d" ]] && JAVA_HOME="$(find_jdk "$d")" && break
    done
fi
[[ -n "$JAVA_HOME" ]] || { echo "ERROR: JDK 21 not found (set JAVA_HOME or --jdk)" >&2; exit 1; }
echo "   jdk: $JAVA_HOME"

cd "$APP_DIR"
./gradlew --no-daemon app:patchGamaJars || { echo "ERROR: patchGamaJars failed" >&2; exit 1; }

echo "== rebuilding androidsensor extension =="
"$SCRIPT_DIR/build_extension.sh" gama.extension.androidsensor \
    || { echo "ERROR: extension rebuild failed" >&2; exit 1; }

echo "== assembling APK =="
./gradlew --no-daemon app:assembleDebug || { echo "ERROR: assembleDebug failed" >&2; exit 1; }

APK="$APP_DIR/app/build/outputs/apk/debug/app-debug.apk"
echo "== BUILD OK: $APK ($(du -h "$APK" | cut -f1))"

# --- 5. optional device smoke test ---------------------------------------------
if [[ "$DEVICE_TEST" -eq 1 ]]; then
    command -v adb >/dev/null 2>&1 || { echo "ERROR: adb not found (need Android platform-tools for --device)" >&2; exit 1; }
    adb wait-for-device
    echo "== installing APK =="
    adb install -r "$APK"
    MODEL="models/Toy Models/Android Sensors/AndroidDigitalTwinMap.gaml"
    echo "== launching $MODEL =="
    adb shell am force-stop com.gama.nativeapp
    adb logcat -c
    adb shell am start -n com.gama.nativeapp/.ExperimentActivity \
        --es jar_path "'$MODEL'" --ez from_library true
    sleep 20
    GAML_ERRORS="$(adb shell "logcat -d -s GamlErrors:E *:S" 2>/dev/null | grep -c 'GamlErrors' || true)"
    DRAWS="$(adb shell "logcat -d -s ANDROID_DISPLAY:I *:S" 2>/dev/null | grep -c 'onDraw' || true)"
    if [[ "$GAML_ERRORS" -gt 0 ]]; then
        echo "!! SMOKE TEST FAILED: $GAML_ERRORS GAML error line(s); $DRAWS frame(s)" >&2
        exit 1
    elif [[ "$DRAWS" -ge 2 ]]; then
        echo "== SMOKE TEST PASS: model compiled and rendered ($DRAWS frames, no GAML errors) =="
    else
        echo "?? SMOKE TEST INCONCLUSIVE: no render frames in logcat ($GAML_ERRORS GAML errors)" >&2
    fi
fi

echo
echo "== NEXT: review, then bump versionCode/versionName, commit, tag v..., push =="
echo "   (tag push triggers .github/workflows/auto-release.yml => APK release)"