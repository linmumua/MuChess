package linmumua.chess.game

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * 围棋背景音乐判定测试。
 *
 * 这套状态机的错法全是**静默错**，没有一种会抛异常：
 * 该停没停会让终局播报被音乐盖过去，该等没等会让同一段曲子在客户端叠着放，
 * 该播不播则是玩家开了开关却一直听不到声。三种都只能靠断言钉住。
 */
class GoBgmDecisionTest {

    /** 六个条件全真的那一组基准参数，各用例只改其中一项 */
    private fun eligible(
        globalEnabled: Boolean = true,
        sessionEnabled: Boolean = true,
        isGo: Boolean = true,
        phase: GamePhase = GamePhase.PLAYING,
        sound: String = "muchess:music.go_opening",
        volume: Double = 0.6,
    ) = GoBgmDecision.eligible(globalEnabled, sessionEnabled, isGo, phase, sound, volume)

    /** 全条件满足时才响，否则整个特性等于没接上 */
    @Test
    fun `围棋对局中且两级闸都开时该响`() {
        assertTrue(eligible())
    }

    /**
     * 总闸关着，这一局自己开着也不许响。
     *
     * 两级闸的顺序不能反：服主关掉总闸就是不想要这功能，
     * 不能被某一局的存档值翻上来。
     */
    @Test
    fun `全局总闸关着时不响`() {
        assertFalse(eligible(globalEnabled = false))
    }

    /** 玩家在对局菜单里关掉的那一局不响，即使总闸开着 */
    @Test
    fun `本局开关关着时不响`() {
        assertFalse(eligible(sessionEnabled = false))
    }

    /**
     * 象棋与五子棋没有这段音乐。
     *
     * 这条防的是以后有人把判断挪走时漏掉棋种：漏掉的表现是象棋盘开始放
     * 66 秒的围棋曲子，一局几分钟的棋种被长曲子盖住。
     */
    @Test
    fun `非围棋不响`() {
        assertFalse(eligible(isGo = false))
    }

    /**
     * 只在 PLAYING 响：等人入座时不响，终局后也不响。
     *
     * 终局那一条是"两声不叠"的第二道保险 —— 精确停在 announceResult 开头，
     * 巡检这里再兜一次，防状态漂移（phase 已 FINISHED 但音乐还在放）。
     */
    @Test
    fun `等人与终局阶段都不响`() {
        assertFalse(eligible(phase = GamePhase.WAITING))
        assertFalse(eligible(phase = GamePhase.FINISHED))
    }

    /** 音效 id 留空是"不播放"的正式写法，等同把总闸关掉 */
    @Test
    fun `音效 id 留空不响`() {
        assertFalse(eligible(sound = ""))
        assertFalse(eligible(sound = "   "))
    }

    /** 音量 0 等于没声，不值得发包 */
    @Test
    fun `音量为零不响`() {
        assertFalse(eligible(volume = 0.0))
    }

    /** 该响却没在放：起播。这条断言的是"开关拨开后真能听到" */
    @Test
    fun `该响且没在放时起播`() {
        val action = GoBgmDecision.decide(eligible = true, playing = false, nowMillis = 1_000, nextPlayAtMillis = 0)
        assertEquals(GoBgmAction.PLAY, action)
    }

    /**
     * 在放且周期没到：什么都不做。
     *
     * **这条防的是每秒重播叠成噪音。** 巡检每秒跑一次，若这里返回 PLAY，
     * 一分钟就会有 60 遍同一首曲子叠着放，音量翻几十倍。
     */
    @Test
    fun `在放且未到周期时等着`() {
        val action = GoBgmDecision.decide(
            eligible = true,
            playing = true,
            nowMillis = 10_000,
            nextPlayAtMillis = 70_000,
        )
        assertEquals(GoBgmAction.WAIT, action)
    }

    /** 到点（含刚好等于）就重播，MC 不能让声音事件自己循环 */
    @Test
    fun `到达周期时重播`() {
        assertEquals(
            GoBgmAction.PLAY,
            GoBgmDecision.decide(eligible = true, playing = true, nowMillis = 70_000, nextPlayAtMillis = 70_000),
        )
        assertEquals(
            GoBgmAction.PLAY,
            GoBgmDecision.decide(eligible = true, playing = true, nowMillis = 70_001, nextPlayAtMillis = 70_000),
        )
    }

    /**
     * 不该响却在放：停。
     *
     * **这条防的是终局后音乐放不停** —— 客户端不会自己停，
     * 不发停止包的话那首曲子会一直放到玩家退服。
     */
    @Test
    fun `不该响却在放时停止`() {
        val action = GoBgmDecision.decide(
            eligible = false,
            playing = true,
            nowMillis = 10_000,
            nextPlayAtMillis = 70_000,
        )
        assertEquals(GoBgmAction.STOP, action)
    }

    /**
     * 不该响也没在放：什么都不做。
     *
     * 绝大多数会话（象棋盘、等人的围棋盘）每秒都走这一支，
     * 返回 STOP 会让巡检对着一堆没放音乐的棋盘白发停止包。
     */
    @Test
    fun `不该响且没在放时不动`() {
        val action = GoBgmDecision.decide(
            eligible = false,
            playing = false,
            nowMillis = 10_000,
            nextPlayAtMillis = 0,
        )
        assertEquals(GoBgmAction.IDLE, action)
    }

    /** 正常周期按秒换算成毫秒 */
    @Test
    fun `下次重播时间按周期推算`() {
        assertEquals(66_000L, GoBgmDecision.nextPlayAt(nowMillis = 0, periodSeconds = 66))
        assertEquals(1_020_000L, GoBgmDecision.nextPlayAt(nowMillis = 1_000_000, periodSeconds = 20))
    }

    /**
     * 周期 0 与负数被抬到 1 秒。
     *
     * 这是配置解析之外的第二道：绕过配置直接构造 `GoBgmSettings` 的调用方
     * （单测、将来的命令）填 0 会让巡检退化成每秒重播，一分钟叠 60 遍。
     */
    @Test
    fun `周期为零或负数时至少间隔一秒`() {
        assertEquals(1_000L, GoBgmDecision.nextPlayAt(nowMillis = 0, periodSeconds = 0))
        assertEquals(1_000L, GoBgmDecision.nextPlayAt(nowMillis = 0, periodSeconds = -30))
    }
}
