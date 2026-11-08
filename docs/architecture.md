# Architecture

Jogakbo keeps notes in a Room database, makes their originals and summaries in background jobs, embeds them for
search, and serves them to other apps over a bound service. All AI runs in the companion app OndolSaem
(`ml/ModelHost`, OndolSaem client SDK); Jogakbo itself holds no models.

## Data (`data/db`, `AppDatabase.VERSION = 2`)

| Table | What it holds |
|---|---|
| `notes` | `uuid` (stable across export/import), `kind` (MEMO, LINK, YOUTUBE, AUDIO, DOCUMENT), `title`, `body` (original, Markdown), `summary` (Markdown), `summaryEdited`, `summaryPrompt`, `summaryState`/`summaryError`/`summaryModel`, `sourceUrl`, `sourceMeta` (JSON: site, channel, description, fetch time…), `sourceState`/`sourceError`, `audioFile` (under `files/audio`), `durationMs`, `createdAt`, `updatedAt` (text changes), `embeddedAt`/`embedKey`, `taggedAt` (schema 2) |
| `cues` | One line of a transcript or subtitle track (`startMs`, `endMs`, `text`): playback seek and the transcript list |
| `chunks` | A piece of a note's text with its embedding (int8 `[scale][int8 × dim]`, `ml/Quant`) and the model id |
| `tags` (2) | The user's tags: `name` (unique, case-insensitive), `description`, `ordinal` |
| `note_tags` (2) | One answer per note and tag: `state` (AUTO_YES, SUGGESTED, AUTO_NO from the decision model; MANUAL, REJECTED from the user), `score`, `modelId`, `at` |

Migrations: `MIGRATION_1_2` (0.2.0) adds `notes.taggedAt` and the two tag tables (`MigrationTest`).

Settings (`data/prefs/SettingsStore`, DataStore): embedding mode and schedule, the global summary prompt, the summary
language (ORIGINAL or APP), auto-summary per kind, automatic tagging and whether the example tags were added,
onboarding, approvals of other apps, and the mirrored model ids (language, embedding, decision).

## Notes and jobs

`notes/NoteRepository` creates, edits and deletes notes and hands background work to `NoteJobs`
(`jobs/WorkJobs` on WorkManager, a fake in tests). One `NoteWorker` runs per note and job (`note-<id>-source`,
`note-<id>-summary`, unique, foreground with a progress notification; links wait for the network).

- **Source** (`jobs/SourceJob`): makes the original.
  - Link: `fetch/UrlConnectionHttp` (redirects, 5 MB cap, charset from header or meta) → `fetch/ArticleExtractor`
    (drops navigation, asides, footers, ads, reference lists and wiki chrome; takes the element with the most paragraph
    text, weighted against link density, then walks up through wrappers and takes an ancestor that holds at least 1.5×
    the paragraph text, so an article split into sections stays whole; trailing "See also / References / External
    links" sections are cut) → `fetch/HtmlToMarkdown`.
  - YouTube (`fetch/YouTube`): the watch page (`ytInitialPlayerResponse`: title, channel, description, length, and
    the API key), then the player API as the Android app asks (`youtubei/v1/player`, client `ANDROID`) for the caption
    tracks; track choice: a track written by people in the spoken language (the language of the generated track),
    else the generated one, else any. The timed text (classic XML, srv3 or json3) becomes cues, merged into sentences
    (`mergeCues`). No captions: the description only, with a notice.
  - Audio file: copied into `files/audio`, decoded to 16 kHz mono PCM (`audio/AudioDecoder`, MediaCodec), transcribed
    by OndolSaem (`transcribe` streams cues; a cut-off run resumes after the last saved cue).
  - Then `afterSource` starts the summary (unless there is nothing to summarize, e.g. a recording without speech) when the user asked (`sourceMeta.summarize`, or a prompt) or the setting
    for the kind says so.
- **Summary** (`jobs/SummaryJob` → `summary/Summarizer`): input is the original (for transcripts the plain lines,
  without times). `summary/PromptBuilder` builds the system prompt: task and source kind, the Markdown format, the
  user's global instructions, and the language line last; the note's own instruction goes in the user turn above the
  text, and a one-line language reminder below it. For "same language as the original" the language is named when
  the script shows it (Hangul → Korean, kana → Japanese, Han → Chinese): a small model otherwise drifts into the
  English of the prompt. `summary/Tokens` estimates tokens (Hangul/CJK one per character, else 3.2 characters per token). Text that
  fits `contextTokens − prompt − answer reserve` is one call; longer text is cut at paragraph, line, sentence and
  word boundaries, each part becomes bullet notes (map), and the notes are summarized (reduce, again in parts if
  needed, up to 3 levels). An "exceeds" error from the host retries with parts 0.6× as large. Each call has a fresh
  conversation key and background priority.

## Recordings (`record/`)

`RecordingService` is a microphone foreground service. It creates the note (`sourceState = RUNNING`,
`sourceMeta.recording = true`), holds an OndolSaem speech lease and runs a `Recorder`:

- `PcmSource` (`MicSource`: `AudioRecord` 16 kHz mono, `VOICE_RECOGNITION`; `FileSource` for tests) → `files/rec/<uuid>.pcm`
  and `AacEncoder` (MediaCodec + MediaMuxer, 48 kbps AAC-LC) → `files/audio/<uuid>.m4a`. Pauses leave the paused
  audio out of both, so cue times match the m4a.
- `LiveTranscriber`: about every second, when at least 5 s are new, the PCM after the last given-out line is copied
  to a scratch file and transcribed; lines that end at least 1 s before the end are written to `cues` (the screen
  shows them), the last one is read again next time. Long silence moves the start on. Stopping runs a final pass.
- On stop the body is built from the cues, the PCM is deleted and the summary starts. Without a speech model the
  note is marked failed (Retry transcribes the m4a later). If the app is killed while recording, the next start
  finishes the note from the PCM (the m4a is encoded again from it).

Playback (`ui/notes/AudioPlayer`, `MediaPlayer`) seeks when a cue is tapped; YouTube cues open the video at `t=`.

## Search (`index/`, `search/`)

- `NoteIndexer`: chunks = the summary (with the note title) + the original through `text/MarkdownChunker` (heading
  path as chunk title, `text/TextChunker` fallback). Each chunk is embedded with `embedDocument(text, title)`
  (`retryShorter` on the 1024-token limit). `embedKey = "<model>|n<NOTE_VERSION>"`; a note is embedded again when
  `embeddedAt < updatedAt` or the key differs (model change in OndolSaem).
- Timing of the summary, embedding and tags of a new note: the mode below is the default (IMMEDIATE = now). A note can
  override it (`notes/Timing`, `"now": true|false` in `sourceMeta`, set by the "Summarize, index and tag now" checkbox
  of the editor and of the add-link/audio dialog). A later summary is `JobState.DEFERRED` (no job); the SCHEDULED, IDLE
  and manual passes make deferred summaries first, then embed and tag everything. The pass after a change (`auto`)
  takes only notes whose timing is now; in the other modes it returns at once unless a note asked for now.
- `EmbedScheduler` (`EmbedWorker`): IMMEDIATE (unique work 30 s after the last change, and a catch-up at start and
  after a model change), SCHEDULED (daily at a time, battery not low, optionally charging), IDLE (a check every 15 min while
  charging; the pass runs only when OndolSaem SDK's `IdleRule` says idle: the screen off for 10/30/60 min at ≥ 90 % /
  70–89 % / < 70 % battery with the Normal preset, Sooner 5/15/30, Later 30/60/120; screen-off times from
  `IdleState`; embedding and tagging stop between notes when the screen turns on), MANUAL ("Embed now").
- A pass that cannot reach OndolSaem's language model puts the waiting summary back to `DEFERRED` (`SummaryJob.run(keepWaiting
  = true)` returns false, the rest of the summaries wait, the idle/scheduled worker retries) instead of `FAILED`; the manual
  pass (`source = "now"`, tagged `embed-now`) also retries summaries that failed earlier (`NoteDao.failedSummaries`).
  The worker reports `setProgress` (stage summary/index/tags, done, total, note title), runs the summaries as a foreground
  service with the progress notification, and returns the counts (`OUT_SUMMARIZED/EMBEDDED/TAGGED/BLOCKED`); Settings
  shows both under the button, and the note list marks notes whose search index is waiting.
- `NoteSearch`: all vectors of the current model in memory (cached by a chunk stamp), a note's semantic score is
  its best chunk; notes below `max(0.30, top − 0.12)` are dropped. Words: every word of the query in the title,
  summary or original (SQL LIKE on the longest word, then checked in memory). Score = 1.2 × word score + semantic
  score (scaled 0.5–1 inside the band) + 0.15 when both match.

## Tags (`tags/`)

- `TagRepository`: the user's tags (add, rename or describe, delete, order) and their choices on notes
  (`addToNote` = MANUAL, `removeFromNote` = REJECTED). Renaming or describing a tag differently forgets the model's
  answers for it; *Tag all notes again* forgets all of them. The example tags (`R.array.tag_examples`, in the app
  language) are added once at first start.
- `NoteTagger`: runs in `EmbedWorker` after the embedding pass (so it follows the embedding time) when automatic
  tagging is on. It asks OndolSaem's decision model (`ModelHost.decider()`, category `decision`, SDK API 3) one
  question per tag in one call per note: premise = title + summary (or the start of the original, 1,500 characters,
  Markdown marks removed), statement = `Decisions.hypothesis(name, description)` ("이 글은 여행에 관한
  내용이다."). P ≥ 0.70 is AUTO_YES (shown), ≥ 0.40 SUGGESTED (offered on the note), else AUTO_NO. A note is asked
  again when it changed after `taggedAt`, or lacks an answer of the current decision model for some tag; MANUAL and
  REJECTED rows are never touched. Without a decision model nothing happens (and the embedding pass does not retry
  for it). On the emulator with the real model: 5 Korean notes × 7 example tags all right, no wrong tag, about 0.6 s
  per note (`RealModelTest`).

## Export and import (`io/`)

`NoteMarkdown` writes one note as Markdown: front matter (`jogakbo: 1`, `uuid`, `kind`, `title`, `created`,
`updated`, `source_url`, `summary_prompt`, `summary_edited`, `duration_ms`, `source_meta`, `audio`, `cues`, `tags`,
`tags_rejected`; strings JSON-quoted, tag lists as `["a", "b"]`), the title as `#`, then `<!-- jogakbo:summary -->` + `## Summary` and `<!-- jogakbo:original -->` +
`## Original`, so both read back exactly. `Backup` writes `notes/<yyyyMMdd>-<slug>-<uuid8>.md`, `audio/<uuid>.<ext>`
and `audio/<uuid>.cues.json`, plus `tags.json` (names, descriptions, order). Import adds the tags it does not know
(by name; existing tags keep their description), and a note's tags become the user's own (MANUAL / REJECTED); tags in
other apps' front matter (`tags: [a, b]`, `tags: a, b` or a block list) are read too. Import takes a ZIP or one `.md`/`.txt`: a known uuid is replaced only by a newer
`updated`; files without Jogakbo front matter become memos (title from front matter, the first `#` heading or the
file name). Imported notes are embedded by the scheduler.

## Other apps (`service/`, `:sdk`)

See [notes-sdk.md](notes-sdk.md). `NotesService` (exported, action `io.github.taehunkim.jogakbo.action.BIND`) checks
every call with `CallerCheck` (same signing key: allowed; others: a request recorded in `Approvals`, a notification,
and refusal until the user allows the app in Settings › Connections). `NotesApi` is the logic. `NotesEvents`
broadcasts `NOTES_CHANGED` (no data) after changes and embedding passes.

## UI (`ui/`)

Compose + Navigation: notes list (search, kind filters, + menu), note detail (summary card, source state, player,
transcript, original), editor (`ui/editor/MarkdownEditor`: toolbar from `MarkdownActions`, preview with
`ui/common/Markdown`, which renders CommonMark with GFM tables, strikethrough and task lists), recording screen,
settings (Notes, Data, Connections, App), onboarding. Shares (`ACTION_SEND` text/URL/audio) open the add dialog.

### Attached documents
`DOCUMENT` originals are copied under `files/docs`; `sourceMeta` holds the safe basename and document metadata (no schema change). `DocumentExtractor` uses pdfbox text per page and OndolSaem OCR for pages without usable text, OOXML/ODF parsing adapted from Nunsori, or text decoding. Page/slide/sheet headings survive in chunk titles. Up to 500 PDF pages, 400 Office units and 400,000 stored characters keep Room rows below CursorWindow limits; a truncation notice is shown. Interrupted extraction is re-enqueued at startup and starts again idempotently. Summary map/reduce retains section labels, at most six reduction levels and 256 calls, failing explicitly rather than silently discarding remaining parts. Document originals can be opened with a read-only FileProvider grant and included in ZIP backups; absent originals after text-only import are not trusted from metadata.
