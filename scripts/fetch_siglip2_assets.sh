#!/usr/bin/env bash
set -euo pipefail

# Downloads SigLIP2 (ONNX) + tokenizer assets into res/raw.
# Source: onnx-community/siglip2-base-patch16-224-ONNX

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RAW_DIR="$ROOT_DIR/app/src/main/res/raw"

TOKENIZER_URL="https://huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX/resolve/main/tokenizer.model"

TOKENIZER_OUT="$RAW_DIR/siglip2_tokenizer.model"

mkdir -p "$RAW_DIR"

MODE="${1:-float}"
if [[ "$MODE" != "float" && "$MODE" != "int8" && "$MODE" != "both" ]]; then
  echo "Usage: $0 [float|int8|both]"
  echo "  float: downloads CPU-friendly float models (default)"
  echo "  int8 : downloads INT8 models (often requires QNN execution provider)"
  echo "  both : downloads both float and INT8 models"
  exit 2
fi

if [[ "$MODE" == "float" || "$MODE" == "both" ]]; then
  VISION_URL="https://huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX/resolve/main/onnx/vision_model.onnx"
  TEXT_URL="https://huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX/resolve/main/onnx/text_model.onnx"
  VISION_OUT="$RAW_DIR/siglip2_vision_model.onnx"
  TEXT_OUT="$RAW_DIR/siglip2_text_model.onnx"

  echo "Downloading vision model (float) -> $VISION_OUT"
  curl -L --fail --retry 3 --retry-delay 2 -o "$VISION_OUT.tmp" "$VISION_URL"

  echo "Downloading text model (float) -> $TEXT_OUT"
  curl -L --fail --retry 3 --retry-delay 2 -o "$TEXT_OUT.tmp" "$TEXT_URL"

  mv -f "$VISION_OUT.tmp" "$VISION_OUT"
  mv -f "$TEXT_OUT.tmp" "$TEXT_OUT"
fi

if [[ "$MODE" == "int8" || "$MODE" == "both" ]]; then
  VISION_URL="https://huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX/resolve/main/onnx/vision_model_int8.onnx"
  TEXT_URL="https://huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX/resolve/main/onnx/text_model_int8.onnx"
  VISION_OUT="$RAW_DIR/siglip2_vision_model_int8.onnx"
  TEXT_OUT="$RAW_DIR/siglip2_text_model_int8.onnx"

  echo "Downloading vision model (int8) -> $VISION_OUT"
  curl -L --fail --retry 3 --retry-delay 2 -o "$VISION_OUT.tmp" "$VISION_URL"

  echo "Downloading text model (int8) -> $TEXT_OUT"
  curl -L --fail --retry 3 --retry-delay 2 -o "$TEXT_OUT.tmp" "$TEXT_URL"

  mv -f "$VISION_OUT.tmp" "$VISION_OUT"
  mv -f "$TEXT_OUT.tmp" "$TEXT_OUT"
fi

echo "Downloading tokenizer -> $TOKENIZER_OUT"
curl -L --fail --retry 3 --retry-delay 2 -o "$TOKENIZER_OUT.tmp" "$TOKENIZER_URL"

mv -f "$TOKENIZER_OUT.tmp" "$TOKENIZER_OUT"

echo "Done."
ls -lh "$RAW_DIR"/siglip2_* "$TOKENIZER_OUT" 2>/dev/null || true
