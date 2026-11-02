package dev.owner.ownlog.domain

data class PageMeta(
    val title: String?,
    val ogTitle: String?,
    val ogDescription: String?,
)

private val TITLE_TAG = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val META_TAG = Regex("""<meta\s+([^>]+)>""", RegexOption.IGNORE_CASE)
private val META_ATTR = Regex("""([A-Za-z_:][-A-Za-z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
private val DECIMAL_ENTITY = Regex("""&#(\d+);""")
private val HEX_ENTITY = Regex("""&#x([0-9a-fA-F]+);""")

fun parsePageMeta(html: String): PageMeta {
    val sliced = html.take(300_000)
    val title = TITLE_TAG.find(sliced)?.groupValues?.getOrNull(1)?.let(::cleanMeta)?.ifBlank { null }
    var ogTitle: String? = null
    var ogDescription: String? = null
    var description: String? = null
    for (tag in META_TAG.findAll(sliced)) {
        val attrs = linkedMapOf<String, String>()
        for (attr in META_ATTR.findAll(tag.groupValues[1])) {
            val key = attr.groupValues[1].lowercase()
            val value = attr.groupValues[2].ifEmpty { attr.groupValues[3] }
            attrs.putIfAbsent(key, value)
        }
        val name = attrs["property"] ?: attrs["name"] ?: continue
        val content = attrs["content"]?.let(::cleanMeta)?.ifBlank { null } ?: continue
        when (name.lowercase()) {
            "og:title" -> if (ogTitle == null) ogTitle = content
            "og:description" -> if (ogDescription == null) ogDescription = content
            "description" -> if (description == null) description = content
        }
    }
    return PageMeta(title = title, ogTitle = ogTitle, ogDescription = ogDescription ?: description)
}

fun extrasWithPageMeta(extrasJson: String?, meta: PageMeta): String {
    val updates = linkedMapOf<String, String?>()
    val ogTitle = meta.ogTitle ?: meta.title
    if (!ogTitle.isNullOrBlank()) updates["og_title"] = ogTitle
    if (!meta.ogDescription.isNullOrBlank()) updates["og_description"] = meta.ogDescription
    if (updates.isEmpty()) return extrasJson?.trim().takeUnless { it.isNullOrBlank() } ?: "{}"
    return mergeJson(extrasJson, updates)
}

private fun cleanMeta(raw: String): String {
    val compact = raw.replace(Regex("""\s+"""), " ").trim()
    return unescapeBasic(compact)
}

private fun unescapeBasic(text: String): String {
    val named = HEX_ENTITY.replace(DECIMAL_ENTITY.replace(text) { match ->
        match.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: match.value
    }) { match ->
        match.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: match.value
    }
    return named
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
}
