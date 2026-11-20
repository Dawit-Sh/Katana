# Third-party notices

## Manrope (UI typeface)

Bundled as `app/src/main/res/font/manrope.ttf` (variable font, weight axis). Copyright 2018 The Manrope
Project Authors (https://github.com/googlefonts/manrope). Licensed under the SIL Open Font License,
Version 1.1; the full text follows.

## Material Symbols Rounded (UI icons)

The vector drawables in `core/ui-components/src/main/res/drawable/ic_*.xml` are converted from
https://github.com/google/material-design-icons (Apache License 2.0).

## DjVuLibre (DjVu pages)

`core/djvu/src/main/cpp/djvulibre/` is DjVuLibre 3.5.28's `libdjvu`
(https://sourceforge.net/projects/djvu/files/DjVuLibre/3.5.28/), built into `libkatana_djvu.so` to draw
DjVu pages. Copyright 1999-2001 LizardTech, Inc.; 2002-2014 Léon Bottou, Yann Le Cun and others (see
`COPYRIGHT` there). Licensed under the GNU General Public License, version 2 or (at your option) any later
version; the full text is `core/djvu/src/main/cpp/djvulibre/COPYING`.

**What this means for Katana.** An APK that contains DjVuLibre may only be shared under the GPL, so Katana
as a whole is released under the GNU General Public License, version 3 or (at your option) any later
version, with the agreement of yabai's author. The full text is `LICENSE` at the top of the repository, and
the complete source is at https://github.com/Dawit-Sh/Katana.

## PDFium and PdfiumAndroidKt (the text of PDF pages)

`feature:reader-pdf` uses PdfiumAndroidKt 1.0.35 (`io.legere:pdfiumandroid`,
https://github.com/johngray1965/PdfiumAndroidKt), Apache License 2.0, Copyright John Gray and contributors,
to read the words on a PDF page and where they sit, for selecting text and placing highlights (Android's
`PdfRenderer` still draws the pages). It bundles PDFium (https://pdfium.googlesource.com/pdfium/),
BSD 3-Clause License, Copyright 2014 The PDFium Authors, with its third-party parts under their own
permissive licences (FreeType under the FreeType License, libjpeg-turbo, OpenJPEG, libpng, Little CMS, zlib
and AGG; see PDFium's `LICENSE` and `third_party/`). The library also brings Guava
(https://github.com/google/guava), Apache License 2.0.

## jsoup (MOBI and AZW3 conversion)

`core:ebook-convert` uses jsoup 1.18.1 (https://jsoup.org), the copy Readium already brings. MIT License,
Copyright (c) 2009-2024 Jonathan Hedley.

## MOBI, AZW, AZW3 and FB2 conversion

`core:ebook-convert` is Katana's own code, written from the public descriptions of the formats (the
MobileRead wiki's MOBI and KF8 pages, the FictionBook 2 schema). It follows the approach of Librera Reader,
libmobi and KindleUnpack (converting to EPUB) without using their code. MuPDF is not used.

---

Copyright 2018 The Manrope Project Authors (https://github.com/googlefonts/manrope)

This Font Software is licensed under the SIL Open Font License, Version 1.1.
This license is copied below, and is also available with a FAQ at:
http://scripts.sil.org/OFL


-----------------------------------------------------------
SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007
-----------------------------------------------------------

PREAMBLE
The goals of the Open Font License (OFL) are to stimulate worldwide
development of collaborative font projects, to support the font creation
efforts of academic and linguistic communities, and to provide a free and
open framework in which fonts may be shared and improved in partnership
with others.

The OFL allows the licensed fonts to be used, studied, modified and
redistributed freely as long as they are not sold by themselves. The
fonts, including any derivative works, can be bundled, embedded, 
redistributed and/or sold with any software provided that any reserved
names are not used by derivative works. The fonts and derivatives,
however, cannot be released under any other type of license. The
requirement for fonts to remain under this license does not apply
to any document created using the fonts or their derivatives.

DEFINITIONS
"Font Software" refers to the set of files released by the Copyright
Holder(s) under this license and clearly marked as such. This may
include source files, build scripts and documentation.

"Reserved Font Name" refers to any names specified as such after the
copyright statement(s).

"Original Version" refers to the collection of Font Software components as
distributed by the Copyright Holder(s).

"Modified Version" refers to any derivative made by adding to, deleting,
or substituting -- in part or in whole -- any of the components of the
Original Version, by changing formats or by porting the Font Software to a
new environment.

"Author" refers to any designer, engineer, programmer, technical
writer or other person who contributed to the Font Software.

PERMISSION & CONDITIONS
Permission is hereby granted, free of charge, to any person obtaining
a copy of the Font Software, to use, study, copy, merge, embed, modify,
redistribute, and sell modified and unmodified copies of the Font
Software, subject to the following conditions:

1) Neither the Font Software nor any of its individual components,
in Original or Modified Versions, may be sold by itself.

2) Original or Modified Versions of the Font Software may be bundled,
redistributed and/or sold with any software, provided that each copy
contains the above copyright notice and this license. These can be
included either as stand-alone text files, human-readable headers or
in the appropriate machine-readable metadata fields within text or
binary files as long as those fields can be easily viewed by the user.

3) No Modified Version of the Font Software may use the Reserved Font
Name(s) unless explicit written permission is granted by the corresponding
Copyright Holder. This restriction only applies to the primary font name as
presented to the users.

4) The name(s) of the Copyright Holder(s) or the Author(s) of the Font
Software shall not be used to promote, endorse or advertise any
Modified Version, except to acknowledge the contribution(s) of the
Copyright Holder(s) and the Author(s) or with their explicit written
permission.

5) The Font Software, modified or unmodified, in part or in whole,
must be distributed entirely under this license, and must not be
distributed under any other license. The requirement for fonts to
remain under this license does not apply to any document created
using the Font Software.

TERMINATION
This license becomes null and void if any of the above conditions are
not met.

DISCLAIMER
THE FONT SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO ANY WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT
OF COPYRIGHT, PATENT, TRADEMARK, OR OTHER RIGHT. IN NO EVENT SHALL THE
COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
INCLUDING ANY GENERAL, SPECIAL, INDIRECT, INCIDENTAL, OR CONSEQUENTIAL
DAMAGES, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF THE USE OR INABILITY TO USE THE FONT SOFTWARE OR FROM
OTHER DEALINGS IN THE FONT SOFTWARE.
