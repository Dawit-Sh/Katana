# AppListCover

A list's cover: its first four books' covers in a 2×2 mosaic, at a cover's 2:3 and `radius-cover`, 2 apart. A list reads apart from a book (one cover) and a series (one cover with a stack behind it).

- Kit: `AppListCover(count, cell = { index, modifier -> ... })`; the caller draws each cover (the library's cover composable) filling the modifier it gets.
- Four books or more make the mosaic. One to three books show the first book's cover, whole: a mosaic with empty cells reads as broken. An empty list is a dashed outline of the cover (2 wide, 6 on 6 off, `on-surface` at 20%), not a blank card: a list you fill by hand shows a 26 `add` over "Add books" (13 SemiBold, `on-surface-variant`) and a tap on its tile opens the library to pick its books; any other empty list shows the list mark (`checklist`, 32); a row's 40 cover is the outline alone.
- Used for list tiles on the Lists screen (the grid's cover width), `AppListRow` (40) and the list page's header (132).
- A list that updates itself (Stalled) adds a 32 round badge with the `tune` icon at its top end, on `surface-container-high`.
