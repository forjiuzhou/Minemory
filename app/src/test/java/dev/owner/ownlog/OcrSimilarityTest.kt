package dev.owner.ownlog

import dev.owner.ownlog.domain.FeedCoalescer
import dev.owner.ownlog.domain.FeedScreen
import dev.owner.ownlog.domain.OcrPolicy
import dev.owner.ownlog.domain.OcrSimilarity
import dev.owner.ownlog.domain.TextNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrSimilarityTest {
    @Test
    fun skipsOnlyWhenMoreThanNinetyPercentSimilar() {
        val previous = "今天把后山的路走完了，风很大，但是云很好看。"
        assertTrue(OcrSimilarity.isNearDuplicate(previous, previous))
        assertTrue(OcrSimilarity.isNearDuplicate(previous, "今天  把后山的路走完了，风很大，但是云很好看。"))
        val almost = previous.replaceFirst("云", "雾")
        assertTrue(OcrSimilarity.similarity(previous, almost) > 0.90)
        assertTrue(OcrSimilarity.isNearDuplicate(previous, almost))

        val ten = "abcdefghij"
        val oneChange = "abcdefghik"
        assertEquals(0.90, OcrSimilarity.similarity(ten, oneChange), 0.0001)
        assertFalse(OcrSimilarity.isNearDuplicate(ten, oneChange))

        assertFalse(OcrSimilarity.isNearDuplicate(previous, "完全是另一段话，讲的是晚饭和地铁。"))
        assertFalse(OcrSimilarity.isNearDuplicate(null, previous))
        assertFalse(OcrSimilarity.isNearDuplicate("", previous))
    }

    @Test
    fun nearDuplicateDoesNotBypassTheMinimumOcrGap() {
        val packages = setOf("com.tencent.mm")
        assertNull(
            OcrPolicy.decide(
                "com.tencent.mm",
                packages,
                lastUsefulTextAt = null,
                lastOcrAt = 0,
                screenChangedAt = 0,
                now = OcrPolicy.MIN_GAP_MS - 1,
            ),
        )
        assertTrue(OcrPolicy.MIN_GAP_MS <= OcrPolicy.LIST_GAP_MS)
    }

    @Test
    fun xiaohongshuNoteBecomesOneScreen() {
        val nodes = listOf(
            TextNode("关注", null, "com.xingin.xhs:id/follow", false, 0),
            TextNode("小林", null, "com.xingin.xhs:id/nickName", false, 0),
            TextNode("周末在家做的面", null, "com.xingin.xhs:id/title", false, 0),
            TextNode("汤底熬了两个小时，辣椒是自己晒的，最后撒了一把葱花。", null, "com.xingin.xhs:id/noteContent", false, 0),
            TextNode("点赞", null, "com.xingin.xhs:id/like", false, 0),
            TextNode("评论", null, null, false, 0),
        )
        val screen = FeedScreen.consolidate("com.xingin.xhs", nodes, null)
        assertEquals(FeedScreen.HINT_XHS_NOTE, screen?.screenHint)
        assertEquals("周末在家做的面", screen?.title)
        assertEquals("小林", screen?.author)
        assertTrue(screen!!.description!!.contains("汤底"))
        assertFalse(screen.content.contains("点赞"))
        assertFalse(screen.content.contains("关注"))
        assertEquals(3, screen.content.lines().size)
        val withExtraChrome = nodes + TextNode("收藏", null, "com.xingin.xhs:id/fav", false, 0)
        val again = FeedScreen.consolidate("com.xingin.xhs", withExtraChrome, null)
        assertEquals(screen.signature, again?.signature)
    }

    @Test
    fun weChatChannelsConsolidateAndChatsDoNot() {
        val channels = listOf(
            TextNode("视频号", null, null, false, 0),
            TextNode("阿宁", null, "com.tencent.mm:id/finder_nickname", false, 0),
            TextNode("今天把后山的路走完了", null, "com.tencent.mm:id/finder_desc", false, 0),
            TextNode("转发", null, null, false, 0),
        )
        val screen = FeedScreen.consolidate("com.tencent.mm", channels, null)
        assertEquals(FeedScreen.HINT_WECHAT_CHANNELS, screen?.screenHint)
        assertEquals("阿宁", screen?.author)
        assertEquals("今天把后山的路走完了", screen?.description)
        assertFalse(screen!!.content.contains("转发"))

        val chat = listOf(
            TextNode("在吗", null, "com.tencent.mm:id/chatting_content_tv", false, 0),
            TextNode("在", null, "com.tencent.mm:id/chatting_content_tv", false, 0),
        )
        assertNull(FeedScreen.consolidate("com.tencent.mm", chat, "微信"))
    }

    @Test
    fun feedListStaysOnTheNormalPath() {
        val cards = (1..4).map { index ->
            TextNode("这是第${index}条笔记的标题，写得比较长一些", null, "com.xingin.xhs:id/cardTitle$index", false, 0)
        }
        assertNull(FeedScreen.consolidate("com.xingin.xhs", cards, null))
        assertNull(FeedScreen.consolidate("com.ss.android.ugc.aweme", cards, null))
    }

    @Test
    fun coalescerEmitsTheSettledScreenOnce() {
        val coalescer = FeedCoalescer(quietMs = 1_000)
        assertNull(coalescer.offer(FeedCoalescer.Sample("com.xingin.xhs|xhs_note", "partial", 0)))
        assertNull(coalescer.offer(FeedCoalescer.Sample("com.xingin.xhs|xhs_note", "settled", 400)))
        val ready = coalescer.takeReady(400, force = true)
        assertEquals("settled", ready?.signature)
        assertNull(coalescer.offer(FeedCoalescer.Sample("com.xingin.xhs|xhs_note", "settled", 2_000)))
        assertNull(coalescer.takeReady(2_000, force = true))

        val fresh = FeedCoalescer(quietMs = 1_000)
        fresh.offer(FeedCoalescer.Sample("com.xingin.xhs|xhs_note", "note", 0))
        val displaced = fresh.offer(FeedCoalescer.Sample("com.tencent.mm|wechat_channels", "video", 100))
        assertEquals("com.xingin.xhs|xhs_note", displaced?.key)
        assertEquals("video", fresh.takeReady(100, force = true)?.signature)
    }
}
