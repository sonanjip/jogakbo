# Development

- `source scripts/env.sh` sets JAVA_HOME (Android Studio's JBR), ANDROID_HOME, PATH, `APP_ID`.
- `local.properties`: `sdk.dir=…` and `ondolsaem.dir=../ondolsaem` to build the OndolSaem SDK from the sibling
  checkout (composite build); without it the SDK comes from JitPack (`com.github.sonanjip:ondolsaem:sdk-v0.5.0`, API 3 for the decision model).
- Build and check: `./gradlew assembleDebug testDebugUnitTest lintDebug`.
- Instrumented tests: `./gradlew installDebug installDebugAndroidTest`, then
  `adb shell am instrument -w io.github.taehunkim.jogakbo.test/androidx.test.runner.AndroidJUnitRunner`
  (`connectedDebugAndroidTest` uninstalls the app at the end). They use an in-memory database and fake models,
  speech and network (`androidTest/.../Fakes.kt`), plus the real UI (`NavigationTest`). `RealModelTest` feeds a
  recording from a file to OndolSaem's real speech model and checks that lines arrive while it records; it is skipped
  without the model or the fixture: `adb push long_talk.wav /data/local/tmp/` (Nunsori's `make-audio-fixtures.sh`), then
  `adb shell run-as io.github.taehunkim.jogakbo cp /data/local/tmp/long_talk.wav cache/`. Its
  `koreanNotesGetTheRightTags` runs OndolSaem's real decision model on Korean notes with the Korean example tags (logs
  every score; skipped without the model). `MigrationTest` checks schema 1 → 2 against `app/schemas`.
- The emulator's host microphone (`adb emu avd hostmicon`) may only deliver silence (macOS microphone access); use
  `RealModelTest` or an audio file for transcription checks.
- Scripts: `scripts/run.sh` (install and start), `scripts/screenshot.sh`, `scripts/logcat.sh`, `scripts/emu-start.sh`.
- Real-model checks need OndolSaem with a language, speech and embedding model on the device; the emulator runs
  them on the CPU (slow).
- Release signing: `NUNSORI_STORE_FILE` / `_PASSWORD` / `NUNSORI_KEY_ALIAS` / `_KEY_PASSWORD` (same key as the other
  apps, so they trust each other).
- Versions: the app in `app/build.gradle.kts` (`appVersionName`), the SDK in `sdk/build.gradle.kts`. A schema change
  needs `AppDatabase.VERSION` + a Migration + the schema JSON + a test; a chunking change bumps
  `NoteIndexer.NOTE_VERSION`.

Share and image coverage includes selected-text navigation and stream deduplication in NavigationTest, image OCR in DocumentTest, and original-image backup in NotesTest. Run instrumentation on an emulator or device after building; OCR tests use a fake engine.
