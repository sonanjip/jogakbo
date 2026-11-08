# User guide

## First start

Jogakbo shows what it needs: OndolSaem with its models (language model for summaries, speech recognition for
recordings, embedding model for search by meaning) and the notification and microphone permissions. Memos, links,
word search and export work without OndolSaem.

## Adding notes (+ button)

- **New memo**: a title (empty: the first line is used) and the text. The toolbar above the text adds Markdown:
  H1–H3, bold, italic, strikethrough, bullet and numbered lists, checklists, quotes, code, links and dividers; tap
  again to remove. *Preview* shows the result. Tick *Make a summary after saving* to summarize it.
- **Web or YouTube link**: paste an address, or share a link from the browser or YouTube app to Jogakbo. Choose
  whether to summarize and add an instruction for this summary ("make a table of the prices").
- **Record**: the transcript appears line by line while you record (it lags a few seconds). Pause and Stop are on
  the screen and in the notification; recording continues with the screen off.
- **Document**: pick a text, Markdown, PDF, Word, PowerPoint, Excel or OpenDocument file (or share one to Jogakbo); the text is read in the background, pages without a text layer by text recognition (OndolSaem). The original file stays in the app; very long documents keep their first 400,000 characters. Summaries of long documents are made in parts and then combined.
- **Audio file**: pick a file or share one from a recorder app; it is transcribed in the background.

## A note

The summary is on top (fold it with the arrow), the original below. *Summarize again* takes a new instruction; if
you edited the summary, it asks before replacing your version. *Edit summary* opens the same editor as memos.
Recordings have a player: tap a line to play from there. YouTube notes list the subtitles: tap a line to open the
video at that time. A link or audio file that failed shows *Retry*. The ⋮ menu shares the note as Markdown, deletes
its audio (keeping the text) or deletes the note.

## Tags

Notes carry tags, shown as `#tag` in the list and as chips on the note. With *Tag notes automatically* on (Settings ›
Tags) OndolSaem's decision model checks each note against each tag when notes are embedded; a tag it is unsure about
appears as `#tag?`: tap to add it, × to decline. Tap × on a tag to take it off; *Tags* on a note opens the full list
to tick. Tags you add or take off yourself are never changed again by the model. The chips under the kind filters show
notes with any of the chosen tags.

## Search

Type in the search field: notes match by words right away and by meaning once they are embedded (Settings ›
Notes › Search by meaning). The chips filter by kind.

## Settings

- **Notes**: instructions for every summary, the summary language (same as the original, or the app's language),
  which kinds are summarized automatically, and when new notes are summarized, embedded and tagged (now, right after
  a change; while charging and not in use; once a day at a time; or only with *Summarize, index and tag now*). When you
  add a note, *Summarize, index and tag now* under *Make a summary* overrides this for that note, both ways. "Not in use" means the screen has been off for 10, 30 or
  60 minutes when the battery is at 90 % or more, 70–89 % or below 70 % (*Sooner* and *Later* shorten or lengthen
  this); it stops when you use the phone. Under *Summarize, index and tag now* Settings shows how many summaries and notes
  are waiting, what the run is doing (which note is being summarized, indexing, tagging) and what the last run did; the
  button also tries again the summaries that failed. In the note list a note shows *Summary waits*, *Summarizing…*,
  *Summary failed* or *Search index waits*. If OndolSaem's language model is not available during an automatic run, the
  summaries keep waiting for the next run instead of failing.
- **Tags**: your tags (add, edit with an optional description of what belongs there, reorder, delete), automatic
  tagging on or off, and *Tag all notes again*. Seven example tags are added at first start.
- **Data**: export a ZIP (one Markdown file per note with its tags, and the tag list; summaries and audio optional) and import a ZIP or a Markdown
  or text file. Notes already here are only replaced by newer versions.
- **Connections**: OndolSaem and its models (the decision model is needed for automatic tags); other apps that asked for your notes (allow, deny, forget).
- **App**: permissions, language, licenses.

## With Malbotdari

Malbotdari (0.2.0 or newer) can search and read your notes in a chat ("find my notes about the Jeju trip"). Its
result cards open the note here. It is signed with the same key, so it connects without asking.
*Ask in chat* in a note's menu opens a new chat in Malbotdari with that note attached; for a long note the chat looks up
only the passages it needs (`search_in_note`) instead of reading everything.

In a document note’s menu choose **Open original document** to open the saved file in another app. Export can include the original document files. Long-press notes in the list to select them, then choose **Ask in chat** (up to ten per chat). The detail menu also has this action. Legacy binary Office files (.doc/.xls/.ppt) and HWP are not supported. Text beyond the extraction limit stays only in the original file; summaries and search cover the extracted portion.

### Share from other apps

Select text and choose Jogakbo from the text actions, or share text, links, audio, images and supported documents to Jogakbo. Review each file’s type and size and choose which items to include before importing; at most 10 selected items are allowed. Each selected file is limited to 100 MiB, and files together to 200 MiB; limits are checked while copying, even when the sender does not report a size. The app shows copying progress and keeps the receiving screen open until copying finishes. A multiple-item share creates a separate note for each item. Images become image notes and use on-device text recognition through OndolSaem. Photos with no recognized text are kept and show that no text was found. Open the original from the note menu. In Settings → Data, stored original sizes are shown and the backup option includes original documents and images; audio has its own option. Failed imports are listed again for retry or exclusion; successful items are not duplicated.
