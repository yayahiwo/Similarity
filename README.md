<H1>Similarity</H1>
<ul>
<li> Indexing speed optimization, now indexes about 50 images per second (CPU only) on a Qualcomm Snapdragon 8 Gen 2. equivilant to 3000 images per minute.</li>
<li> Added image-to-image similarity threshold control slider.</li>
<li> Added multi-select in the grid.</li>
<li> Bulk Delete, Move, Share.</li>
<li> Automatic index cleanup when photos are deleted.</li>
<li> Added a persistent “folders to index” setting.</li>
<li> Changed startup so the app doesn’t auto-index but waits for folder selection.</li>
<li> Added indexing progress + indexed count display.</li>
<li> Added re-index progress UI and auto-refreshes the grid when indexing finishes.</li>
<li> Added pinch-to-zoom for the thumbnails grid</li>
<li> Made the grid remember its zoom level.</li>
<li> In image-to-image results, added dimensions under each thumbnail can betoggled on/off from settings.</li>
<li> Back button to return from image-to-image results to the full “all images” grid.</li>
<li> Single-image screen: shows file location, file size, and image dimensions.</li>
<li> Adjusted single-image navigation so swipe-to-next/previous.</li>
<li> Find near-duplicates tool.</li>
<li> Library Stats display.</li>
<li> Added Lossless JPEG rotation</li>
<li> Added Reset index to delete on-device embedding database</li>
<li> Added stats screen (statistics of images count, size on desk etc)</li>
<li> Added Backup and Restore database option</li>
<li> Added manual metadata scan</li>
<li> Added settings for Show similarity slider, CPU micro-batch size, use size filtering</li>
</ul>

<p>Thanks and Credit to https://github.com/slavabarkov for the original Tidy code</p>

<p><b>Disclaimer 1</b>: I am not a java developer and can't even understand most of the code in this repository, and also very new to git, I just want to put this here if it's useful to anyone.</p>

<p><b>Disclaimer 2</b>: This software is provided “AS IS”, without warranty of any kind, express or implied, including but not limited to warranties of merchantability, fitness for a particular purpose, and noninfringement. Use of this app and its source code is at your own risk. The author(s) and contributor(s) are not responsible or liable for any damages, losses, data loss, device issues, security/ privacy incidents, or other harm arising from the use, misuse, or inability to use the software, including actions such as deleting, moving, or modifying files/photos. Always review permissions and keep backups of important data before use.</p>

<h2>Screenshots of the UI</h2>
<div style="display:flex;">
<img alt="Text-to-Image Search" src="/res/Similarity_V0.8.4.jpg" width="100%">
</div>

---

## Build prerequisites
- Android Studio (recommended) or Android SDK command-line tools
- Android SDK + NDK (required for the native `externalNativeBuild`/CMake code)
- Java 17+ (Android Gradle Plugin requires Java 17)

## Clone + submodules
This repo uses git submodules under `third_party/` (required for native builds).

```bash
git submodule update --init --recursive
```

## Model assets
The app expects SigLIP2 ONNX + tokenizer assets under `app/src/main/res/raw/`. They are intentionally excluded from git (see `.gitignore`) because they are large.

Download them with:

```bash
./scripts/fetch_siglip2_assets.sh float
```

Options:
- `float` (default): float models (CPU-friendly)
- `int8`: INT8 models
- `both`: downloads both

## Build and install (USB device)
If Gradle fails with a “requires Java 17” error, either set `JAVA_HOME` to a Java 17+ JDK, or (macOS + Android Studio) run Gradle using Android Studio’s bundled JDK:

```bash
./gradlew -Dorg.gradle.java.home="/Applications/Android Studio.app/Contents/jbr/Contents/Home" :app:assembleDebug
```

```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

Verify the device is connected:
```bash
adb devices -l
```

## Build (Release)
Release builds are shrinked/obfuscated (R8) and also require signing. For convenience, this project will sign `release` with the debug keystore unless you configure a release keystore.

To configure a release keystore, copy `keystore.properties.example` → `keystore.properties` (not committed) or set env vars:
- `SIMILARITY_KEYSTORE_PATH`
- `SIMILARITY_KEYSTORE_PASSWORD`
- `SIMILARITY_KEY_ALIAS`
- `SIMILARITY_KEY_PASSWORD`

```bash
./gradlew :app:assembleRelease
```

## Tests / Lint
```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```
