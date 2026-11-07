package io.github.taehunkim.jogakbo.docs

/**
 * Nunsori renders a preview card per Office unit; notes need no previews, so only the kind of document survives here
 * (kept so [OfficeParsers] stays identical to Nunsori's copy).
 */
object TextCard {
    enum class Style { SLIDE, DOCUMENT, SHEET, OPEN_DOCUMENT }
}
