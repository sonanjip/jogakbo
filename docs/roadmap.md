# Roadmap

## Done in 0.1.0

Memos with a Markdown toolbar, web and YouTube notes, recordings with a live transcript and imported audio,
summaries (global and per-note instructions, language, editing, long texts in parts), search by meaning and words
with a chosen embedding time, ZIP/Markdown export and import, and the notes SDK for Malbotdari.

## Next

1. **Notes from chat**: write tools for Malbotdari (`add_note`, `append_to_note`, a summary on request), confirmed
   in its UI like Nunsori's file actions.
2. **Better summaries**: per-kind default prompts the user can edit; a summary that streams into the note while it is
   made; keep the map step's part notes for very long recordings.
3. **Speaker turns** in recordings (diarization) and a live transcript that refines lines after the fact.
4. **More sources**: PDFs and images shared to the app (OCR through OndolSaem), podcasts (audio URLs), web pages
   that need JavaScript.
5. **Organizing**: tags or folders, pinning, merging notes; related notes by meaning under each note.
6. **Nunsori**: optionally index notes into Nunsori's central index (the roadmap's provider sketch), so one search
   covers files and notes.

## Limits

- YouTube has no public subtitle API: the fetcher reads the watch page and the player API, which YouTube changes
  now and then (everything is in `fetch/YouTube.kt`, covered by tests with saved answers). Videos that need sign-in
  or are age-restricted give no subtitles.
- Article extraction is heuristic: pages built by JavaScript, paywalls and some layouts give little text.
- Summary length and quality depend on OndolSaem's language model and its context window (4096 tokens by default);
  long texts are summarized in parts.
- On the emulator (CPU) live transcription lags behind and summaries take minutes.
