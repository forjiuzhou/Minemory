package dev.owner.ownlog.domain

data class ConsolidatedUi(
    val screenHint: String,
    val title: String?,
    val author: String?,
    val description: String?,
    val content: String,
    val signature: String,
)

/**
 * One title / author / description record for a Xiaohongshu note or a WeChat
 * Channel, instead of a line per accessibility fragment.
 * Returns null for other screens so chats and feeds keep the normal path.
 */
object FeedScreen {
    const val XHS = "com.xingin.xhs"
    const val WECHAT = "com.tencent.mm"
    const val HINT_XHS_NOTE = "xhs_note"
    const val HINT_WECHAT_CHANNELS = "wechat_channels"
    const val QUIET_MS = 1_200L

    private val chrome = setOf(
        "关注", "已关注", "+关注", "点赞", "评论", "收藏", "分享", "转发", "推荐",
        "首页", "消息", "我", "搜索", "直播", "发现", "购物", "视频号", "朋友",
        "通讯录", "设置", "赞", "发送", "说点什么", "写评论", "去评论", "关注作者",
        "不感兴趣", "私信", "更多", "返回", "喜欢", "商城", "同城", "笔记",
    )

    fun consolidate(packageName: String, nodes: List<TextNode>, windowTitle: String?): ConsolidatedUi? {
        val hint = when (packageName) {
            XHS -> if (isXhsNote(nodes)) HINT_XHS_NOTE else null
            WECHAT -> if (isWeChatChannels(nodes, windowTitle)) HINT_WECHAT_CHANNELS else null
            else -> null
        } ?: return null
        val pieces = pieces(nodes)
        val author = pieces.firstOrNull { it.isAuthor() && it.text.length <= 40 }?.text
        val titled = pieces.firstOrNull { it.isTitle() && it.text != author && it.text.length <= 80 }?.text
        val window = windowTitle?.let(ScreenText::normalizeLine)
            ?.takeIf { it !in chrome && it != author && it.length <= 80 }
        val title = titled ?: window
        val described = pieces.filter { it.isBody() && it.text != author }.maxByOrNull { it.text.length }?.text
        val description = described ?: pieces
            .filter { it.text.length >= 12 && it.text != author && it.text != title }
            .maxByOrNull { it.text.length }
            ?.text
        val resolvedTitle = title?.takeIf { it != description }
        if (resolvedTitle.isNullOrBlank() && description.isNullOrBlank()) return null
        val lines = listOfNotNull(resolvedTitle, author, description).distinct()
        val content = lines.joinToString("\n")
        if (content.isBlank()) return null
        return ConsolidatedUi(
            screenHint = hint,
            title = resolvedTitle ?: author,
            author = author,
            description = description,
            content = content,
            signature = ScreenText.signatureOf(content),
        )
    }

    private fun isXhsNote(nodes: List<TextNode>): Boolean {
        val ids = idBlob(nodes)
        val noteId = listOf("notecontent", "notedesc", "note_content", "note_desc", "notetitle").any { it in ids }
        val longLines = meaningful(nodes).count { it.length >= 20 }
        if (noteId && meaningful(nodes).any { it.length >= 8 }) return true
        if (longLines == 1) return true
        if (longLines >= 4) return false
        val hinted = listOf("nickname", "nick", "author", "title", "desc", "content").any { it in ids }
        return hinted && longLines in 1..2
    }

    private fun isWeChatChannels(nodes: List<TextNode>, windowTitle: String?): Boolean {
        val ids = idBlob(nodes)
        val chat = listOf("chatting", "mm_chat", "chat_input", "chatting_content").any { it in ids }
        val finder = "finder" in ids
        if (finder) return true
        if (chat) return false
        if (windowTitle?.contains("视频号") == true && meaningful(nodes).any { it.length >= 8 }) return true
        val raw = nodes.flatMap { listOfNotNull(it.text, it.contentDescription) }
            .mapNotNull(ScreenText::normalizeLine)
        return raw.any { it == "视频号" } && meaningful(nodes).any { it.length >= 8 }
    }

    private fun pieces(nodes: List<TextNode>): List<Piece> {
        val out = ArrayList<Piece>()
        for (node in nodes) {
            val id = node.viewId?.substringAfterLast('/')?.lowercase().orEmpty()
            for (raw in listOfNotNull(node.text, node.contentDescription)) {
                val line = ScreenText.normalizeLine(raw) ?: continue
                if (line in chrome) continue
                out.add(Piece(id, line))
            }
        }
        return out
    }

    private fun meaningful(nodes: List<TextNode>): List<String> {
        return pieces(nodes).map { it.text }.distinct()
    }

    private fun idBlob(nodes: List<TextNode>): String {
        return nodes.joinToString(" ") { it.viewId?.lowercase().orEmpty() }
    }

    private data class Piece(val id: String, val text: String) {
        fun isAuthor(): Boolean {
            return id.hasAny("nick", "author", "username", "user_name", "finder_name")
        }

        fun isTitle(): Boolean = id.contains("title") && !id.contains("subtitle")

        fun isBody(): Boolean = id.hasAny("desc", "content", "note", "detail")
    }

    private fun String.hasAny(vararg parts: String): Boolean = parts.any { it in this }
}

/**
 * Holds the latest consolidated screen and emits it once the tree settles,
 * or immediately when the user moves to a different screen.
 */
class FeedCoalescer(private val quietMs: Long = FeedScreen.QUIET_MS) {
    data class Sample(val key: String, val signature: String, val at: Long)

    private var pending: Sample? = null
    private var lastKey: String? = null
    private var lastSignature: String? = null

    fun offer(sample: Sample): Sample? {
        val previous = pending
        pending = sample
        if (previous == null || previous.key == sample.key) return null
        if (previous.key == lastKey && previous.signature == lastSignature) return null
        lastKey = previous.key
        lastSignature = previous.signature
        return previous
    }

    fun takeReady(now: Long, force: Boolean = false): Sample? {
        val sample = pending ?: return null
        if (!force && now - sample.at < quietMs) return null
        pending = null
        if (sample.key == lastKey && sample.signature == lastSignature) return null
        lastKey = sample.key
        lastSignature = sample.signature
        return sample
    }
}
