# Settings

The settings hub and its pages, on the frame in Layout and spacing.

- **No "Settings" title.** The hub starts with its first group, Appearance, at content top (84). On Compact and Medium the bar holds only the menu button; on Expanded there is no bar content at all.
- **Expanded: two panes on one top line.** The hub is the 320 list pane on the gutter; the open page follows 24 later and is at most 800 wide. Both start at content top, so the hub's first group label and the page's first line share a top edge.
- **Gutter 24** from Medium, 16 on phones; the panes are a gutter apart. Group labels sit on the panel's edge, and so does a page's one-line intro.
- A page opened on Compact or Medium shows Back and its name in the bar, and the bar has no glass until something scrolls under it.
- The open page is marked by the selected hub row (`primary-container`); the page itself carries no title.

## The hub

- **Every row says its value**, not what its page is for: a server's row its state ("Synced 09:12", "Synced yesterday", "Offline", "Checking", "Syncing", "Not connected") after the status dot (`AppSettingsRow(supportingTone)`: Good while connected, Neutral otherwise); Filter chips how many each library shows ("8 in Kavita, 6 in Grimmory"); Source order the order ("Kavita, Grimmory, Local"). Nothing claims which source opens on launch: launch restores where you were.
- **Groups:** Appearance (Theme); Reading (Default look for new books, then Translation); Sources (one row per server, then Reading progress sync, which is about those servers); Library (one row, "Filters, tags and source order", opening a page with Filter chips, Tags and Source order); Storage; Highlights and backup; About (Katana and its version with Check for updates, Check for updates daily, Open-source licenses).
- **Default look for new books** says the look "Use these for new books" saved in a book's Aa panel ("Literata, 110%, Normal margins"), or "Not set: books open with the reader's own look". Its page lists the look setting by setting in the Aa panel's words (Font, Text size, Line height, Alignment, Margins, Paragraph spacing, Paragraph indent, Page turn) with a tonal **Reset**; with nothing saved, one line says how to save one.
- **Library** is one row whose line names the first source's filters and that it comes first ("14 filters in Grimmory · Grimmory first"). Filter chips, Tags and Source order open from its page, and Back from each returns to it.
- **Storage** is a panel, not rows: "Katana uses 1.5 GB" with "9.8 GB free" at its end, one bar split into Downloads (`primary`), Kept from opening (`tertiary`) and Covers (`secondary`) on `surface-container-highest`, a legend with each size, then tonal **Manage books** (the Downloads sheet), **Remove finished books** and **Clear covers**. Both removals ask first in an `AppDialog` with a destructive confirm.
- **Highlights and backup:** Export everything (every highlight, note and saved word as Markdown), Back up to a file (highlights, notes and saved words as JSON), Restore from a file ("Adds what the backup has; nothing here is deleted"). Each opens Android's file picker; a toast says what was saved or added.

## A server's page

Connected, top to bottom:

- **Server.** One row: the address as the title, "account · backup address" under it, then the `AppStatusPill` (Connected, Offline, Checking) and a tonal Edit that opens the form in place (Done closes it). A connection or sync error shows as one `error` line above the group. There is no second Server group at the bottom.
- **Library.** Sync: "Synced today, 09:12" (the step and an `AppProgressBar` while it runs), a filled **Sync now**, and on Kavita a ••• with Fast sync. Cached covers: its line, a tonal **Preload** (Cancel while it runs), and a ••• with Remove cached covers. One button per row; the destructive one only in •••.
- **Failed covers** are one row under Cached covers, `error` icon: "3 covers didn't download", the first two titles "and 1 more", and a text **Retry**. No raw error lines.
- **Reading defaults.** One row per library: Left to right / Right to left, then Paged / Scroll (`AppSegmentedRow`s beside the name from 640 wide, stacked under it below). One line under the group, flush with the panel's edge like the group label: "Comics in each library open this way, unless you picked something else for a book or a series in the reader."

- **Sign out**, last: one row, "Removes Grimmory from this tablet. You can add it again here.", with a tonal Sign out. It asks first (`AppDialog`, destructive confirm): the server's address and sign-in are forgotten and it leaves the side menu; reading places, highlights and the books kept on the tablet stay.

Not connected, the page is the sign-in form alone.

## The Downloads sheet

- **On this tablet** comes first: every server book whose file is here, downloaded or kept from opening, the latest read first. One line under the heading says why a book you opened is here ("Books you opened are kept so they read offline, the same as a download."). Each `AppBookRow`: cover, title, "2.1 MB · opened today · 9% read", and a Delete icon button that removes the file at once. A row opens the book's page.
- Then Downloading, Waiting, Couldn't download and Finished as before; with nothing in the queue, a Downloading heading with "Nothing waiting. Long-press covers in the library to download several at once."
- **The footer**, under an `AppSheetDivider`: "Books on this tablet" and their size, an `AppProgressBar` of their share of the space, and tonal **Remove finished books** and **Remove all**, each asked first.
- The header's line says what downloads, else "3 books on this tablet · 8.5 MB". Empty, the sheet says "Nothing on this tablet" and how books get here.

## Reading progress sync

What waits to upload, and why. With nothing waiting, one row says "Everything is uploaded".

- **Waiting uploads.** An `AppStatusPill` with how many and since when (`error-container` once one failed), "Last tried …", then Retry now, the page's one filled button. Then a group per server, a book's uploads of one kind on one row, "Waiting" or "Failed" at the end, and the server's last error under the group.
- **Books no longer in your library.** An upload whose book is no longer in the app's copy of its server's library can't go anywhere: the server removed the book, so no retry helps. Those leave the count and the server groups for their own group, last on the page, "No longer in your library": one row per book, "Grimmory · Progress, 2 reading sessions", nothing at the end. Under it one line says why, and a tonal **Keep on this device** stops the uploads and keeps everything: the journal keeps the sessions and the entry, the progress stays, and the app stops trying. Nothing is deleted, so it needs no confirm. A server whose library the app holds no books from (signed out, never synced) proves nothing, so its uploads stay waiting.
- **The hub row**, last in Sources, says what is waiting ("1 item waiting to upload", in `error` once one failed); with only gone books, "1 book no longer in your library"; otherwise "Up to date".
