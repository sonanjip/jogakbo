# Architecture

Jogakbo keeps notes in a Room database, makes their originals and summaries in background jobs, embeds them for
search, and serves them to other apps over a bound service. All AI runs in the companion app OndolSaem
(`ml/ModelHost`, OndolSaem client SDK); Jogakbo itself holds no models.

## Data (`data/db`, `AppDatabase.VERSION = 1`)

| Table | What it holds |
|---|---|
| `notes` | `uuid` (stable across export/import), `kind` (MEMO, LINK, YOUTUBE, AUDIO), `title`, `body` (original, Markdown), `summary` (Markdown), `summaryEdited`, `summaryPrompt`, `summaryState`/`summaryError`/`summaryModel`, `sourceUrl`, `sourceMeta` (JSON: site, channel, description, fetch time…), `sourceState`/`sourceError`, `audioFile` (under `files/audio`), `durationMs`, `createdAt`, `updatedAt` (text changes), `embeddedAt`/`embedKey` |
| `cues` | One line of a transcript or subtitle track (`startMs`, `endMs`, `text`): playback seek and the transcript list |
| `chunks` | A piece of a note's text with its embedding (int8 `[scale][int8 × dim]`, `ml/Quant`) and the model id |

Settings (`data/prefs/SettingsStore`, DataStore): embedding mode and schedule, the global summary prompt, the summary
language (ORIGINAL or APP), auto-summary per kind, onboarding, approvals of other apps, and the mirrored model ids.

## Notes and jobs

`notes/NoteRepository` creates, edits and deletes notes and hands background work to `NoteJobs`
(`jobs/WorkJobs` on WorkManager, a fake in tests). One `NoteWorker` runs per note and job (`note-<id>-source`,
`note-<id>-summary`, unique, foreground with a progress notification; links wait for the network).

- **Source** (`jobs/SourceJob`): makes the original.
  - Link: `fetch/UrlConnectionHttp` (redirects, 5 MB cap, charset from header or meta) → `fetch/ArticleExtractor`
    (drops navigation, asides, footers and ads, takes the element with the most paragraph text, weighted against
    link density) → `fetch/HtmlToMarkdown`.
  - YouTube (`fetch/YouTube`): the watch page (`ytInitialPlayerResponse`: title, channel, description, length, and
    the API key), then the player API as the Android app asks (`youtubei/v1/player`, client `ANDROID`) for the caption
    tracks; track choice: a track written by people in the spoken language (the language of the generated track),
    else the generated one, else any. The timed text (classic XML, srv3 or json3) becomes cues, merged into sentences
    (`mergeCues`). No captions: the description only, with a notice.
  - Audio file: copied into `files/audio`, decoded to 16 kHz mono PCM (`audio/AudioDecoder`, MediaCodec), transcribed
    by OndolSaem (`transcribe` streams cues; a cut-off run resumes after the last saved cue).
  - Then `afterSource` starts the summary when the user asked (`sourceMeta.summarize`, or a prompt) or the setting
    for the kind says so.
- **Summary** (`jobs/SummaryJob` → `summary/Summarizer`): input is the original (for transcripts the plain lines,
  without times). `summary/PromptBuilder` builds the system prompt: task and source kind, the Markdown format, the
  user's global instructions, and the language line last; the note's own instruction goes in the user turn above the
  text. `summary/Tokens` estimates tokens (Hangul/CJK one per character, else 3.2 characters per token). Text that
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
- `EmbedScheduler` (`EmbedWorker`): IMMEDIATE (unique work 30 s after the last change, and a catch-up at start and
  after a model change), SCHEDULED (daily at a time, battery not low, optionally charging), IDLE (every 3 h while
  charging and idle), MANUAL ("Embed now").
- `NoteSearch`: all vectors of the current model in memory (cached by a chunk stamp), a note's semantic score is
  its best chunk; notes below `max(0.30, top − 0.12)` are dropped. Words: every word of the query in the title,
  summary or original (SQL LIKE on the longest word, then checked in memory). Score = 1.2 × word score + semantic
  score (scaled 0.5–1 inside the band) + 0.15 when both match.

## Export and import (`io/`)

`NoteMarkdown` writes one note as Markdown: front matter (`jogakbo: 1`, `uuid`, `kind`, `title`, `created`,
`updated`, `source_url`, `summary_prompt`, `summary_edited`, `duration_ms`, `source_meta`, `audio`, `cues`; strings
JSON-quoted), the title as `#`, then `<!-- jogakbo:summary -->` + `## Summary` and `<!-- jogakbo:original -->` +
`## Original`, so both read back exactly. `Backup` writes `notes/<yyyyMMdd>-<slug>-<uuid8>.md`, `audio/<uuid>.<ext>`
and `audio/<uuid>.cues.json`. Import takes a ZIP or one `.md`/`.txt`: a known uuid is replaced only by a newer
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
