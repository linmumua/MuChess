package linmumua.chess.game

import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.xiangqi.XiangqiRules
import org.junit.jupiter.api.Test

/**
 * 读秒相关的纯逻辑测试：剩余秒数、每局时限覆盖、动作栏文案分流。
 *
 * 为什么这三件事值得单测：它们都只在"限时对局跑到某个特定秒数"时才表现出来，
 * 在服务器上复现一次要真的坐两个人等一分钟，而错了的表现全都是玩家眼里的"计时坏了"——
 * 读到 1 秒却已判负、读到 0 还能走、设了快棋时限重启后变回全局值、轮到自己却提示对方行棋。
 *
 * 最关键的一条不变式：[ChessGame.remainingSeconds] 返回 0 的时刻必须与
 * [ChessGame.isTimedOut] 转 true 的时刻严格重合。下面用同一组时间点同时断言两者。
 */
class TurnCountdownTest {

    private val red = UUID.randomUUID()
    private val black = UUID.randomUUID()

    /** 开局时刻，取一个不为 0 的值：0 在 ChessGame 里表示"还没开始计时" */
    private val startAt = 1_700_000_000_000L

    private fun startedGame(): ChessGame = ChessGame(XiangqiRules()).apply {
        join(red, Side.FIRST)
        join(black, Side.SECOND)
        start(startAt)
    }

    /**
     * 不限时的局不读秒，而不是读成 0。
     *
     * 这条区分是整个读秒功能的地基：0 表示"时间到了该判负"，
     * 不限时若也返回 0，动作栏会常驻一行"该你走 0 秒"，而对局永远不会结束。
     */
    @Test
    fun `不限时返回 null 而不是 0`() {
        val game = startedGame()
        assertNull(game.remainingSeconds(startAt, timeoutSeconds = 0))
    }

    /**
     * 开局后还没走第一步时不读秒。
     *
     * 与 [ChessGame.isTimedOut] 的"lastMoveAt 为 0 不计时"是同一件事的两面。
     * 这里用未开局的对局验证：WAITING 阶段的棋盘上方挂着"加入对局"的牌子，
     * 此时再刷一行读秒会让人以为棋局已经开始了。
     */
    @Test
    fun `未开局不读秒`() {
        val game = ChessGame(XiangqiRules())
        game.join(red, Side.FIRST)
        assertNull(game.remainingSeconds(startAt, timeoutSeconds = 30), "WAITING 阶段不该有读秒")
    }

    /**
     * 刚落子那一瞬显示满值。
     *
     * 显示 29 会让玩家觉得"我还没动就少了一秒"。这是取整方向的直接体现。
     */
    @Test
    fun `刚落子显示满值`() {
        val game = startedGame()
        assertEquals(30, game.remainingSeconds(startAt, timeoutSeconds = 30))
    }

    /**
     * 秒数向上取整：不足一秒也算还有一秒。
     *
     * 配合下面那条"耗尽即 0"，保证读秒从 1 跳到 0 的时刻正是判负时刻。
     */
    @Test
    fun `不足一秒仍显示一秒`() {
        val game = startedGame()
        // 时限 30 秒，已过 29.001 秒，剩 0.999 秒
        assertEquals(1, game.remainingSeconds(startAt + 29_001L, timeoutSeconds = 30))
    }

    /**
     * 读到 0 与判超时必须同时发生。
     *
     * 这是本文件最重要的一条：两个方法各自算时间，任何一边改了取整或比较方向都会错开一秒，
     * 表现就是"读秒还剩 1 秒人已经输了"或"读秒到 0 还能继续走"。
     */
    @Test
    fun `读到 0 的时刻与判超时的时刻一致`() {
        val game = startedGame()
        val timeout = 30

        val justBefore = startAt + 29_999L
        assertEquals(1, game.remainingSeconds(justBefore, timeout), "还差 1 毫秒不能读成 0")
        assertEquals(false, game.isTimedOut(justBefore, timeout), "还差 1 毫秒不能判负")

        val exact = startAt + 30_000L
        assertEquals(0, game.remainingSeconds(exact, timeout), "时间耗尽应读 0")
        assertEquals(true, game.isTimedOut(exact, timeout), "时间耗尽应判负")
    }

    /**
     * 时限被调小或系统时间回跳时，读秒不超过时限本身。
     *
     * 服主在一局中途把 turn-timeout-seconds 从 60 改成 10，若不夹取，
     * 动作栏会显示"该你走 55 秒"而实际 10 秒就判负。
     */
    @Test
    fun `读秒不超过时限本身`() {
        val game = startedGame()
        // 假装时间回跳了 20 秒，按公式算出的剩余是 30 秒，但时限只有 10 秒
        assertEquals(10, game.remainingSeconds(startAt - 20_000L, timeoutSeconds = 10))
    }

    /**
     * 每局时限盖过全局值。
     *
     * 没有这一层，服主要开一桌快棋就得改全局配置，正在进行的所有对局会一起被改。
     */
    @Test
    fun `每局时限盖过全局值`() {
        val game = startedGame()
        assertEquals(60, game.effectiveTimeoutSeconds(globalSeconds = 60), "没设过就听全局的")

        game.turnTimeoutSeconds = 10
        assertEquals(10, game.effectiveTimeoutSeconds(globalSeconds = 60))
    }

    /**
     * 每局设成 0 表示"这局不限时"，不能退回全局值。
     *
     * 这条是 `Int?` 而非 `Int` 的全部理由：0 与 null 若混为一谈，
     * 玩家在菜单里选了"不限时"，下一秒就会被全局时限判负。
     */
    @Test
    fun `每局设成 0 表示不限时而不是跟随全局`() {
        val game = startedGame()
        game.turnTimeoutSeconds = 0

        assertEquals(0, game.effectiveTimeoutSeconds(globalSeconds = 60))
        assertNull(
            game.remainingSeconds(startAt, game.effectiveTimeoutSeconds(60)),
            "这局选了不限时，就不该有读秒",
        )
    }

    /**
     * 动作栏文案按"看的人能做什么"分流。
     *
     * 三条键各自对应一种处境。分错的表现是观战者看到"该你走"，
     * 或者轮到自己时看到"对方思考"——玩家会因此错过自己的回合。
     */
    @Test
    fun `文案按观看者处境分流`() {
        val remaining = 30
        assertEquals(
            "turn-countdown-ours",
            TurnCountdownDecision.key(Side.FIRST, Side.FIRST, remaining),
            "轮到自己",
        )
        assertEquals(
            "turn-countdown-theirs",
            TurnCountdownDecision.key(Side.SECOND, Side.FIRST, remaining),
            "轮到对手",
        )
        assertEquals(
            "turn-countdown-spectator",
            TurnCountdownDecision.key(null, Side.FIRST, remaining),
            "观战者两边都不是，必须报出是谁在走",
        )
    }

    /**
     * 进入阈值才换告警文案，且三条分流各自都有紧急态。
     *
     * 只给"轮到自己"做紧急态是不够的：对手时间快用完时，等的那一方也需要知道
     * 局面马上要有结果了。阈值上下各测一格，钉住"含端点"这个约定。
     */
    @Test
    fun `剩余秒数进入阈值时换告警文案`() {
        val threshold = TurnCountdownDecision.URGENT_SECONDS
        assertEquals(
            "turn-countdown-ours",
            TurnCountdownDecision.key(Side.FIRST, Side.FIRST, threshold + 1),
            "刚好超过阈值仍是常态配色",
        )
        assertEquals(
            "turn-countdown-ours-urgent",
            TurnCountdownDecision.key(Side.FIRST, Side.FIRST, threshold),
            "阈值含端点",
        )
        assertEquals(
            "turn-countdown-theirs-urgent",
            TurnCountdownDecision.key(Side.SECOND, Side.FIRST, 0),
            "对手快用完时等的那一方也要收到告警",
        )
        assertEquals(
            "turn-countdown-spectator-urgent",
            TurnCountdownDecision.key(null, Side.FIRST, 1),
        )
    }
}
