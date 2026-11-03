package dev.owner.ownlog

import dev.owner.ownlog.domain.ClickSnap
import dev.owner.ownlog.domain.Engagement
import dev.owner.ownlog.domain.EngagementDedupe
import dev.owner.ownlog.domain.PasswordSignals
import dev.owner.ownlog.domain.TextNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngagementTest {
    @Test
    fun xiaohongshuLikeKeepsTitleAuthorAndVisibleUrl() {
        val hit = Engagement.detect(
            packageName = "com.xingin.xhs",
            snap = ClickSnap(
                text = null,
                contentDescription = "点赞 1.2万",
                viewId = "com.xingin.xhs:id/likeLayout",
                className = "android.widget.ImageView",
                isPassword = false,
                inputType = 0,
            ),
            windowTitle = null,
            nodes = xhsNoteNodes(),
        )
        requireNotNull(hit)
        assertEquals(Engagement.LIKE, hit.action)
        assertEquals("赞", hit.actionLabel)
        assertEquals("周末去灵隐寺看桂花", hit.title)
        assertEquals("杭州阿九", hit.author)
        assertEquals("xhs_note", hit.screenHint)
        assertEquals("https://www.xiaohongshu.com/discovery/item/abc123", hit.url)
        assertTrue(hit.content.startsWith("赞"))
        assertTrue(hit.content.contains("周末去灵隐寺看桂花"))
        assertTrue(hit.content.contains("杭州阿九"))
        assertTrue(hit.content.contains("防滑"))
        assertFalse(hit.content.contains("hunter2"))
        assertFalse(hit.privateContext)
        val extras = Engagement.extrasJson(hit)
        assertTrue(extras.contains("\"action\":\"like\""))
        assertTrue(extras.contains("\"author\":\"杭州阿九\""))
        assertTrue(extras.contains("\"url\":\"https://www.xiaohongshu.com/discovery/item/abc123\""))
        assertTrue(extras.contains("\"screen_hint\":\"xhs_note\""))
    }

    @Test
    fun xiaohongshuFavoriteMatchesCollectButtonEvenWhenTheLabelIsACount() {
        val hit = Engagement.detect(
            packageName = "com.xingin.xhs",
            snap = ClickSnap(
                text = "1.2万",
                contentDescription = null,
                viewId = "com.xingin.xhs:id/collect_btn",
                className = "android.widget.TextView",
                isPassword = false,
                inputType = 0,
            ),
            windowTitle = null,
            nodes = xhsNoteNodes(),
        )
        requireNotNull(hit)
        assertEquals(Engagement.FAVORITE, hit.action)
        assertEquals("收藏", hit.actionLabel)
        assertEquals(Engagement.MATCHED_VIEW_ID, hit.matched)
        assertEquals("周末去灵隐寺看桂花", hit.title)
        assertTrue(hit.content.contains("收藏"))
    }

    @Test
    fun douyinDiggOnTheParentAndBilibiliLikeLabel() {
        val digg = Engagement.detect(
            packageName = "com.ss.android.ugc.aweme",
            snap = ClickSnap(
                text = "128",
                contentDescription = null,
                viewId = "com.ss.android.ugc.aweme:id/count_tv",
                className = "android.widget.TextView",
                isPassword = false,
                inputType = 0,
                parentViewId = "com.ss.android.ugc.aweme:id/digg",
                parentContentDescription = "点赞",
            ),
            windowTitle = "桂花",
            nodes = emptyList(),
        )
        requireNotNull(digg)
        assertEquals(Engagement.LIKE, digg.action)
        assertEquals(Engagement.MATCHED_VIEW_ID, digg.matched)
        assertEquals("com.ss.android.ugc.aweme:id/digg", digg.viewId)

        val bili = Engagement.detect(
            packageName = "tv.danmaku.bili",
            snap = ClickSnap("点赞", null, null, "android.widget.TextView", false, 0),
            windowTitle = "桂花巷",
            nodes = emptyList(),
        )
        requireNotNull(bili)
        assertEquals(Engagement.LIKE, bili.action)
        assertEquals(Engagement.MATCHED_LABEL, bili.matched)
        assertEquals("桂花巷", bili.title)
    }

    @Test
    fun genericBookmarkAndCommentCountAndRepost() {
        val bookmark = Engagement.detect(
            packageName = "com.example.reader",
            snap = ClickSnap(null, "Bookmark", "com.example.reader:id/icon", "android.widget.ImageView", false, 0),
            windowTitle = "一篇很长的标题",
            nodes = emptyList(),
        )
        requireNotNull(bookmark)
        assertEquals(Engagement.FAVORITE, bookmark.action)
        assertEquals(Engagement.MATCHED_LABEL, bookmark.matched)
        assertTrue(bookmark.content.contains("收藏"))
        assertTrue(bookmark.content.contains("Bookmark"))

        val comment = Engagement.detect(
            packageName = "com.example.reader",
            snap = ClickSnap("评论 23", null, null, "android.widget.Button", false, 0),
            windowTitle = null,
            nodes = emptyList(),
        )
        requireNotNull(comment)
        assertEquals(Engagement.COMMENT, comment.action)

        val repost = Engagement.detect(
            packageName = "com.example.reader",
            snap = ClickSnap(null, "转发", null, "android.widget.Button", false, 0),
            windowTitle = null,
            nodes = emptyList(),
        )
        requireNotNull(repost)
        assertEquals(Engagement.SHARE, repost.action)
        assertEquals("转发", repost.actionLabel)
        assertTrue(repost.content.startsWith("转发"))
    }

    @Test
    fun wechatChannelLikeKeepsTheVideoAndChatFavoriteDropsTheThread() {
        val channel = Engagement.detect(
            packageName = "com.tencent.mm",
            snap = ClickSnap("喜欢", "喜欢", "com.tencent.mm:id/finder_like", "android.widget.ImageView", false, 0),
            windowTitle = "视频号",
            nodes = listOf(
                TextNode("视频号", null, "com.tencent.mm:id/finder_tab", false, 0),
                TextNode("桂花巷口的雨", null, "com.tencent.mm:id/finder_title", false, 0),
                TextNode("阿九", null, "com.tencent.mm:id/finder_name", false, 0),
                TextNode("雨后的石头路很滑，记得穿防滑的鞋。", null, "com.tencent.mm:id/finder_desc", false, 0),
            ),
        )
        requireNotNull(channel)
        assertEquals(Engagement.LIKE, channel.action)
        assertEquals("wechat_channels", channel.screenHint)
        assertFalse(channel.privateContext)
        assertTrue(channel.content.contains("赞"))
        assertTrue(channel.content.contains("喜欢"))
        assertTrue(channel.content.contains("桂花巷口的雨"))
        assertEquals("阿九", channel.author)

        val secret = "账号密码是 hunter2 别告诉别人"
        val chat = Engagement.detect(
            packageName = "com.tencent.mm",
            snap = ClickSnap("收藏", "收藏", "com.tencent.mm:id/fav_btn", "android.widget.Button", false, 0),
            windowTitle = "张三",
            nodes = listOf(
                TextNode(secret, null, "com.tencent.mm:id/chatting_content_tv", false, 0),
                TextNode("https://secret.example/dm", null, "com.tencent.mm:id/chatting_content_tv", false, 0),
            ),
        )
        requireNotNull(chat)
        assertEquals(Engagement.FAVORITE, chat.action)
        assertTrue(chat.privateContext)
        assertEquals("张三", chat.author)
        assertEquals("张三", chat.title)
        assertEquals("收藏", chat.content)
        assertNull(chat.url)
        assertFalse(chat.content.contains("hunter2"))
        assertFalse(chat.content.contains("secret.example"))
        assertFalse(Engagement.extrasJson(chat).contains("hunter2"))
        assertFalse(Engagement.extrasJson(chat).contains("secret.example"))
    }

    @Test
    fun passwordTabsSentencesAndCommentBodiesAreNotEngagement() {
        assertNull(
            Engagement.detect(
                "com.xingin.xhs",
                ClickSnap("点赞", "点赞", "id/like", "android.widget.EditText", true, 0),
                null,
                emptyList(),
            ),
        )
        val typed = PasswordSignals.TYPE_CLASS_TEXT or PasswordSignals.TYPE_TEXT_VARIATION_PASSWORD
        assertNull(
            Engagement.detect(
                "com.xingin.xhs",
                ClickSnap("点赞", null, "id/like", "android.widget.EditText", false, typed),
                null,
                emptyList(),
            ),
        )
        assertNull(
            Engagement.detect(
                "com.xingin.xhs",
                ClickSnap("收藏", "收藏", "com.xingin.xhs:id/tab_collect", "android.widget.TextView", false, 0),
                null,
                emptyList(),
            ),
        )
        assertNull(
            Engagement.detect(
                "com.xingin.xhs",
                ClickSnap("评论", "评论", null, "com.google.android.material.tabs.TabLayout\$TabView", false, 0),
                null,
                emptyList(),
            ),
        )
        assertNull(
            Engagement.detect(
                "com.tencent.mm",
                ClickSnap("发送", null, "com.tencent.mm:id/send", "android.widget.Button", false, 0),
                "张三",
                emptyList(),
            ),
        )
        assertNull(
            Engagement.detect(
                "com.xingin.xhs",
                ClickSnap("我喜欢这家店的桂花糕", null, null, "android.widget.TextView", false, 0),
                null,
                emptyList(),
            ),
        )
        assertNull(
            Engagement.detect(
                "com.xingin.xhs",
                ClickSnap("这条评论说得真好", null, "com.xingin.xhs:id/comment_list", "android.widget.TextView", false, 0),
                null,
                emptyList(),
            ),
        )
        assertNull(
            Engagement.detect(
                "com.example.reader",
                ClickSnap("12条评论", null, null, "android.widget.TextView", false, 0),
                null,
                emptyList(),
            ),
        )
    }

    @Test
    fun dedupeDropsTheSameTapAndKeepsAnotherActionOrTitle() {
        val dedupe = EngagementDedupe(windowMs = 1_000)
        assertTrue(dedupe.shouldRecord("com.xingin.xhs", "like", "桂花|like", 1_000))
        assertFalse(dedupe.shouldRecord("com.xingin.xhs", "like", "桂花|like", 1_400))
        assertTrue(dedupe.shouldRecord("com.xingin.xhs", "favorite", "桂花|fav", 1_500))
        assertTrue(dedupe.shouldRecord("com.xingin.xhs", "like", "下一篇|like", 1_600))
        assertTrue(dedupe.shouldRecord("com.xingin.xhs", "like", "桂花|like", 2_000))
    }

    private fun xhsNoteNodes(): List<TextNode> {
        return listOf(
            TextNode("周末去灵隐寺看桂花", null, "com.xingin.xhs:id/noteTitle", false, 0),
            TextNode("杭州阿九", null, "com.xingin.xhs:id/nickName", false, 0),
            TextNode("雨后的石头路很滑，记得穿防滑的鞋。", null, "com.xingin.xhs:id/noteDesc", false, 0),
            TextNode("https://www.xiaohongshu.com/discovery/item/abc123", null, "com.xingin.xhs:id/link", false, 0),
            TextNode("hunter2", null, "com.xingin.xhs:id/password", true, 0),
        )
    }
}
