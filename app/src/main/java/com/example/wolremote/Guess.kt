package com.example.wolremote

// Devine titre / année / saison / épisode depuis le nom d'un fichier ou d'une release.
object Guess {
    private val tags = Regex(
        "(?i)\\b(480p|576p|720p|1080p|1080i|2160p|4k|uhd|multi|vostfr|vff|vfq|vfi|vf|french|truefrench|subfrench|bluray|bdrip|brrip|web|webrip|web-dl|hdtv|dvdrip|x264|x265|h264|h265|hevc|hdr|remux)\\b"
    )
    private val year = Regex("(?<![0-9])(19|20)\\d{2}(?![0-9])")
    private val seasonTag = Regex("(?i)\\bS\\d{1,2}(\\s?E\\d{1,3})?\\b|\\b(saison|season)\\s?\\d{1,2}\\b")

    private fun clean(n: String): String {
        var s = n.replace('.', ' ').replace('_', ' ')
        s = s.replace(Regex("^\\s*\\[[^\\]]*\\]\\s*"), "")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    private fun cutTags(t: String): String {
        val m = tags.find(t)
        return if (m != null && m.range.first > 0) t.substring(0, m.range.first) else t
    }

    private fun tidy(t: String): String = t.replace(Regex("[\\(\\[\\-\\s]+$"), "").trim()

    fun movieTerm(name: String): String {
        val s = clean(name)
        val ys = year.findAll(s).toList()
        val y = ys.lastOrNull { it.range.first > 0 } ?: ys.firstOrNull()
        var title = if (y != null && y.range.first > 0) s.substring(0, y.range.first) else s
        title = tidy(cutTags(title))
        if (title.isEmpty()) title = tidy(cutTags(s))
        return if (y != null && y.range.first > 0) "$title ${y.value}".trim() else title
    }

    fun seriesTerm(name: String): String {
        val s = clean(name)
        val cut = seasonTag.find(s)
        var title = if (cut != null && cut.range.first > 0) s.substring(0, cut.range.first) else s
        val y = year.find(title)
        if (y != null && y.range.first > 0) title = title.substring(0, y.range.first)
        return tidy(cutTags(title))
    }

    // Renvoie (saison, épisode) ; épisode vide = saison complète
    fun seasonEpisode(name: String): Pair<String, String> {
        val s = clean(name)
        val se = Regex("(?i)\\bS(\\d{1,2})\\s?E(\\d{1,3})\\b").find(s)
        if (se != null) return Pair(se.groupValues[1].toInt().toString(), se.groupValues[2].toInt().toString())
        val so = Regex("(?i)\\bS(\\d{1,2})\\b").find(s)
        if (so != null) return Pair(so.groupValues[1].toInt().toString(), "")
        val sa = Regex("(?i)\\b(?:saison|season)\\s?(\\d{1,2})\\b").find(s)
        if (sa != null) return Pair(sa.groupValues[1].toInt().toString(), "")
        return Pair("1", "")
    }
}
