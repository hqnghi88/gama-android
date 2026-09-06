#!/usr/bin/env bash
# build_plugin_from_jar.sh — repackage an existing engine-style GAMA extension jar
# (classes only, no nested deps) into an Android runtime plugin for this app.
#
# This is the "no source available" counterpart of build_plugin.sh: the extension
# ships already-compiled (e.g. app/libs/gama.extension.androidsensor.jar) and we
# only need to dex its classes.dex and stamp the Bundle-SymbolicName manifest.
#
# Usage:
#   ./build_plugin_from_jar.sh <bundle-symbolic-name> <version> <source.jar>
#
#   <bundle-symbolic-name>  e.g. gama.extension.androidsensor
#   <version>               e.g. 0.1.0
#   <source.jar>            the extension jar under plugins/<name>/
#
# Optional: a <source.jar> sibling '<name>/resources' directory is merged into
# the final jar root (models/, etc.), same convention as build_plugin.sh.
#
# Output: <source jar's dir>/out/plugin_<version>.jar  (ready to install in the app)
set -euo pipefail

BUNDLE="${1:?bundle symbolic name required}"
VERSION="${2:?version required}"
SRC_JAR="$(cd "$(dirname "${3:?source jar required}")" && pwd)/$(basename "$3")"

[[ -f "${SRC_JAR}" ]] || { echo "source jar not found: ${SRC_JAR}" >&2; exit 1; }
SRC="$(dirname "${SRC_JAR}")"

SDK="${ANDROID_HOME:-${HOME}/Library/Android/sdk}"
BT="$(ls -1 "${SDK}/build-tools" 2>/dev/null | sort -V | tail -1 || true)"
D8="${SDK}/build-tools/${BT}/d8"
ANDROID_JAR="${SDK}/platforms/android-$(ls -1 "${SDK}/platforms" 2>/dev/null | rg -o '[0-9]+' | sort -n | tail -1 || true)/android.jar"
MIN_API="${MIN_API:-26}"

[[ -f "${D8}" ]] || { echo "d8 not found under ${SDK}/build-tools" >&2; exit 1; }
[[ -f "${ANDROID_JAR}" ]] || { echo "android.jar not found under ${SDK}/platforms" >&2; exit 1; }

rm -rf "${SRC}/out"
mkdir -p "${SRC}/out/classes" "${SRC}/out/dex"

echo ">> extract .class files from ${SRC_JAR##*/}"
(cd "${SRC}/out/classes" && jar xf "${SRC_JAR}" 2>/dev/null \
    || unzip -q "${SRC_JAR}" -d "${SRC}/out/classes")
find "${SRC}/out/classes" -name '*.class' >/dev/null || { echo "no .class files found in ${SRC_JAR}" >&2; exit 1; }

echo ">> d8 (min-api ${MIN_API})"
CLASSES="$(cd "${SRC}/out" && find classes -name '*.class' | tr '\n' ' ')"
(cd "${SRC}/out" && ${D8} --min-api "${MIN_API}" --lib "${ANDROID_JAR}" --output dex ${CLASSES})

echo ">> assemble plugin jar"
MF="${SRC}/out/MANIFEST.MF"
{
  echo "Manifest-Version: 1.0"
  echo "Bundle-ManifestVersion: 2"
  echo "Bundle-SymbolicName: ${BUNDLE}"
  echo "Bundle-Version: ${VERSION}"
  echo ""
} > "${MF}"

PLUGIN_JAR="${SRC}/out/plugin_${VERSION}.jar"
if command -v jar >/dev/null; then
  jar cfm "${PLUGIN_JAR}" "${MF}" -C "${SRC}/out/dex" classes.dex
else
  python3 - "${PLUGIN_JAR}" "${MF}" "${SRC}/out/dex/classes.dex" <<'PY'
import sys, zipfile
out, mf, dex = sys.argv[1], sys.argv[2], sys.argv[3]
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    z.write(mf, 'META-INF/MANIFEST.MF')
    z.write(dex, 'classes.dex')
PY
fi

# Merge an optional resources/ dir (models/, plugins/ files, etc.) into the jar root
if [[ -d "${SRC}/resources" ]]; then
  echo ">> merging resources/ into plugin jar"
  python3 - "${PLUGIN_JAR}" "${SRC}/resources" <<'PY'
import sys, zipfile, os
jar, resdir = sys.argv[1], sys.argv[2]
root = resdir.rstrip('/') + '/'
tmp = jar + '.tmp'
with zipfile.ZipFile(jar, 'r') as zin, zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        zout.writestr(item, zin.read(item.filename))
    for dirpath, dirnames, filenames in os.walk(resdir):
        for f in filenames:
            absf = os.path.join(dirpath, f)
            zout.write(absf, absf[len(root):])
os.replace(tmp, jar)
PY
fi

echo ">> done: ${PLUGIN_JAR} (bundle ${BUNDLE} v${VERSION})"
ls -lh "${PLUGIN_JAR}"