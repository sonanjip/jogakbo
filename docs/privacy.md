# Privacy

- **Network**: Jogakbo has the `INTERNET` permission only to read what you add: the web page of a link you save,
  and for YouTube links the watch page, YouTube's player API and the subtitle file of that video. Requests carry a
  generic mobile browser user agent and a consent cookie; nothing about you or your notes is sent. Nothing else
  uses the network (no analytics, no accounts, no sync).
- **AI**: summaries, speech recognition, embeddings and tags (the decision model) run in OndolSaem on the phone,
  reached over Android's on-device IPC. Note text and audio go to OndolSaem only.
- **Microphone**: only while you record; a notification shows it, and the service stops with the recording.
- **Stored data**: the database `jogakbo.db` (notes, transcript lines, embeddings, tags and the decision model's
  answers), audio files in the app's private
  `files/audio`, attached documents in `files/docs` (original files, kept for the user; their text is in the note), and a raw PCM file in `files/rec` during a recording (deleted when it ends). App data is excluded
  from Android backup (`allowBackup=false`); export is the way to keep a copy.
- **Other apps**: apps signed with Jogakbo's key (Malbotdari) can search and read notes; any other app must be
  allowed by you in Settings › Connections. They get note text, never files.
- **Export**: the ZIP is written where you choose with the system file picker; it is not encrypted.

Opening a saved document gives the chosen viewer a temporary read grant limited to that file through FileProvider. Exported ZIPs optionally include document originals in `docs/`, with extracted text kept in the Markdown note.

Shared audio, documents and images are copied into private app storage when the import is confirmed, subject to a 100 MiB per-file and 200 MiB per-share streaming limit. Image OCR runs through OndolSaem; the recognized text is stored in the note. Original images travel with the optional document originals in ZIP backups, and deleting the note deletes its local original.
