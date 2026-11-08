# Roadmap

## Done in 0.2.0

Tags you define (with examples), checked on every note by OndolSaem's decision model at the embedding time;
suggestions for unsure ones; tag filters; tags in export/import and in the notes SDK.

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
4. **More sources**: podcasts (audio URLs), web pages
   that need JavaScript.
5. **Organizing**: folders, pinning, merging notes; related notes by meaning under each note (tags with automatic
   tagging are done in 0.2.0). Tags: let Malbotdari tag notes from the chat; tag images and audio once OndolSaem has a
   decision model that reads them (d1-omni, see OndolSaem's roadmap).
6. **YouTube through the official API**: an optional YouTube Data API v3 key (entered by the user in Settings) for
   the title, channel, description, length and caption list (`videos.list`, `captions.list`), so these no longer
   depend on the watch page. Caveat: downloading caption text (`captions.download`) needs OAuth and is allowed only
   for the video's owner, so the subtitles themselves stay on the current page-based fetch; without a key everything
   works as now.
7. **Nunsori**: optionally index notes into Nunsori's central index (the roadmap's provider sketch), so one search
   covers files and notes.

## Limits

- YouTube has no public subtitle API: the fetcher reads the watch page and the player API, which YouTube changes
  now and then (everything is in `fetch/YouTube.kt`, covered by tests with saved answers). Videos that need sign-in
  or are age-restricted give no subtitles.
- Article extraction is heuristic: pages built by JavaScript, paywalls and some layouts give little text.
- Summary length and quality depend on OndolSaem's language model and its context window (4096 tokens by default);
  long texts are summarized in parts.
- On the emulator (CPU) live transcription lags behind and summaries take minutes (about 1–2 minutes for a short note
  with Gemma 4 E2B).
- The small default language model follows simple instructions but not always formatting requests (asked for a table, it
  wrote a list).

Document attachments now cover text/Markdown, PDF (text or OCR), OOXML and OpenDocument. Current limits: 400,000 extracted characters, 500 PDF pages, 400 Office units; full original is retained. Binary legacy Office/HWP and arbitrary-page image viewing from chat remain outside this implementation.

Shared image originals now use the document pipeline and OndolSaem OCR. Recognition extracts text only; it does not describe image content.

Shared imports accept at most 10 selected items and 100 MiB per file / 200 MiB total. Files whose copy fails are reported while other selected items can continue.
