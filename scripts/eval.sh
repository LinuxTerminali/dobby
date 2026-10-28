#!/usr/bin/env bash
# Run the golden eval on a connected phone and pull results.
#   scripts/eval.sh [model-id]            (BACKEND=cpu TOOLS=native scripts/eval.sh gemma4-e4b to override)
set -euo pipefail
MODEL=${1:-}
PKG=app.dobby
DIR=/sdcard/Android/data/$PKG/files/eval
adb shell rm -f $DIR/latest.txt
if [ -n "$MODEL" ]; then
  adb shell am broadcast -a $PKG.EVAL -n $PKG/.eval.EvalReceiver --es model "$MODEL" ${BACKEND:+--es backend $BACKEND} ${TOOLS:+--es tools $TOOLS} >/dev/null
else
  adb shell am broadcast -a $PKG.EVAL -n $PKG/.eval.EvalReceiver >/dev/null
fi
echo "eval started; waiting…"
for i in $(seq 1 180); do
  if adb shell "[ -f $DIR/latest.txt ]" 2>/dev/null; then break; fi
  sleep 5
done
mkdir -p eval-results
adb pull -q $DIR eval-results/ 2>/dev/null || true
echo; cat eval-results/eval/latest.txt 2>/dev/null || echo "no result (timed out or app not installed)"
echo; echo "files: eval-results/eval/"
