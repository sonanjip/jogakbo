# Third-party notices

Jogakbo is licensed under the [Apache License 2.0](LICENSE), Copyright 2026 Taehun Kim.
It uses the components below. Versions come from [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

## Libraries (linked into the app)

| Component | Version | License |
|---|---|---|
| AndroidX (Compose BoM, Material 3, Activity, Navigation, Lifecycle, Room, WorkManager, DataStore, AppCompat) | see catalog (Compose BoM 2026.09.00, Room 2.8.5, WorkManager 2.12.0) | Apache-2.0 |
| Kotlin, kotlinx.coroutines | 2.4.21 / 1.11.0 | Apache-2.0 |
| OndolSaem client SDK | sdk-v0.3.0 | Apache-2.0 |
| commonmark-java (+ GFM tables, strikethrough, task list items) | 0.32.0 | BSD-2-Clause |
| jsoup | 1.24.1 | MIT |
| pdfbox-android | 2.0.27.0 | Apache-2.0 (text layer of PDF documents) |

Code adapted from the sibling projects Nunsori (text chunkers, document extraction: Office/PDF parsers and text decoding, audio decoding, int8 vectors, caller approval) and
Malbotdari (Markdown rendering, model host connection) is by the same author under the same license.

## Models

No model is stored in this repository or bundled in the app. The language, speech recognition and embedding models
are downloaded and run by the companion app OndolSaem, whose THIRD_PARTY_NOTICES.md lists the models and runtimes
with their licenses.

## Web content

Jogakbo reads web pages and YouTube videos only when the user adds them; their text belongs to its authors and is
kept on the phone for the user's own notes.
