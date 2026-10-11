# DY Safety Walk

An Android app for people who walk while looking at their phone. It overlays the rear camera on top of whatever app is open, semi-transparent, so the street ahead stays visible through the screen. On-device models watch for vehicles and listen for hazardous sounds, and a warning appears in the middle of the screen when something is close.

> **This is not a safety device.** It cannot see below your feet, beside you or behind you, and the detectors miss things. Do not rely on it at crossings, on roadways, or anywhere a mistake could hurt you. Look up.

## What it does

- **See-through overlay.** The rear camera is drawn into a system overlay that passes every touch through, so the app underneath works normally.
- **Screen-aware rendering.** The screen is sampled at 1/10 scale five times a second. Bright regions get locally dimmed, and the camera's tone and opacity adapt, so the street stays visible over both white and dark apps.
- **Vehicle detection.** EfficientDet-Lite0 checks the camera every 0.4 s for cars, trucks, buses, bicycles and motorcycles/scooters. Larger on screen means closer, which raises the risk score.
- **Sound detection.** YAMNet classifies the microphone every 0.5 s for car horns, sirens, motorcycles, bicycle bells, screeching tires and heavy traffic. This covers what the camera cannot see. A phone's microphones cannot tell front from back, so it reports *what* the sound is, not *where*.
- **Warning.** A red triangle with an exclamation mark and a short message (`Bicycle nearby — stay alert`, `Car horn heard — stay alert`), plus a short vibration for high-risk events.

Every setting (opacity, contrast, dimming, sensitivity, warning size, each detector on or off) lives in the app's settings screen.

## Privacy

Everything runs on the device. The app does **not** request the `INTERNET` permission, so camera frames, audio and screen captures cannot leave the phone. Screen captures are reduced to a 4x8 grid of brightness values and discarded immediately; audio is held in a one-second rolling buffer and never written to storage.

| Permission | Why |
|---|---|
| `CAMERA` | The see-through view and vehicle detection |
| `SYSTEM_ALERT_WINDOW` | Drawing over other apps |
| `RECORD_AUDIO` | Sound detection (optional) |
| Screen capture prompt | Screen-aware rendering (optional) |
| `FOREGROUND_SERVICE_*`, `POST_NOTIFICATIONS` | Keeping the overlay alive with a visible stop button |
| `VIBRATE` | Warning vibration |

## Building

No Gradle. You need JDK 11+, `curl`, `unzip`, and the Android SDK with `platforms;android-34` and `build-tools;34.0.0`.

```sh
chmod +x build.sh scripts/fetch_deps.sh       # make the checked-out scripts executable
./scripts/fetch_deps.sh                     # models, TensorFlow Lite, compiler (SHA-256 verified)
ANDROID_HOME=/path/to/android-sdk ./build.sh
```

That produces `DYSafetyWalk.apk`, signed with a throwaway debug key for local testing. To sign a release with your own key:

```sh
KEYSTORE=/path/to/release.keystore KEY_ALIAS=myalias KEYSTORE_PASS=... ANDROID_HOME=... ./build.sh
```

Keep your release keystore out of the repository. `.gitignore` already excludes `*.keystore` and `*.jks`.

## How it works

```
src/com/dysafety/walk/
  MainActivity.java     Settings screen, permissions, start/stop
  OverlayService.java   Foreground service: camera, overlay windows, detectors, warning logic
  Detector.java         EfficientDet-Lite0 inference, anchor decoding, NMS
  SoundDetector.java    Microphone capture and YAMNet inference
  ScreenAnalyzer.java   MediaProjection brightness sampler
  ScrimView.java        Per-region background dimming layer
  WarnView.java         Warning sign renderer
  ClipCrosswalk.java    Optional crosswalk scorer (see below)
  Prefs.java            Settings keys and defaults
```

Notes for anyone touching the detectors:

- **EfficientDet-Lite0 has no built-in NMS.** It outputs 19,206 raw anchors x 90 class scores and 19,206 box deltas. `Detector.java` generates the anchors (levels 3-7, 3 octaves, aspect ratios 1.0 / 2.0 / 0.5, anchor scale 3.0), decodes boxes and runs per-class NMS.
- **Its scores already have sigmoid applied.** Applying sigmoid again flattens every score to about 0.50. The decoder was checked against reference images before porting to Java.
- **YAMNet thresholds** were set against ESC-50 clips: car horn 0.74-1.00, siren 0.67-0.74, engine 0.67-0.74, and no false positives on church bells or rain. "Traffic noise" uses a much higher threshold (0.72) because it is present on most streets.
- **The overlay window uses `FLAG_SECURE`.** Without it the screen sampler would capture the app's own overlay and feed back into itself.

## Crosswalk detection (optional, not included)

The detector's COCO classes have no crosswalk, so `ClipCrosswalk.java` scores scenes with a MobileCLIP-S2 image encoder against "crosswalk" and "zebra crossing" text embeddings. On a 19-image test set crosswalks scored 0.249-0.326 and everything else 0.038-0.197, giving a default threshold of 0.235.

It is off and hidden in this build because the model adds about 138 MB and is not redistributed here. To enable it, supply:

1. `assets/mobileclip_s2_image.tflite`: MobileCLIP-S2 image encoder converted to TFLite, input `[1,3,256,256]` float32 in 0-1, output `[1,512]`.
2. `assets/crosswalk_emb.f32`: the L2-normalized MobileCLIP-S2 text embeddings for `crosswalk` and `zebra crossing`, 2 x 512 float32, little-endian.

Then set the default in `Prefs.crosswalk()` to `true` and add the switch back in `MainActivity`:

```java
addSwitch(root, "Crosswalk detection (heavy, more heat)", Prefs.CROSSWALK, Prefs.crosswalk(this));
```

Check the MobileCLIP license before distributing a build that includes it.

## Known limitations

- Rear camera only, typically a 65-80° field of view. Nothing at your feet or to the sides.
- No sound direction. Two closely spaced microphones cannot resolve front from back, and Android usually downmixes to mono.
- "Scooter" is COCO's `motorcycle` class. E-scooters are often, not always, detected as motorcycles.
- Continuous camera, inference and screen capture drain the battery and warm the phone.
- Native libraries are bundled for arm64-v8a and armeabi-v7a only.

## Contributing

Issues and pull requests are welcome. For changes to detection logic, please include how you tested it (images, audio clips, or device and conditions) and the before/after scores.

## License

App source: MIT License. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

Third-party components keep their own licenses; the MIT license for this app does not relicense downloaded libraries or model weights.

Third-party components are downloaded by `scripts/fetch_deps.sh` and are not part of this repository:

| Component | Source | License |
|---|---|---|
| TensorFlow Lite 2.16.1 | Maven Central | Apache 2.0 |
| EfficientDet-Lite0 (int8) | Google MediaPipe model storage | See the MediaPipe model card |
| YAMNet | Google MediaPipe model storage | See the MediaPipe model card |
| Eclipse Compiler for Java 3.33.0 | Maven Central | EPL 2.0 (build tool only, not shipped in the APK) |
