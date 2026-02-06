# Repository Guidelines

## Project Structure & Module Organization
- `app/`: Android application module (Kotlin).
- `app/src/main/java/com/yayahiwo/similarity/`: app code (`fragments/`, `viewmodels/`, `data/`, `utils/`, `tokenizer/`).
- `app/src/main/res/`: Android resources (layouts, drawables, values, navigation).
- `app/src/main/res/raw/`: on-device models and tokenizer assets (`visual_quant.onnx`, `textual_quant.onnx`, `vocab.json`, `merges.txt`).
- `res/`: repository assets used by `README.md` (screenshots/banners).
- `fastlane/metadata/`: store listing metadata (if publishing via Fastlane).

## Build, Test, and Development Commands
Prereqs: Android Studio + Android SDK, and Java 17 (required by the Android Gradle Plugin).
- `./gradlew assembleDebug`: build a debug APK.
- `./gradlew installDebug`: install debug build to a connected device/emulator.
- `./gradlew assembleRelease`: build a release APK (signing not configured by default).
- `./gradlew assembleDebug -PuseQnn=true`: build with the ONNX Runtime QNN (Qualcomm Hexagon/HTP) Execution Provider enabled.
- `./gradlew lintDebug`: run Android Lint.
- `./gradlew testDebugUnitTest`: run local (JVM) unit tests.
- `./gradlew connectedDebugAndroidTest`: run instrumentation tests on a device/emulator.

Tip: `local.properties` is machine-specific (Android SDK path). Don’t commit it.
Tip: for Android Studio builds, set `useQnn=true` in `~/.gradle/gradle.properties` (or remove it to go back to CPU).

## Coding Style & Naming Conventions
- Indentation: 4 spaces; follow Android Studio’s default Kotlin style.
- Kotlin: `PascalCase` for types/files, `camelCase` for functions/vars, `UPPER_SNAKE_CASE` for constants.
- Resources: use `snake_case` (e.g., `ic_search.xml`, `fragment_search.xml`).
- Prefer adding UI strings to `app/src/main/res/values/strings.xml` over hardcoding text.

## Testing Guidelines
- Frameworks: JUnit4 (unit), AndroidX JUnit + Espresso (instrumentation).
- Place tests in `app/src/test/java/...` and `app/src/androidTest/java/...`.
- Naming: `*Test.kt` (e.g., `TokenizerTest.kt`).

## Commit & Pull Request Guidelines
- Commits: short, imperative subject lines (e.g., “Fix …”, “Add …”, “Update …”), usually without a trailing period.
- PRs: include a clear description, link related issues, and add screenshots/screen recordings for UI changes; note the device/emulator + Android version you tested on.

## Security & Configuration Tips
- Be extra cautious with MediaStore delete/move flows: keep destructive actions behind explicit user confirmation and test on real devices.
