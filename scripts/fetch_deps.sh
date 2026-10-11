#!/usr/bin/env bash
# Downloads third-party models, libraries and the compiler from their official sources,
# verifies SHA-256, and lays them out where build.sh expects them.
#
# Nothing fetched here is committed to the repository.
set -euo pipefail

cd "$(dirname "$0")/.."
mkdir -p assets libs jni/arm64-v8a jni/armeabi-v7a tools .cache

fetch() {  # url sha256 dest
    local url="$1" sum="$2" dest="$3"
    if [ -f "$dest" ] && echo "$sum  $dest" | sha256sum -c --status; then
        echo "  ok      $dest"
        return
    fi
    echo "  fetch   $url"
    curl -fsSL --retry 5 --retry-delay 5 --retry-all-errors -o "$dest.part" "$url"
    if ! echo "$sum  $dest.part" | sha256sum -c --status; then
        echo "  SHA-256 mismatch for $url" >&2
        rm -f "$dest.part"
        exit 1
    fi
    mv "$dest.part" "$dest"
}

echo "[models]"
# Google MediaPipe EfficientDet-Lite0, int8, COCO
fetch "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/1/efficientdet_lite0.tflite" \
      0720bf247bd76e6594ea28fa9c6f7c5242be774818997dbbeffc4da460c723bb \
      assets/efficientdet_lite0.tflite
# Google MediaPipe YAMNet audio classifier, float32
fetch "https://storage.googleapis.com/mediapipe-models/audio_classifier/yamnet/float32/1/yamnet.tflite" \
      4d8b4a53282dc83ef04e3e7dbc4fbc98082e34e44ed798e16c3a0cdd4c584faf \
      assets/yamnet.tflite

# Label lists are embedded in each model's metadata (the .tflite is also a zip).
unzip -p assets/efficientdet_lite0.tflite labels.txt > assets/coco_labels.txt 2>/dev/null || true
unzip -p assets/yamnet.tflite yamnet_label_list.txt > assets/yamnet_labels.txt 2>/dev/null || true
[ -s assets/coco_labels.txt ]   || { echo "could not extract COCO labels" >&2; exit 1; }
[ -s assets/yamnet_labels.txt ] || { echo "could not extract YAMNet labels" >&2; exit 1; }
echo "  ok      assets/coco_labels.txt, assets/yamnet_labels.txt"

echo "[TensorFlow Lite 2.16.1]"
fetch "https://repo1.maven.org/maven2/org/tensorflow/tensorflow-lite/2.16.1/tensorflow-lite-2.16.1.aar" \
      34b065817e294e7dd3569504e8b9454938ceae453b6a459da54d5f6e45d3c94e \
      .cache/tensorflow-lite-2.16.1.aar
fetch "https://repo1.maven.org/maven2/org/tensorflow/tensorflow-lite-api/2.16.1/tensorflow-lite-api-2.16.1.aar" \
      885b95bc9e47a4aa5cf8e71523aed9b05e397cfef1ea09773f13fea8b649fcee \
      .cache/tensorflow-lite-api-2.16.1.aar
unzip -p .cache/tensorflow-lite-2.16.1.aar classes.jar > libs/tflite-impl.jar
unzip -p .cache/tensorflow-lite-api-2.16.1.aar classes.jar > libs/tflite-api.jar
for abi in arm64-v8a armeabi-v7a; do
    unzip -p .cache/tensorflow-lite-2.16.1.aar "jni/$abi/libtensorflowlite_jni.so" \
        > "jni/$abi/libtensorflowlite_jni.so"
done
echo "  ok      libs/, jni/"

echo "[Eclipse compiler]"
fetch "https://repo1.maven.org/maven2/org/eclipse/jdt/ecj/3.33.0/ecj-3.33.0.jar" \
      f7686c4960cf70c2ebc5c500a73a8cfc04541b730c18f1c5c21329889b137f45 \
      tools/ecj.jar

echo "Done."
