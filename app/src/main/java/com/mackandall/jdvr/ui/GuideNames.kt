package com.mackandall.jdvr.ui

/**
 * Shorter programme names for narrow guide cells (the guide's wider spans). A cell uses the first that fits;
 * the details band always shows the full title.
 */
object GuideNames {
    // Whole words or phrases, longest first so "Law & Order: Special Victims Unit" takes both.
    private val ABBREVIATIONS =
        listOf(
            "Special Victims Unit" to "SVU",
            "Criminal Intent" to "CI",
            "Organized Crime" to "OC",
            "Law & Order" to "L&O",
            "Law and Order" to "L&O",
            "Saturday Night Live" to "SNL",
            "Entertainment Tonight" to "ET",
            "Everybody Loves Raymond" to "Raymond",
            "Two and a Half Men" to "2½ Men",
            "Aqua Teen Hunger Force" to "Aqua Teen",
            "Anthony Bourdain Parts Unknown" to "Bourdain",
            "Anthony Bourdain: Parts Unknown" to "Bourdain",
            "Late Night with Seth Meyers" to "Seth Meyers",
            "The Tonight Show Starring Jimmy Fallon" to "Fallon",
            "The Late Show with Stephen Colbert" to "Colbert",
            "Jimmy Kimmel Live!" to "Kimmel",
            "Anderson Cooper 360" to "AC360",
            "Star Trek: The Next Generation" to "Star Trek: TNG",
            "Star Trek: Deep Space Nine" to "Star Trek: DS9",
            "Premier League" to "Prem",
            "College Football" to "CFB",
            "Football" to "Ftbl",
            "Basketball" to "Bball",
            "Paid Programming" to "Paid",
            "Good Morning America" to "GMA",
        )

    private val ARTICLE = Regex("^(The|A|An) ", RegexOption.IGNORE_CASE)

    // Local news: "KOCO 5 News at 6am Saturday" → "KOCO 5 News".
    private val NEWS_TIME = Regex(" (at )?\\d{1,2}(:\\d\\d)? ?(am|pm|a\\.m\\.|p\\.m\\.)(?![a-z]).*$", RegexOption.IGNORE_CASE)

    /** The full title, then shorter ones, each different from the one before. */
    fun tiers(title: String): List<String> {
        val out = mutableListOf(title)
        val abbreviated =
            ABBREVIATIONS
                .fold(title) { t, (long, short) -> t.replace(long, short, ignoreCase = true) }
                .let { if (it.contains("News", ignoreCase = true)) it.replace(NEWS_TIME, "") else it }
                .trim()
        out += abbreviated
        // Without a leading article, then without what follows a colon (unless that leaves too little).
        val noArticle = abbreviated.replace(ARTICLE, "").trim()
        out += noArticle
        val head = noArticle.substringBefore(": ").substringBefore(" - ").trim()
        if (head.length >= 3) out += head
        return out.filter { it.isNotEmpty() }.distinct()
    }

    /** The first name that [fits], else the shortest (cut with "…" by the cell). */
    fun pick(
        title: String,
        fits: (String) -> Boolean,
    ): String {
        val all = tiers(title)
        return all.firstOrNull(fits) ?: all.last()
    }
}
