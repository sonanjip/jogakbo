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
  `files/audio`, and a raw PCM file in `files/rec` during a recording (deleted when it ends). App data is excluded
  from Android backup (`allowBackup=false`); export is the way to keep a copy.
- **Other apps**: apps signed with Jogakbo's key (Malbotdari) can search and read notes; any other app must be
  allowed by you in Settings › Connections. They get note text, never files.
- **Export**: the ZIP is written where you choose with the system file picker; it is not encrypted.
