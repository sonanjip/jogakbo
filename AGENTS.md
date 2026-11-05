# Jogakbo / 조각보 (Android)
AI 노트 앱: 메모, 웹페이지, 유튜브, 녹음 → 위에 요약, 아래 원문(모두 마크다운). applicationId·패키지 `io.github.taehunkim.jogakbo`, Kotlin + Compose, minSdk 29 / target 36 / compile 37. 모델(언어·음성 인식·임베딩)은 온돌샘(`../ondolsaem`) SDK로만 쓴다. INTERNET은 사용자가 넣은 링크·유튜브를 읽을 때만 쓴다. 노트는 `:sdk`(AIDL `INotes`)로 말벗다리 등에 연다.

## 문서 유지 규칙
코드를 바꾸면 같은 커밋에서 관련 문서도 고친다(필요 없으면 이유 한 줄). 새 기능·화면·설정 → README(en/ko 기능 표), `docs/user-guide.md`; 구조·데이터·파이프라인 → `docs/architecture.md`; SDK·도구 → `docs/notes-sdk.md`; 권한·네트워크·저장 데이터 → `docs/privacy.md`; 빌드·테스트 → `docs/development.md`; 계획·한계 → `docs/roadmap.md`; 의존성 → `THIRD_PARTY_NOTICES.md`. 문자열은 `values/strings.xml`(en)과 `values-ko/strings.xml`(ko) 키가 같아야 한다(`StringsParityTest`).

## 스키마·버전
`@Entity` 변경 시 `AppDatabase.VERSION` 증가 + `Migrations.kt`(`ALL_MIGRATIONS`) + `app/schemas` JSON + 마이그레이션 테스트. destructive 폴백 금지(노트는 사용자 데이터). 청크 방식이 바뀌면 `NoteIndexer.NOTE_VERSION`. 앱 버전은 `app/build.gradle.kts`의 `appVersionName`, SDK 버전은 `sdk/build.gradle.kts`.

## 환경·워크플로우
- `source scripts/env.sh`(JBR, SDK, adb). `scripts/run.sh`, `scripts/screenshot.sh`, `scripts/logcat.sh`, `scripts/emu-start.sh`.
- `local.properties`: `ondolsaem.dir=../ondolsaem`(composite build).
- `./gradlew assembleDebug testDebugUnitTest lintDebug`. 계측 테스트는 `installDebug installDebugAndroidTest` 후 `adb shell am instrument -w io.github.taehunkim.jogakbo.test/androidx.test.runner.AndroidJUnitRunner`(`connectedDebugAndroidTest`는 끝나고 앱을 지운다).

## 구조 (요약)
- `data/db`: `notes`(원문 `body`, `summary`, 작업 상태), `cues`(녹음·자막 줄), `chunks`(int8 임베딩). `notes/NoteRepository`가 만들고 고치고, 백그라운드 작업은 `NoteJobs`(앱: `jobs/WorkJobs`, 테스트: 가짜).
- `jobs/`: `SourceJob`(링크 → `fetch/ArticleExtractor`, 유튜브 → `fetch/YouTube`, 오디오 → `audio/AudioDecoder` + 온돌샘 ASR), `SummaryJob`(`summary/Summarizer`: 창에 맞게 나눠 요약), `NoteWorker`.
- `record/`: `RecordingService`(마이크 포그라운드) → `Recorder`(PCM + AAC m4a) + `LiveTranscriber`(녹음 중 받아쓰기).
- `index/`: `NoteIndexer`(청크·임베딩), `EmbedScheduler`(바로·하루 한 번·충전+유휴·수동). `search/NoteSearch`(의미 + 단어).
- `io/`: `NoteMarkdown`(노트 ↔ .md), `Backup`(ZIP 내보내기·가져오기). `service/`: `NotesService`·`NotesApi`·`CallerCheck`+`Approvals`·`NotesEvents`.
- 자세히: `docs/architecture.md`.

## 테스트 메모
- 단위 테스트는 가짜 모델·저장된 HTML/자막으로 돈다. 계측 테스트(`NotesTest`)는 메모리 DB + 가짜 모델·음성·네트워크, `NavigationTest`는 실제 화면.
- 실제 모델 E2E는 에뮬레이터에 온돌샘 + 언어·음성·임베딩 모델이 있어야 한다. 유튜브 파싱은 유튜브 페이지가 바뀌면 깨질 수 있다(`fetch/YouTube.kt`만 고치면 됨).
