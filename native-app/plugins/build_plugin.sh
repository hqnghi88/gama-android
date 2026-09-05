#!/usr/bin/env bash
# build_plugin.sh — package a GAMA extension as an Android runtime plugin.
#
# A plugin is a jar that carries classes.dex (already dexed) plus a manifest declaring
# Bundle-SymbolicName. Drop it into the app's plugins folder (or use the in-app
# "Install extension" button) and it registers its gaml.additions.<short>.GamlAdditions
# on next start, exactly like a build-time bundle.
#
# Usage:
#   ./build_plugin.sh <bundle-symbolic-name> <version> <src-dir> [extra-jar-or-dir...]
#
#   <bundle-symbolic-name>  e.g. gama.extension.demo
#   <version>               e.g. 0.1.0
#   <src-dir>               a directory tree of .java files compiled against the plugin
#                           classpath (gama.api + gama.annotations + gama.core + gaml.compiler)
#   extra...                extra jars or dirs appended to the compile classpath
#
# Optional: <src-dir>/resources is merged into the final jar root (e.g. models/, plugins/).
#
# Output: <src-dir>/out/plugin_<version>.jar  (ready to install in the app)
set -euo pipefail

BUNDLE="${1:?bundle symbolic name required}"
VERSION="${2:?version required}"
SRC="${3:?source dir required}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_LIBS="${ROOT}/../app/libs"
SDK="${ANDROID_HOME:-${HOME}/Library/Android/sdk}"
PLATFORMS="$(ls -1 "${SDK}/platforms" 2>/dev/null | rg -o '[0-9]+' | sort -n | tail -1 || true)"
BT="$(ls -1 "${SDK}/build-tools" 2>/dev/null | sort -V | tail -1 || true)"
D8="${SDK}/build-tools/${BT}/d8"
ANDROID_JAR="${SDK}/platforms/android-${PLATFORMS}/android.jar"
MIN_API="${MIN_API:-26}"

[[ -f "${D8}" ]] || { echo "d8 not found under ${SDK}/build-tools" >&2; exit 1; }
[[ -f "${ANDROID_JAR}" ]] || { echo "android.jar not found under ${SDK}/platforms" >&2; exit 1; }
command -v javac >/dev/null || { echo "javac required (JDK 17+)" >&2; exit 1; }

SOURCES="$(find "${SRC}/src" -name '*.java' 2>/dev/null || true)"
[[ -n "${SOURCES}" ]] || { echo "no .java sources under ${SRC}/src" >&2; exit 1; }

rm -rf "${SRC}/out"
mkdir -p "${SRC}/out/classes" "${SRC}/out/dex"

CP=()
for pat in gama.api gama.annotations gama.core gaml.compiler; do
  for j in "${APP_LIBS}"/${pat}_*.jar; do CP+=("$j"); done
done
for extra in "${@:4}"; do CP+=("$extra"); done
CP_STR=$(IFS=':'; printf '%s' "${CP[*]}")

echo ">> javac (release 17) against engine jars"
# shellcheck disable=SC2086
javac --release 17 -Xlint:-options -cp "${CP_STR}" -d "${SRC}/out/classes" ${SOURCES}

echo ">> d8 (min-api ${MIN_API})"
# Engine classes referenced by the extension are intentionally NOT on this classpath:
# they are already dexed inside the app, and DexClassLoader resolves them via the parent.
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