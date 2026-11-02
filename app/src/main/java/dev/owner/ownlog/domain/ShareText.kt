package dev.owner.ownlog.domain

private val URL_IN_TEXT = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

private val TRAILING_URL_JUNK = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '>', '"', '\'', '，', '。', '；', '：', '！', '？', '、')

private val HOST_PACKAGES = listOf(
    "mp.weixin.qq.com" to "com.tencent.mm",
    "weixin.qq.com" to "com.tencent.mm",
    "wechat.com" to "com.tencent.mm",
    "v.douyin.com" to "com.ss.android.ugc.aweme",
    "douyin.com" to "com.ss.android.ugc.aweme",
    "iesdouyin.com" to "com.ss.android.ugc.aweme",
    "xhslink.com" to "com.xingin.xhs",
    "xiaohongshu.com" to "com.xingin.xhs",
    "xhs.cn" to "com.xingin.xhs",
    "b23.tv" to "tv.danmaku.bili",
    "bilibili.com" to "tv.danmaku.bili",
    "weibo.com" to "com.sina.weibo",
    "weibo.cn" to "com.sina.weibo",
    "zhihu.com" to "com.zhihu.android",
)

fun extractUrl(text: String?): String? {
    if (text.isNullOrBlank()) return null
    val raw = URL_IN_TEXT.find(text)?.value ?: return null
    return raw.trimEnd { it in TRAILING_URL_JUNK }.ifBlank { null }
}

fun hostOf(url: String?): String? {
    if (url.isNullOrBlank()) return null
    val host = try {
        java.net.URI(url.trim()).host
    } catch (_: Exception) {
        null
    } ?: return null
    return host.lowercase().removePrefix("www.").ifBlank { null }
}

fun packageForHost(host: String): String? {
    val normalized = host.lowercase().removePrefix("www.")
    return HOST_PACKAGES.firstOrNull { (suffix, _) ->
        normalized == suffix || normalized.endsWith(".$suffix")
    }?.second
}

fun inferSourcePackage(explicitPackage: String?, url: String?): String? {
    val explicit = explicitPackage?.trim()?.takeIf { it.contains('.') && !it.equals("android", ignoreCase = true) }
    if (explicit != null) return explicit
    val host = hostOf(url) ?: return null
    return packageForHost(host)
}

fun sourceLabel(packageName: String?): String = when (packageName) {
    null, "" -> "未识别来源"
    "com.tencent.mm" -> "微信"
    "com.ss.android.ugc.aweme" -> "抖音"
    "com.ss.android.ugc.aweme.lite" -> "抖音极速版"
    "com.xingin.xhs" -> "小红书"
    "tv.danmaku.bili" -> "哔哩哔哩"
    "com.sina.weibo" -> "微博"
    "com.zhihu.android" -> "知乎"
    else -> packageName
}

fun shareBody(subject: String?, text: String?): String? {
    val body = text?.trim().orEmpty()
    val heading = subject?.trim().orEmpty()
    val combined = when {
        heading.isEmpty() -> body
        body.isEmpty() -> heading
        body.contains(heading) -> body
        else -> "$heading\n$body"
    }
    return combined.ifBlank { null }
}

fun shareTitle(subject: String?, text: String?): String? {
    val heading = subject?.trim().orEmpty()
    if (heading.isNotEmpty()) return heading.take(80)
    val line = text?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: return null
    return line.take(80)
}

fun isImageMime(mime: String?): Boolean {
    return mime?.substringBefore(';')?.trim()?.lowercase()?.startsWith("image/") == true
}
