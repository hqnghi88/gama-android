#!/bin/zsh
# Automated model regression harness for com.gama.nativeapp on a connected device.
# Usage: scripts/regression.sh [--wait SECONDS] [--model "Name.gaml"] [--out DIR] [--lib]
# --lib: also run library models from gama.library jar (jar_path extras).
# Each model: launch ExperimentActivity, wait, extract pass/fail from logcat + screenshot.

set -u

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PKG=com.gama.nativeapp
ACT=com.gama.nativeapp/.ExperimentActivity
MODELS_DIR="/data/user/0/$PKG/files/models"
LOCAL_MODELS="/Users/hqnghi/git/gama-android/native-app/app/src/main/assets/models"
LIB_JAR="/data/user/0/$PKG/cache/gama.library.jar"

WAIT=30
OUT=/var/folders/4c/wyn32frn4wd0nwmc9t5csq480000gn/T/opencode/regression
ONLY_MODEL=""
RUN_LIB=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --wait) WAIT="$2"; shift 2;;
    --model) ONLY_MODEL="$2"; shift 2;;
    --out) OUT="$2"; shift 2;;
    --lib) RUN_LIB=1; shift;;
    *) echo "unknown arg: $1"; exit 1;;
  esac
done

mkdir -p "$OUT"
log() { echo "[harness] $*"; }

am_start() {
  # adb shell joins args with spaces, so single-quote each arg for the
  # device shell (handles spaces in model names / jar paths).
  local cmd="am start -n '$ACT'"
  local arg
  for arg in "$@"; do
    cmd+=" '$arg'"
  done
  # Fire-and-forget with a hard 20s guard (adb am start can hang when the
  # device is busy/flooded).
  "$ADB" shell "$cmd" >/dev/null 2>&1 &
  local pid=$!
  ( sleep 20; kill -9 $pid 2>/dev/null ) &
  local killer=$!
  wait $pid 2>/dev/null
  kill $killer 2>/dev/null
}

push_models() {
  log "pushing models to device"
  (cd "$LOCAL_MODELS" && for f in *.gaml; do
    "$ADB" push "$f" /data/local/tmp/m.gaml >/dev/null 2>&1
    "$ADB" shell "cp /data/local/tmp/m.gaml '$MODELS_DIR/$f'" >/dev/null 2>&1
  done)
  "$ADB" shell "chown u0_a234:u0_a234 $MODELS_DIR/*.gaml; chcon u:object_r:app_data_file:s0:c234,c256,c512,c768 $MODELS_DIR/*.gaml 2>/dev/null; true"
  "$ADB" shell rm -f /data/local/tmp/m.gaml
}

# Returns PASS / COMPILE_FAIL / BOOT_FAIL / CRASH / NOSTART / RENDER_?
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
    # nothing compiled -> look at whether the activity even got far
    grep -q "GAMA engine initialized" "$logf" && res="BOOT_FAIL"
  fi
  if [[ "$score" -ge 2 ]]; then
    # verify actual pixels drawn in the center region of the screenshot
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

run_one() {
  local model="$1" exp="$2" ext=("${@:3}")
  local slug="${model// /_}"
  local logf="$OUT/${slug}.log"
  local pngf="$OUT/${slug}.png"
  log "=== $model (experiment: $exp) ==="
  "$ADB" shell am force-stop "$PKG" >/dev/null 2>&1 || true
  sleep 2
  "$ADB" logcat -c 2>/dev/null || true
  am_start --es model_name "$model" --es experiment_name "$exp" "${ext[@]}"
  sleep "$WAIT"
  "$ADB" logcat -d > "$logf" 2>/dev/null || true
  "$ADB" exec-out screencap -p > "$pngf" 2>/dev/null || true
  local res; res=$(analyze "$logf" "$pngf")
  echo "  => $res"
  echo -e "$res\t$model\t$exp" >> "$OUT/results.tsv"
}

push_models
rm -f "$OUT/results.tsv"
log "output dir: $OUT"

declare -A EXP=(
  "SimpleTest.gaml" "test_experiment"
  "Life.gaml" "Game of Life"
  "Bubble Sort 3D.gaml" "Display"
  "Traffic and Pollution.gaml" "traffic"
  "AndroidSensorTest.gaml" "sensor_test"
  "AndroidSensorLab.gaml" "sensor_lab"
)

if [[ -n "$ONLY_MODEL" ]]; then
  run_one "$ONLY_MODEL" "${EXP[$ONLY_MODEL]:-Display}" --es file_path "$MODELS_DIR/$ONLY_MODEL"
else
  for model in "${(@k)EXP}"; do
    run_one "$model" "${EXP[$model]}" --es file_path "$MODELS_DIR/$model"
  done
fi

if [[ "$RUN_LIB" == "1" ]]; then
  log "=== library models ==="
  declare -A LIBEXP=(
    "models/Toy Models/Evacuation/models/City Escape.gaml" "City Escape"
    "models/Toy Models/Evacuation/models/Evacuation Phuc Xa.gaml" "main"
    "models/Toy Models/Evacuation/models/Continuous Move.gaml" "main"
    "models/Toy Models/Evacuation/models/Move on Grid.gaml" "Run"
    "models/Toy Models/Evacuation/models/Goto on Grid.gaml" "evacuationgoto"
    "models/Toy Models/Traffic/models/Traffic and Pollution.gaml" "traffic"
    "models/Toy Models/Traffic/models/LWR Traffic Flow Model.gaml" "TraficGroup"
    "models/Toy Models/Traffic/models/Netlogo - Traffic model - 2 roads.gaml" "NetlogoTrafficmodel"
    "models/Toy Models/Flood Simulation/models/Hydrological Model.gaml" "Run"
    "models/Toy Models/Comodels/Flood and Evacuation/Flood Evacuation Comodel.gaml" "simple"
    "models/Toy Models/Comodels/Urban and Traffic/Urbanization And Traffic Comodel.gaml" "main"
  )
  for libpath in "${(@k)LIBEXP}"; do
    fname="${libpath:t}"
    run_one "$fname" "${LIBEXP[$libpath]}" \
      --es jar_path "$libpath" \
      --ez from_library true
  done
fi
log "DONE. results:"
column -t -s $'\t' "$OUT/results.tsv" 2>/dev/null || cat "$OUT/results.tsv"
