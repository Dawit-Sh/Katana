# My words

Every saved word in one list, each with the sentence it was met in. Katana's design for it is the Katana Desk canvas (`my-words`, desktop, 20 Nov 2026); the phone version is built from the kit like the Bookmarks page, and the code is `feature:annotations` (`MyWordsScreen`, `MyWordsBar`, `MyWords.kt`).

- **In the menu** after Bookmarks, before Journal (`style`), with the number of saved words at the row's end (`label-large`, the row's own colour); on the rail too. Picking it always lands on the list, not a review left open.
- **No screen title.** The bar holds the menu button and, at its end, **Export** (`AppTonalButton`, `download`): the words of the book on the filter (every word under All books) as Markdown, each book's words under its title in reading order with their sentences and notes, the words part of a book's export. Saved where the reader picks as "My words 2026-11-20.md".
- **Top of the list**: "41 words from 3 books" ("2 words from Hamlet" under a book filter) with **Review · 6 due** (`AppButton`, `style`; "Review" when nothing is due) beside it, wrapping under it when the line is full. Review opens the flashcards (the Bookmarks page's review) over the words on the filter: Back, "Review saved words", "1 of 6 · from every book" ("from Hamlet"). Then an `AppSearchField` ("Search words, sentences, books and notes": the word, its sentence, its book's title and its note, matched as every mark search is, `MarkSearch`), then the book filter: an `AppChip` (`filter_list`, the book's title or "All books", `keyboard_arrow_down`) opening an `AppMenu` of All books and every book by title (one line) with its word count at the row's end, a check on the one shown.
- **The words**, the newest first, each an `AppPanel` (16 inside):
  - the word in the book face (Literata, `title-large`, SemiBold);
  - its meaning on one line (`body-small`, `on-surface-variant`), only when one is known: `WordMeanings` gives it (nothing does yet, so no word has the line);
  - where it was met: the sentence in Literata (`body-medium`, `on-surface-variant`, up to six lines, cut around the word so it always shows) with the word in bold `on-surface`;
  - its note in the note box, when it has one;
  - "A Christmas Carol · p. 11 · today, 11:40 AM" (`label-medium`; the page is the reader's page there, else how far through the book; the time follows the device's 12 or 24 hours).
  - Under 600 dp these stack. From 600 dp the word and its meaning take the start 42% and the rest sits beside them; a larger text size needs that much more room (at 200% the Fire stacks too).
- **A tap** opens the book at the sentence (as a highlight opens from Bookmarks); a word saved without a place opens the book where it was left; a book no longer in the library says so in a toast.
- **A long press** opens an `AppMenu`: **Look it up** (`search`, a web search for the word), **Edit note** (`edit`, the "What it means" dialog) and **Remove** (`delete`; the toast "Word removed" has **Undo**, which brings the word back exactly: its sentence, place, note and review state).
- **Empty**: "No saved words yet" with "While you read, select a word and pick Add to dictionary. It shows up here with the sentence you met it in." A search or filter that finds nothing says "No words match".
