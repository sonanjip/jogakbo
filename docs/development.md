# Development

- `source scripts/env.sh` sets JAVA_HOME (Android Studio's JBR), ANDROID_HOME, PATH, `APP_ID`.
- `local.properties`: `sdk.dir=…` and `ondolsaem.dir=../ondolsaem` to build the OndolSaem SDK from the sibling
  checkout (composite build); without it the SDK comes from JitPack (`com.github.TaeHunKim:ondolsaem:sdk-v0.3.0`).
- Build and check: `./gradlew assembleDebug testDebugUnitTest lintDebug`.
- Instrumented tests: `./gradlew installDebug installDebugAndroidTest`, then
  `adb shell am instrument -w io.github.taehunkim.jogakbo.test/androidx.test.runner.AndroidJUnitRunner`
  (`connectedDebugAndroidTest` uninstalls the app at the end). They use an in-memory database and fake models,
  speech and network (`androidTest/.../Fakes.kt`), plus the real UI (`NavigationTest`).
- Scripts: `scripts/run.sh` (install and start), `scripts/screenshot.sh`, `scripts/logcat.sh`, `scripts/emu-start.sh`.
- Real-model checks need OndolSaem with a language, speech and embedding model on the device; the emulator runs
  them on the CPU (slow).
- Release signing: `NUNSORI_STORE_FILE` / `_PASSWORD` / `NUNSORI_KEY_ALIAS` / `_KEY_PASSWORD` (same key as the other
  apps, so they trust each other).
- Versions: the app in `app/build.gradle.kts` (`appVersionName`), the SDK in `sdk/build.gradle.kts`. A schema change
  needs `AppDatabase.VERSION` + a Migration + the schema JSON + a test; a chunking change bumps
  `NoteIndexer.NOTE_VERSION`.
