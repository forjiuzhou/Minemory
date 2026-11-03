package dev.owner.ownlog.domain

data class EngagementHit(
    val action: String,
    val actionLabel: String,
    val title: String?,
    val content: String,
    val author: String?,
    val url: String?,
    val screenHint: String?,
    val viewId: String?,
    val control: String,
    val matched: String,
    val privateContext: Boolean,
)

/**
 * A like, favorite, comment, or share tap.
 * Package-specific view ids cover the apps Jia uses; any other app falls back
 * to the short label on the control (text or contentDescription).
 * Password fields never match. Private chats keep the action and drop the thread.
 */
object Engagement {
    const val TYPE = "engagement"
    const val LIKE = "like"
    const val FAVORITE = "favorite"
    const val COMMENT = "comment"
    const val SHARE = "share"
    const val DEDUPE_MS = 1_000L
    const val MATCHED_VIEW_ID = "view_id"
    const val MATCHED_LABEL = "label"

    const val LABEL_LIKE = "赞"
    const val LABEL_FAVORITE = "收藏"
    const val LABEL_COMMENT = "评论"
    const val LABEL_SHARE = "分享"
    const val LABEL_REPOST = "转发"

    private val DOUYIN = setOf(
        "com.ss.android.ugc.aweme",
        "com.ss.android.ugc.aweme.lite",
    )
    private val BILI = setOf(
        "tv.danmaku.bili",
        "com.bilibili.app.in",
        "tv.danmaku.bilibilihd",
    )
    private val STAR_PACKAGES = setOf(FeedScreen.XHS, FeedScreen.WECHAT) + DOUYIN + BILI

    private val LIKE_GLOBAL = setOf("like", "liked", "likes", "unlike", "unliked", "praise", "thumb", "thumbs")
    private val LIKE_DOUYIN = setOf("digg", "undigg")
    private val LIKE_WECHAT = setOf("heart")
    private val FAV_GLOBAL = setOf(
        "fav", "favorite", "favourite", "favorited", "favourited",
        "unfavorite", "unfavourite", "collect", "collected", "bookmark", "bookmarked",
    )
    private val FAV_STAR = setOf("star", "starred")
    private val COMMENT_TOKENS = setOf("comment", "comments", "cmt", "reply", "replies")
    private val SHARE_TOKENS = setOf("share", "shared", "forward", "repost", "reposts", "relay")

    private val CHROME_ID = setOf("tab", "tabs", "navigation", "navbar", "nav", "menuitem", "indicator", "pager")
    private val BUTTON_TOKENS = setOf("btn", "button", "icon", "iv", "img", "image", "layout", "click", "action")
    private val CONTAINER_TOKENS = setOf(
        "item", "list", "content", "text", "body", "title", "desc", "description",
        "recycler", "adapter", "holder", "cell", "row", "feed", "avatar", "name",
        "nick", "author", "message", "bubble", "note",
    )

    private val CHROME_LINES = setOf(
        "关注", "已关注", "+关注", "点赞", "评论", "收藏", "分享", "转发", "推荐",
        "首页", "消息", "我", "搜索", "直播", "发现", "购物", "视频号", "朋友",
        "通讯录", "设置", "赞", "发送", "说点什么", "写评论", "去评论", "关注作者",
        "不感兴趣", "私信", "更多", "返回", "喜欢", "商城", "同城", "笔记",
        "like", "liked", "favorite", "bookmark", "comment", "share", "repost",
        "微信", "wechat",
    )

    private val CHAT_MARKERS = listOf(
        "chatting", "mm_chat", "chat_input", "chatting_content",
        "conversation_input", "message_edit", "im_input", "chat_footer",
        "chat_message", "message_list",
    )

    private val TRAILING_COUNT = Regex("""(?:\s*\d+(?:[.,]\d+)?\s*[万亿千wWkK]?\s*条?)+$""")
    private val STATE_TAIL = Regex(
        """^(?:按钮|buttons?|[\d.]+|[wWkK]+|[\s已未取消点赞喜欢收藏评论分享转发人次万千下过])+$""",
        RegexOption.IGNORE_CASE,
    )

    private data class LabelRule(val action: String, val label: String, val pattern: Regex)

    private val LABEL_RULES = listOf(
        LabelRule(LIKE, LABEL_LIKE, Regex("""^(?:取消|未|已)*点?赞$""")),
        LabelRule(LIKE, LABEL_LIKE, Regex("""^(?:取消|未|已)*喜欢$""")),
        LabelRule(LIKE, LABEL_LIKE, Regex("""^(?:un)?likes?$|^(?:un)?liked$""", RegexOption.IGNORE_CASE)),
        LabelRule(FAVORITE, LABEL_FAVORITE, Regex("""^(?:取消|未|已)*收藏$""")),
        LabelRule(
            FAVORITE,
            LABEL_FAVORITE,
            Regex("""^(?:un)?favou?rites?$|^(?:un)?favou?rited$|^bookmarks?$|^bookmarked$""", RegexOption.IGNORE_CASE),
        ),
        LabelRule(COMMENT, LABEL_COMMENT, Regex("""^(?:写|去)?评论$""")),
        LabelRule(COMMENT, LABEL_COMMENT, Regex("""^comments?$""", RegexOption.IGNORE_CASE)),
        LabelRule(SHARE, LABEL_REPOST, Regex("""^转发$|^reposts?$""", RegexOption.IGNORE_CASE)),
        LabelRule(SHARE, LABEL_SHARE, Regex("""^(?:取消|已)?分享$|^shares?$|^shared$""", RegexOption.IGNORE_CASE)),
    )

    private data class LabelHit(val action: String, val label: String, val control: String)

    fun detect(
        packageName: String,
        snap: ClickSnap,
        windowTitle: String?,
        nodes: List<TextNode>,
    ): EngagementHit? {
        if (PasswordSignals.isPassword(snap.isPassword, snap.inputType)) return null
        if (isTabClass(snap.className)) return null
        if (isChromeId(snap.viewId)) return null
        if (snap.viewId.isNullOrBlank() && isChromeId(snap.parentViewId)) return null

        val selfAction = actionFromId(packageName, snap.viewId)
        val parentAction = if (selfAction == null && !isChromeId(snap.parentViewId)) {
            actionFromId(packageName, snap.parentViewId)
        } else {
            null
        }
        val labelHit = firstLabel(snap)
        val action = selfAction ?: parentAction ?: labelHit?.action ?: return null
        val actionLabel = when (action) {
            LIKE -> LABEL_LIKE
            FAVORITE -> LABEL_FAVORITE
            COMMENT -> LABEL_COMMENT
            SHARE -> if (labelHit?.label == LABEL_REPOST) LABEL_REPOST else LABEL_SHARE
            else -> return null
        }
        val control = labelHit?.control ?: shortControl(snap) ?: actionLabel
        val matched = if (selfAction != null || parentAction != null) MATCHED_VIEW_ID else MATCHED_LABEL
        val viewId = when {
            selfAction != null -> snap.viewId
            parentAction != null -> snap.parentViewId
            else -> snap.viewId ?: snap.parentViewId
        }
        val visible = nodes.filterNot { PasswordSignals.isPassword(it.isPassword, it.inputType) }
        return buildHit(
            packageName = packageName,
            windowTitle = windowTitle,
            nodes = visible,
            action = action,
            actionLabel = actionLabel,
            control = control,
            matched = matched,
            viewId = viewId,
        )
    }

    fun extrasJson(hit: EngagementHit): String {
        return JsonObj()
            .str("action", hit.action)
            .str("action_label", hit.actionLabel)
            .str("author", hit.author)
            .str("url", hit.url)
            .str("screen_hint", hit.screenHint)
            .str("view_id", hit.viewId)
            .str("control", hit.control)
            .str("matched", hit.matched)
            .bool("private_context", hit.privateContext)
            .build()
    }

    fun dedupeTarget(hit: EngagementHit): String {
        return listOf(hit.title.orEmpty(), hit.viewId.orEmpty(), hit.control).joinToString("|").take(200)
    }

    private fun buildHit(
        packageName: String,
        windowTitle: String?,
        nodes: List<TextNode>,
        action: String,
        actionLabel: String,
        control: String,
        matched: String,
        viewId: String?,
    ): EngagementHit {
        val privateContext = isPrivateContext(packageName, nodes, windowTitle)
        val consolidated = if (privateContext) null else FeedScreen.consolidate(packageName, nodes, windowTitle)
        val author = if (privateContext) {
            counterpart(windowTitle)
        } else {
            consolidated?.author ?: authorFrom(nodes)
        }
        val title = if (privateContext) {
            author
        } else {
            consolidated?.title ?: plainTitle(windowTitle, nodes, author, control)
        }
        val snippet = if (privateContext) {
            null
        } else {
            val described = consolidated?.description?.take(160)
            described ?: snippetFrom(nodes, title, author, control)
        }
        val url = if (privateContext) null else firstUrl(nodes, windowTitle)
        val line = actionLine(actionLabel, control)
        val content = if (privateContext) {
            line
        } else {
            buildList {
                add(line)
                if (!title.isNullOrBlank() && title != line) add(title.take(80))
                if (!author.isNullOrBlank() && author != title) add(author)
                if (!snippet.isNullOrBlank() && snippet != title && snippet != author) add(snippet.take(160))
            }.joinToString("\n").take(400)
        }
        val eventTitle = when {
            privateContext -> author ?: actionLabel
            !title.isNullOrBlank() -> title
            else -> actionLabel
        }.take(80)
        return EngagementHit(
            action = action,
            actionLabel = actionLabel,
            title = eventTitle,
            content = content,
            author = author?.take(40),
            url = url?.take(300),
            screenHint = consolidated?.screenHint,
            viewId = viewId,
            control = control.take(80),
            matched = matched,
            privateContext = privateContext,
        )
    }

    private fun actionFromId(packageName: String, viewId: String?): String? {
        val tokens = viewIdTokens(viewId)
        if (tokens.isEmpty() || tokens.any { it in CHROME_ID }) return null
        val action = matchTokens(packageName, tokens) ?: return null
        val buttonish = tokens.any { it in BUTTON_TOKENS } || isActionToken(packageName, tokens.last())
        if (tokens.any { it in CONTAINER_TOKENS } && !buttonish) return null
        return action
    }

    private fun matchTokens(packageName: String, tokens: List<String>): String? {
        fun has(words: Set<String>) = tokens.any { it in words }
        if (has(LIKE_GLOBAL) ||
            (packageName in DOUYIN && has(LIKE_DOUYIN)) ||
            (packageName == FeedScreen.WECHAT && has(LIKE_WECHAT))
        ) {
            return LIKE
        }
        if (has(FAV_GLOBAL) || (packageName in STAR_PACKAGES && has(FAV_STAR))) return FAVORITE
        if (has(COMMENT_TOKENS)) return COMMENT
        if (has(SHARE_TOKENS)) return SHARE
        return null
    }

    private fun isActionToken(packageName: String, token: String): Boolean {
        if (token in LIKE_GLOBAL || token in FAV_GLOBAL || token in COMMENT_TOKENS || token in SHARE_TOKENS) {
            return true
        }
        if (packageName in DOUYIN && token in LIKE_DOUYIN) return true
        if (packageName == FeedScreen.WECHAT && token in LIKE_WECHAT) return true
        if (packageName in STAR_PACKAGES && token in FAV_STAR) return true
        return false
    }

    private fun firstLabel(snap: ClickSnap): LabelHit? {
        val raws = listOf(snap.contentDescription, snap.text, snap.parentContentDescription, snap.parentText)
        for (raw in raws) {
            val control = normalizeControl(raw) ?: continue
            val exact = matchExact(control)
            if (exact != null) return LabelHit(exact.first, exact.second, control)
            val head = control.substringBefore(' ').trim()
            val tail = control.substringAfter(' ', "").trim()
            if (head.isNotEmpty() && tail.isNotEmpty() && STATE_TAIL.matches(tail)) {
                val headHit = matchExact(head) ?: continue
                return LabelHit(headHit.first, headHit.second, control)
            }
        }
        return null
    }

    private fun matchExact(control: String): Pair<String, String>? {
        for (rule in LABEL_RULES) {
            if (rule.pattern.matches(control)) return rule.action to rule.label
        }
        return null
    }

    private fun normalizeControl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var text = raw.replace('\u00a0', ' ').trim()
        if (text.isEmpty()) return null
        text = text.replace('，', ' ').replace(',', ' ').replace('、', ' ')
        text = text.replace("按钮", " ")
        text = text.replace(Regex("""\s+"""), " ").trim()
        text = text.replace(Regex("""(?i)\s+buttons?$"""), "").trim()
        text = TRAILING_COUNT.replace(text, "").trim()
        if (text.isEmpty() || text.length > 24) return null
        return text
    }

    private fun shortControl(snap: ClickSnap): String? {
        val raws = listOf(snap.contentDescription, snap.text, snap.parentContentDescription, snap.parentText)
        for (raw in raws) {
            val control = normalizeControl(raw) ?: continue
            return control
        }
        return null
    }

    private fun isPrivateContext(packageName: String, nodes: List<TextNode>, windowTitle: String?): Boolean {
        val ids = nodes.joinToString(" ") { it.viewId?.lowercase().orEmpty() }
        if (CHAT_MARKERS.none { it in ids }) return false
        if (packageName == FeedScreen.WECHAT) {
            if ("finder" in ids) return false
            if (FeedScreen.consolidate(packageName, nodes, windowTitle)?.screenHint == FeedScreen.HINT_WECHAT_CHANNELS) {
                return false
            }
        }
        return true
    }

    private fun counterpart(windowTitle: String?): String? {
        val line = windowTitle?.let(ScreenText::normalizeLine) ?: return null
        if (line.length !in 1..40 || isChromeLine(line)) return null
        return line
    }

    private fun authorFrom(nodes: List<TextNode>): String? {
        for (node in nodes) {
            val id = node.viewId?.substringAfterLast('/')?.lowercase().orEmpty()
            if (!id.hasAny("nick", "author", "username", "user_name", "finder_name")) continue
            val text = ScreenText.normalizeLine(node.text ?: node.contentDescription ?: continue) ?: continue
            if (text.length in 1..40 && !isChromeLine(text)) return text
        }
        return null
    }

    private fun plainTitle(
        windowTitle: String?,
        nodes: List<TextNode>,
        author: String?,
        control: String,
    ): String? {
        val window = windowTitle?.let(ScreenText::normalizeLine)
        if (window != null && usefulLine(window, author, control) && window.length <= 80) return window
        return readableLines(nodes).firstOrNull { usefulLine(it, author, control) && it.length in 4..80 }
    }

    private fun snippetFrom(nodes: List<TextNode>, title: String?, author: String?, control: String): String? {
        return readableLines(nodes)
            .filter { it.length in 12..180 && it != title && usefulLine(it, author, control) }
            .maxByOrNull { it.length }
            ?.take(160)
    }

    private fun readableLines(nodes: List<TextNode>): List<String> {
        val out = LinkedHashSet<String>()
        for (node in nodes) {
            for (raw in listOfNotNull(node.text, node.contentDescription)) {
                val line = ScreenText.normalizeLine(raw) ?: continue
                out.add(line)
            }
        }
        return out.toList()
    }

    private fun usefulLine(line: String, author: String?, control: String): Boolean {
        if (isChromeLine(line)) return false
        if (!author.isNullOrBlank() && line.equals(author, ignoreCase = true)) return false
        if (line.equals(control, ignoreCase = true)) return false
        val head = line.substringBefore(' ')
        if (head.equals(control.substringBefore(' '), ignoreCase = true) && line.length <= control.length + 8) {
            return false
        }
        if (line.startsWith("http://") || line.startsWith("https://")) return false
        return true
    }

    private fun firstUrl(nodes: List<TextNode>, windowTitle: String?): String? {
        val chunks = ArrayList<String>()
        if (!windowTitle.isNullOrBlank()) chunks.add(windowTitle)
        for (node in nodes) {
            node.text?.let(chunks::add)
            node.contentDescription?.let(chunks::add)
        }
        for (chunk in chunks) {
            val url = extractUrl(chunk) ?: continue
            if (url.length in 8..300) return url
        }
        return null
    }

    private fun actionLine(actionLabel: String, control: String): String {
        val shown = control.substringBefore(' ').trim().ifBlank { control }
        if (shown.isBlank() || shown == actionLabel || actionLabel in shown) return actionLabel
        return "$actionLabel · ${shown.take(40)}"
    }

    private fun isChromeLine(line: String): Boolean {
        return line in CHROME_LINES || line.lowercase() in CHROME_LINES
    }

    private fun isChromeId(viewId: String?): Boolean {
        return viewIdTokens(viewId).any { it in CHROME_ID }
    }

    private fun isTabClass(className: String?): Boolean {
        val name = className?.lowercase().orEmpty()
        return "tablayout" in name ||
            "bottomnavigation" in name ||
            "navigationbar" in name ||
            "tabview" in name
    }

    private fun viewIdTokens(viewId: String?): List<String> {
        val leaf = viewId?.substringAfterLast('/')?.trim().orEmpty()
        if (leaf.isEmpty()) return emptyList()
        val snake = leaf
            .replace(Regex("""([a-z0-9])([A-Z])"""), "$1_$2")
            .replace(Regex("""([A-Z]+)([A-Z][a-z])"""), "$1_$2")
        return snake.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
    }

    private fun String.hasAny(vararg parts: String): Boolean = parts.any { it in this }
}

class EngagementDedupe(private val windowMs: Long = Engagement.DEDUPE_MS) {
    private var lastKey: String? = null
    private var lastAt: Long = Long.MIN_VALUE

    fun shouldRecord(packageName: String, action: String, target: String, now: Long): Boolean {
        val key = "$packageName|$action|$target"
        if (lastKey == key && now - lastAt < windowMs) return false
        lastKey = key
        lastAt = now
        return true
    }
}
