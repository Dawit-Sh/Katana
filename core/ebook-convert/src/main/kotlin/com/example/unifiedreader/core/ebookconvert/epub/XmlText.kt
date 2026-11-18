package com.example.unifiedreader.core.ebookconvert.epub

/** [this] safe as XML text or a double-quoted attribute value; characters XML forbids are dropped. */
internal fun String.escapeXml(): String {
    val builder = StringBuilder(length + length / ESCAPE_GROWTH_DIVISOR)
    for (char in this) {
        when (char) {
            '&' -> builder.append("&amp;")
            '<' -> builder.append("&lt;")
            '>' -> builder.append("&gt;")
            '"' -> builder.append("&quot;")
            '\'' -> builder.append("&#39;")
            else -> if (char.isAllowedInXml()) builder.append(char)
        }
    }
    return builder.toString()
}

private fun Char.isAllowedInXml(): Boolean =
    this == '\t' || this == '\n' || this == '\r' || this >= ' ' && this != '￾' && this != '￿'

/** An XHTML document around [body], with the book's language and stylesheets. */
internal fun xhtmlDocument(
    title: String,
    language: String,
    stylesheets: List<String>,
    body: String,
    bodyClass: String? = null,
): String = buildString {
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE html>\n")
    append("<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\"")
    append(" lang=\"").append(language.escapeXml()).append("\" xml:lang=\"").append(language.escapeXml()).append("\">\n")
    append("<head>\n<meta charset=\"UTF-8\"/>\n<title>").append(title.escapeXml()).append("</title>\n")
    stylesheets.forEach { href ->
        append("<link rel=\"stylesheet\" type=\"text/css\" href=\"").append(href.escapeXml()).append("\"/>\n")
    }
    append("</head>\n<body")
    if (bodyClass != null) append(" class=\"").append(bodyClass.escapeXml()).append('"')
    append(">\n").append(body).append("\n</body>\n</html>\n")
}

private const val ESCAPE_GROWTH_DIVISOR = 8
