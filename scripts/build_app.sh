#!/usr/bin/env bash
#
# build_app.sh — reproducible GAMA Native Android build on any machine.
#
# This script rebuilds the exact `app-debug.apk` that is published as a GitHub
# release, from a fresh clone of this repository, without any pre-installed
# project-specific state. It only needs an internet connection and a shell
# (bash on macOS / Linux / WSL). Everything else it provisions itself:
#
#   1. JDK 21           (reuses an installed JDK or downloads Temurin 21)
#   2. Android SDK      (reuses ANDROID_HOME or downloads cmdline-tools and
#                        installs platforms;android-34 + build-tools;34.0.0)
#   3. GAMA jars        (downloads the native-app-deps.tar.gz release asset;
#                        or reuses an existing native-app/app/libs)
#   4. Build            (extension jar + gradle assembleDebug)
#
# Usage:
#   scripts/build_app.sh [options]
#
# Options:
#   --repo DIR     Repository root (default: parent of this script)
#   --deps-tag TAG GitHub release tag to fetch native-app-deps.tar.gz from
#                  (default: "latest" — the newest release)
#   --deps-dir DIR Use a local directory containing the `libs` bundle instead
#                  of downloading it (DIR must contain libs/ and libs/pristine/)
#   --skip-deps    Skip fetching/verifying jars (assumes app/libs is complete)
#   --sdk DIR      Android SDK root to use instead of auto-detection
#   --jdk DIR      JDK 21 home to use instead of auto-detection
#   --keep-daemon  Keep the Gradle daemon (default: --no-daemon)
#   -h, --help     Show this help
#
# Output: native-app/app/build/outputs/apk/debug/app-debug.apk
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
APP_DIR="$REPO_DIR/native-app"
LIBS_DIR="$APP_DIR/app/libs"

DEPS_TAG="latest"
DEPS_DIR=""
SKIP_DEPS=0
SDK_DIR=""
JDK_DIR=""
GRADLE_ARGS=(--no-daemon)

usage() { sed -n '2,30p' "${BASH_SOURCE[0]}"; exit 0; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo) REPO_DIR="$2"; APP_DIR="$REPO_DIR/native-app"; LIBS_DIR="$APP_DIR/app/libs"; shift 2;;
    --deps-tag) DEPS_TAG="$2"; shift 2;;
    --deps-dir) DEPS_DIR="$2"; shift 2;;
    --skip-deps) SKIP_DEPS=1; shift;;
    --sdk) SDK_DIR="$2"; shift 2;;
    --jdk) JDK_DIR="$2"; shift 2;;
    --keep-daemon) GRADLE_ARGS=(); shift;;
    -h|--help) usage;;
    *) echo "unknown option: $1" >&2; usage >&2; exit 1;;
  esac
done

if [[ ! -d "$APP_DIR" || ! -d "$APP_DIR/app" ]]; then
  echo "ERROR: no Android project at $APP_DIR" >&2
  echo "  Clone the repo first: git clone https://github.com/hqnghi88/gama-android.git" >&2
  exit 1
fi

TOOLS_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/gama-android-tools"
mkdir -p "$TOOLS_DIR"

echo "== GAMA Native Android build =="
echo "   repo:  $REPO_DIR"

# --------------------------------------------------------------------------
# 1. JDK 21
# --------------------------------------------------------------------------
find_jdk() {
  local d="$1"
  [[ -x "$d/bin/java" ]] && "$d/bin/java" -version 2>&1 | grep -q '"21' && echo "$d" && return 0
  return 1
}

JDK21=""
if [[ -n "$JDK_DIR" ]]; then
  JDK21="$(find_jdk "$JDK_DIR")" || { echo "ERROR: --jdk $JDK_DIR is not a JDK 21" >&2; exit 1; }
else
  # JAVA_HOME, then common install locations
  if [[ -n "${JAVA_HOME:-}" ]]; then JDK21="$(find_jdk "$JAVA_HOME")" || true; fi
  if [[ -z "$JDK21" ]]; then
    for d in /Library/Java/JavaVirtualMachines/*.jdk/Contents/Home \
             "$HOME/.jdks"/* "$HOME/.sdkman/candidates/java"/* \
             /usr/lib/jvm/java-21-* /usr/lib/jvm/temurin-21* \
             "${PROGRAMFILES:-}/Eclipse Adoptium/jdk-21"*/ \
             "${LOCALAPPDATA:-}/Programs/Eclipse Adoptium/jdk-21"*/; do
      [[ -d "$d" ]] || continue
      JDK21="$(find_jdk "$d")" && break
    done
  fi
fi

if [[ -z "$JDK21" ]]; then
  echo "== Downloading Temurin JDK 21 =="
  OS="linux"; [[ "$(uname -s)" == "Darwin" ]] && OS="mac"
  ARCH="x64"; [[ "$(uname -m)" =~ arm64|aarch64 ]] && ARCH="arm64"
  JDK21="$TOOLS_DIR/jdk21"
  if [[ ! -x "$JDK21/bin/java" ]]; then
    url="https://api.adoptium.net/v3/binary/latest/21/ga/$OS/$ARCH/jdk/hotspot/normal/eclipse"
    tmp="$(mktemp -d)"
    echo "   downloading $url"
    curl -fsSL "$url" -o "$tmp/jdk.tar.gz"
    mkdir -p "$TOOLS_DIR/jdk21-tmp"
    tar -xzf "$tmp/jdk.tar.gz" -C "$TOOLS_DIR/jdk21-tmp"
    rm -rf "$JDK21"
    mv "$TOOLS_DIR"/jdk21-tmp/jdk-21*/ "$JDK21" 2>/dev/null \
      || mv "$TOOLS_DIR"/jdk21-tmp/*/ "$JDK21"
    rm -rf "$TOOLS_DIR/jdk21-tmp" "$tmp"
  fi
fi

export JAVA_HOME="$JDK21"
echo "   jdk:    $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"

# --------------------------------------------------------------------------
# 2. Android SDK
# --------------------------------------------------------------------------
sdk_has() { [[ -d "$SDK_DIR/$1" ]] && return 0 || return 1; }

if [[ -z "$SDK_DIR" ]]; then
  for d in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" \
           "$HOME/Android/Sdk" "${LOCALAPPDATA:-}/Android/Sdk" "/usr/local/share/android-sdk"; do
    [[ -n "$d" && -d "$d" ]] && SDK_DIR="$d" && break
  done
fi

if [[ -z "$SDK_DIR" ]] || ! sdk_has "platforms/android-34" || ! sdk_has "build-tools/34.0.0"; then
  if [[ -z "$SDK_DIR" ]]; then SDK_DIR="$TOOLS_DIR/android-sdk"; fi
  echo "== Provisioning Android SDK at $SDK_DIR =="
  mkdir -p "$SDK_DIR/cmdline-tools"
  CT="$SDK_DIR/cmdline-tools/latest"
  if [[ ! -x "$CT/bin/sdkmanager" ]]; then
    OS="linux"; [[ "$(uname -s)" == "Darwin" ]] && OS="macosx"
    url="https://dl.google.com/android/repository/commandlinetools-${OS}-11076708_latest.zip"
    tmp="$(mktemp -d)"
    echo "   downloading $url"
    curl -fsSL "$url" -o "$tmp/ct.zip"
    unzip -q "$tmp/ct.zip" -d "$tmp"
    mkdir -p "$CT"
    mv "$tmp/cmdline-tools/bin" "$tmp/cmdline-tools/lib" "$CT/"
    rm -rf "$tmp"
  fi
  echo "   installing platform-tools, platforms;android-34, build-tools;34.0.0"
  yes 2>/dev/null | "$CT/bin/sdkmanager" --sdk_root="$SDK_DIR" \
    "platform-tools" "platforms;android-34" "build-tools;34.0.0" >/dev/null
fi

[[ -d "$SDK_DIR/platform-tools" ]] || SDK_DIR="$SDK_DIR"  # platform-tools optional for build
echo "   sdk:    $SDK_DIR"
echo "sdk.dir=$SDK_DIR" > "$APP_DIR/local.properties"

# --------------------------------------------------------------------------
# 3. GAMA jars (app/libs) — either provided, fetched, or assumed complete
# --------------------------------------------------------------------------
restore_from_dir() {
  # Bundle layout: libs/{pristine/*.jar, *.jar} and optionally assets/*.
  local src="$1"
  local LB="$src/libs"
  [[ -d "$LB" ]] || LB="$src"
  mkdir -p "$LIBS_DIR"
  if [[ -d "$LB/pristine" ]]; then
    cp -R "$LB"/pristine "$LIBS_DIR/" 2>/dev/null || true
    # pristine gama jars are also copied to the top level so build_extension.sh
    # (which compiles before Gradle's pristine restore) can resolve the GAMA
    # API/annotations. Gradle's patchGamaJars restore is then idempotent.
    for j in "$LIBS_DIR"/pristine/*.jar; do
      [[ -e "$j" ]] && cp "$j" "$LIBS_DIR/"
    done
  fi
  for j in "$LB"/*.jar; do
    [[ -e "$j" ]] && cp "$j" "$LIBS_DIR/"
  done
  # pristine copies must exist for patchGamaJars to restore pristine state
  local n; n="$(ls "$LIBS_DIR/pristine"/*.jar 2>/dev/null | wc -l | tr -d ' ')"
  if [[ "$n" -lt 30 ]]; then
    echo "ERROR: deps bundle at $src is incomplete (pristine jars: $n < 30)" >&2
    exit 1
  fi
  if [[ -d "$src/assets" ]]; then
    mkdir -p "$APP_DIR/app/src/main/assets"
    cp -R "$src"/assets/. "$APP_DIR/app/src/main/assets/" 2>/dev/null || true
    echo "   assets: restored $src/assets/*"
  fi
  echo "   jars:   restored $(ls "$LIBS_DIR"/*.jar | wc -l | tr -d ' ') jars from $src"
}

if [[ "$SKIP_DEPS" -eq 1 ]]; then
  echo "   jars:   using existing $LIBS_DIR (--skip-deps)"
elif [[ -n "$DEPS_DIR" ]]; then
  restore_from_dir "$DEPS_DIR"
else
  PRISTINE_COUNT="$(ls "$LIBS_DIR/pristine"/*.jar 2>/dev/null | wc -l | tr -d ' ')"
  if [[ "$PRISTINE_COUNT" -ge 30 ]]; then
    echo "   jars:   already present at $LIBS_DIR ($PRISTINE_COUNT pristine)"
  else
    echo "== Downloading GAMA jars (native-app-deps.tar.gz @ $DEPS_TAG) =="
    OUT="$TOOLS_DIR/native-app-deps.tar.gz"
    if [[ "$DEPS_TAG" == "latest" ]]; then
      url="https://github.com/hqnghi88/gama-android/releases/latest/download/native-app-deps.tar.gz"
    else
      url="https://github.com/hqnghi88/gama-android/releases/download/$DEPS_TAG/native-app-deps.tar.gz"
    fi
    echo "   downloading $url"
    curl -fL --retry 3 "$url" -o "$OUT"
    mkdir -p "$TOOLS_DIR/deps-x"
    tar -xzf "$OUT" -C "$TOOLS_DIR/deps-x"
    restore_from_dir "$TOOLS_DIR/deps-x"
    rm -rf "$TOOLS_DIR/deps-x"
  fi
fi

# --------------------------------------------------------------------------
# 4. Build
# --------------------------------------------------------------------------
# First patch the GAMA jars so the extension compiles against the same
# (downgraded/patched) jars the app itself is built from. On the developer
# machine the top-level jars are already patched from a previous build; a
# fresh machine restores raw jars, so the patch must run first.
echo "== Running Gradle patchGamaJars =="
cd "$APP_DIR"
./gradlew "${GRADLE_ARGS[@]}" app:patchGamaJars || {
  echo "ERROR: app:patchGamaJars failed (see output above)" >&2
  exit 1
}

EXT_BIN="$SCRIPT_DIR/build_extension.sh"
if [[ -x "$EXT_BIN" ]]; then
  echo "== Building GAMA extension jar =="
  "$EXT_BIN" gama.extension.androidsensor || echo "   (extension build skipped/failed; using existing jar)"
else
  echo "== NOTE: $EXT_BIN not found; extension jar not rebuilt =="
fi

echo "== Running Gradle assembleDebug =="
./gradlew "${GRADLE_ARGS[@]}" app:assembleDebug

APK="$APP_DIR/app/build/outputs/apk/debug/app-debug.apk"
if [[ -f "$APK" ]]; then
  echo
  echo "== BUILD OK =="
  echo "   $APK ($(du -h "$APK" | cut -f1))"
else
  echo "ERROR: build completed but $APK not found" >&2
  exit 1
fi
