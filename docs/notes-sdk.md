# Notes SDK (`:sdk`)

Other apps on the phone (Malbotdari first) search and read Jogakbo's notes through a bound service. The SDK is the
`:sdk` module (`io.github.taehunkim.jogakbo.sdk`, version in `sdk/build.gradle.kts`, JitPack
`com.github.sonanjip:jogakbo:sdk-v<version>`, built by `jitpack.yml`; or a composite build with `jogakbo.dir`).

## Access

Apps signed with Jogakbo's key connect without asking. Any other app's first call records a request, notifies the
user and fails with `NOT_APPROVED` (`JogakboUnavailableException.Reason.NOT_APPROVED`); open
`Jogakbo.approvalIntent()` so the user can allow it. A decision holds for the signing certificate it was made for.

## API 1 (`INotes.aidl`, JSON payloads, methods only appended)

| Method | Returns |
|---|---|
| `hello(clientJson)` | `{"api","minClientApi","appVersion"}` (`CLIENT_TOO_OLD` below the minimum) |
| `tools()` | `[{"name","description","parameters","confirm":false}]`: `search_notes`, `read_note` |
| `runTool(name, argsJson)` | The text the model sees |
| `notes(ids)` | `[{"note_id","title","kind","created","updated","url"?,"tags"?}]` |
| `readNote(id, maxChars)` | Markdown: `# title`, `Source:`, `## Summary`, `## Original` |
| `changesSince(cursor, limit)` | `{"ids","cursor"}`; cursor 0 = from now on (an `updatedAt` time) |

Tools:

- `search_notes(query?, kinds?, tags?, date_from?, date_to?, limit?)`: kinds `memo, link, youtube, audio`; tags
  comma-separated names (any of them; since app 0.2.0); dates `YYYY-MM-DD` on the creation date; no query lists the
  newest notes. Returns `note_id`, `title`, `kind`, `created`, `updated`, `url`, `tags` (shown tags), `snippet`, or
  `No matching notes.`.
- `read_note(note_id, offset?, max_chars?)`: the note's `tags` and a slice (default 3000, up to 6000 characters) with `next_offset` when
  more is left.

`Jogakbo.ACTION_NOTES_CHANGED` is broadcast explicitly to apps with a receiver (the SDK declares
`NotesChangedReceiver`; set its `handler` in `Application.onCreate`). `Jogakbo.openNoteIntent(id)` opens a note.

## Client

```kotlin
val client = JogakboClient(context)
val tools = client.tools()                       // for the chat model
val out = client.runTool("search_notes", """{"query":"tea"}""")
val text = client.readNote(id, 4000)
```

Calls are suspend functions on `Dispatchers.IO`; the client binds lazily, retries once after Jogakbo's process died
and unbinds after 60 s idle. Failures: `JogakboUnavailableException` (NOT_INSTALLED, NOT_ALLOWED, NOT_APPROVED,
NO_ANSWER, HOST_TOO_OLD, CLIENT_TOO_OLD) or `JogakboCallException(code)`.
