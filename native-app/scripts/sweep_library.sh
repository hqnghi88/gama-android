#!/bin/zsh
# Full library sweep: run every (jar_path, experiment) pair from the discovery TSV.
# Usage: scripts/sweep_library.sh [--wait SECONDS] [--tsv FILE]
# Sourced helpers: analyze() from regression.sh is inlined here (must match).

set -u

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PKG=com.gama.nativeapp
ACT=com.gama.nativeapp/.ExperimentActivity
WAIT=40
TSV=/var/folders/4c/wyn32frn4wd0nwmc9t5csq480000gn/T/opencode/lib_discovery.tsv
OUT=/var/folders/4c/wyn32frn4wd0nwmc9t5csq480000gn/T/opencode/sweep
mkdir -p "$OUT"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --wait) WAIT="$2"; shift 2;;
    --tsv) TSV="$2"; shift 2;;
    *) echo "unknown arg: $1"; exit 1;;
  esac
done

analyze() {
  local logf="$1" pngf="$2"
  local res="NOSTART" score=0
  grep -q "Bootstrap error" "$logf" && res="BOOT_FAIL" && score=1
  grep -qE "File compilation error|Compilation error|Compilation failed|ERROR: .*Exception" "$logf" && res="COMPILE_FAIL" && score=1
  grep -q "FATAL EXCEPTION" "$logf" && res="CRASH" && score=1
  if [[ "$score" == "0" ]]; then
    grep -q "Compiled:" "$logf" && score=2
    grep -q "Experiment started" "$logf" && score=3
    if grep -qE "invalidating AndroidDisplaySurface|drewShapes|ANDROID_3D" "$logf"; then
      score=4
    fi
  fi
  if [[ "$score" == "0" && "$res" == "NOSTART" ]]; then
    grep -q "GAMA engine initialized" "$logf" && res="BOOT_FAIL"
  fi
  if [[ "$score" -ge 2 ]]; then
    local content; content=$(python3 -c "
from PIL import Image
im = Image.open('$pngf').convert('RGB')
w,h = im.size
px = im.load()
n=0
for y in range(0, h, 4):
    for x in range(0, w, 4):
        r,g,b = px[x,y]
        if not (r>245 and g>245 and b>245) and not (r<20 and g<20 and b<20):
            n+=1
print(n)
" 2>/dev/null)
    if [[ -n "$content" && "$content" -gt 20 ]]; then
      res="PASS"
    else
      res="RENDER_?"
    fi
  fi
  echo "$res"
}

am_start() {
  local cmd="am start -n '$ACT'"
  local arg
  for arg in "$@"; do
    cmd+=" '$arg'"
  done
  "$ADB" shell "$cmd" >/dev/null 2>&1 < /dev/null &
  local pid=$!
  ( sleep 25; kill -9 $pid 2>/dev/null ) < /dev/null &
  local killer=$!
  wait $pid 2>/dev/null
  kill $killer 2>/dev/null
}

: > "$OUT/sweep.tsv"
echo "sweep started $(date +%H:%M:%S)"
while IFS=$'\t' read -r jarpath exp <&3; do
  [[ -z "$jarpath" || -z "$exp" ]] && continue
  slug="$(echo "$jarpath" | sed 's/^models\///; s/[^A-Za-z0-9]/_/g')_${exp}"
  logf="$OUT/${slug}.log"
  pngf="$OUT/${slug}.png"
  "$ADB" shell am force-stop "$PKG" >/dev/null 2>&1 || true
  sleep 2
  "$ADB" logcat -c 2>/dev/null || true
  am_start --es model_name "${jarpath:t}" --es jar_path "$jarpath" --ez from_library true --es experiment_name "$exp"
  sleep "$WAIT"
  "$ADB" logcat -d > "$logf" 2>/dev/null || true
  "$ADB" exec-out screencap -p > "$pngf" 2>/dev/null || true
  res=$(analyze "$logf" "$pngf")
  echo -e "$res\t$jarpath\t$exp" >> "$OUT/sweep.tsv"
  echo "[$(date +%H:%M:%S)] $res\t$jarpath\t$exp"
done 3< "$TSV"
echo "sweep done $(date +%H:%M:%S)"
