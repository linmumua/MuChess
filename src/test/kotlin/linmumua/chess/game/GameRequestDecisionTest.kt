package linmumua.chess.game

import linmumua.chess.game.request.GameRequest
import linmumua.chess.game.request.GameRequestDecision
import linmumua.chess.game.request.GameRequestKind
import linmumua.chess.rule.core.Side
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * 求和与悔棋的判定层单测。
 *
 * 这一层的每条规则都是为了挡住一种具体的坏结果，用例按"挡的是什么"组织，
 * 不是按方法逐个覆盖：
 * - 连点刷请求 → 同类型请求未过期时不许再发
 * - 过期请求被答复 → 三十秒后那条不该还能点
 * - 自己同意自己 → 发起方点到自己那条提示上不能生效
 * - 悔棋撤掉对手的手 → 只有"刚走完那手是发起方走的"才允许
 */
class GameRequestDecisionTest {

    private val now = 1_000_000L

    private fun request(
        requester: Side,
        expiresAt: Long,
        kind: GameRequestKind = GameRequestKind.DRAW,
    ) = GameRequest(kind, requester, expiresAt)

    /**
     * 没有旧请求时当然能发。
     *
     * 这是唯一的正常路径，先钉住它，后面几条才有对照。
     */
    @Test
    fun `没有待答复请求时允许发起`() {
        assertEquals(
            GameRequestDecision.OpenVerdict.ALLOWED,
            GameRequestDecision.open(null, now),
        )
    }

    /**
     * 连点防护：同一类型还挂着一条没过期的请求时，第二次发起必须被挡。
     *
     * 不挡住的后果不是"多发一条消息"，而是对手的聊天栏被同一条带按钮的提示刷满，
     * 而且每条都能点 —— 真正的和棋请求会被埋掉。
     */
    @Test
    fun `同类型请求未过期时挡住再次发起`() {
        val pending = request(Side.FIRST, expiresAt = now + 5_000L)
        assertEquals(
            GameRequestDecision.OpenVerdict.PENDING,
            GameRequestDecision.open(pending, now),
        )
    }

    /**
     * 过期的旧请求不算占位，允许重新发起。
     *
     * 否则一次没人理的求和会把这个功能永久锁死 —— 表里那条永远不会有人来答复，
     * 也就永远不会被 answer 摘掉。
     */
    @Test
    fun `请求过期后允许重新发起`() {
        val expired = request(Side.FIRST, expiresAt = now)
        assertEquals(
            GameRequestDecision.OpenVerdict.ALLOWED,
            GameRequestDecision.open(expired, now),
        )
    }

    /** 过期判定取的是"到点即失效"，边界那一毫秒算过期 */
    @Test
    fun `到期时刻本身算已过期`() {
        val onTheDot = request(Side.FIRST, expiresAt = now)
        assertEquals(true, onTheDot.isExpired(now))
        assertEquals(false, request(Side.FIRST, expiresAt = now + 1L).isExpired(now))
    }

    /** 表里没有这条请求时，答复要报"没有请求"而不是静默成功 */
    @Test
    fun `答复不存在的请求报无请求`() {
        assertEquals(
            GameRequestDecision.AnswerVerdict.NONE,
            GameRequestDecision.answer(null, Side.SECOND, now),
        )
    }

    /**
     * 过期请求不能被答复。
     *
     * 带按钮的提示会一直留在聊天记录里，玩家五分钟后翻上去点一下不能真的和棋。
     */
    @Test
    fun `过期请求不能被答复`() {
        val expired = request(Side.FIRST, expiresAt = now - 1L)
        assertEquals(
            GameRequestDecision.AnswerVerdict.EXPIRED,
            GameRequestDecision.answer(expired, Side.SECOND, now),
        )
    }

    /**
     * 发起方不能答复自己那条。
     *
     * 少了这一条，一个人点自己发出的求和就能单方面把这局判和。
     */
    @Test
    fun `发起方不能答复自己的请求`() {
        val mine = request(Side.FIRST, expiresAt = now + 5_000L)
        assertEquals(
            GameRequestDecision.AnswerVerdict.OWN_REQUEST,
            GameRequestDecision.answer(mine, Side.FIRST, now),
        )
    }

    /** 对手答复未过期的请求，这是正常路径 */
    @Test
    fun `对手可以答复未过期的请求`() {
        val theirs = request(Side.FIRST, expiresAt = now + 5_000L)
        assertEquals(
            GameRequestDecision.AnswerVerdict.ALLOWED,
            GameRequestDecision.answer(theirs, Side.SECOND, now),
        )
    }

    /** 一手没走时没有可撤的东西 */
    @Test
    fun `开局未走子时不能悔棋`() {
        assertEquals(
            GameRequestDecision.UndoVerdict.NO_HISTORY,
            GameRequestDecision.undoEligibility(Side.FIRST, sideToMove = Side.FIRST, moveCount = 0),
        )
    }

    /**
     * 悔棋的核心判定：只有"现在轮到对手走"才说明刚走完那手是发起方走的。
     *
     * 这是整个悔棋功能里最容易写反的一条。若允许在自己的回合悔棋，
     * 撤掉的会是对手刚走的那一手 —— 发起方凭一句"我要悔棋"吃掉了对手的着法。
     */
    @Test
    fun `只有刚走完自己那手时才能悔棋`() {
        // 先手走完，现在轮后手 → 最后一手是先手走的，先手可以撤
        assertEquals(
            GameRequestDecision.UndoVerdict.ALLOWED,
            GameRequestDecision.undoEligibility(Side.FIRST, sideToMove = Side.SECOND, moveCount = 1),
        )
        // 轮到先手走 → 最后一手是后手走的，先手要撤就是撤对手的棋
        assertEquals(
            GameRequestDecision.UndoVerdict.NOT_OWN_MOVE,
            GameRequestDecision.undoEligibility(Side.FIRST, sideToMove = Side.FIRST, moveCount = 2),
        )
    }

    /** 后手侧同样成立，判定不能只对先手写对 */
    @Test
    fun `悔棋判定对后手同样成立`() {
        assertEquals(
            GameRequestDecision.UndoVerdict.ALLOWED,
            GameRequestDecision.undoEligibility(Side.SECOND, sideToMove = Side.FIRST, moveCount = 2),
        )
        assertEquals(
            GameRequestDecision.UndoVerdict.NOT_OWN_MOVE,
            GameRequestDecision.undoEligibility(Side.SECOND, sideToMove = Side.SECOND, moveCount = 1),
        )
    }

    /** 过期时刻按秒数换算，默认三十秒 */
    @Test
    fun `过期时刻按传入秒数换算`() {
        assertEquals(now + 30_000L, GameRequestDecision.expiryOf(now))
        assertEquals(now + 5_000L, GameRequestDecision.expiryOf(now, ttlSeconds = 5))
    }

    /**
     * 人机悔棋撤几手 —— 这组用例挡的是**对局卡死**。
     *
     * 撤完之后轮次必须落回玩家。若撤成机器人的轮次，玩家不能走（不是他的轮次）、
     * 机器人也不会走（撤手不触发 scheduleBotIfNeeded），这副棋盘就废了，只能收掉重开。
     * 所以每条用例都顺带断言"撤 plies 手之后该走的还是玩家"这个不变量。
     */
    @Test
    fun `人机悔棋撤完之后轮次必须回到玩家`() {
        // 轮到玩家走：机器人刚落子，末手是机器人的。撤 2 手（机器人那手 + 玩家自己那手）
        assertEquals(
            2,
            GameRequestDecision.botUndoPlies(Side.FIRST, sideToMove = Side.FIRST, moveCount = 8),
        )
        // 轮到机器人走：玩家刚落子、机器人还没走，末手是玩家自己的。撤 1 手就回到玩家
        assertEquals(
            1,
            GameRequestDecision.botUndoPlies(Side.FIRST, sideToMove = Side.SECOND, moveCount = 7),
        )
        // 撤的手数与轮次的奇偶关系：撤一手翻转一次轮次，两条分支撤完都落在玩家身上
        for (moveCount in 2..12) {
            val playerToMove = GameRequestDecision.botUndoPlies(Side.FIRST, Side.FIRST, moveCount)
            val botToMove = GameRequestDecision.botUndoPlies(Side.FIRST, Side.SECOND, moveCount)
            assertEquals(0, playerToMove % 2, "轮到玩家走时必须撤偶数手，否则撤完轮次落到机器人身上")
            assertEquals(1, botToMove % 2, "轮到机器人走时必须撤奇数手，否则撤完轮次还在机器人身上")
        }
    }

    @Test
    fun `没有自己走过的手时人机悔棋撤不动`() {
        // 一手未走
        assertEquals(0, GameRequestDecision.botUndoPlies(Side.FIRST, sideToMove = Side.FIRST, moveCount = 0))
        // 机器人执先只走了一手、玩家还没落子过：要撤 2 手但只有 1 手，撤不动。
        // 这里若错返回 1，就会撤掉机器人那一手、把轮次留在机器人身上 —— 正是卡死那种情况
        assertEquals(0, GameRequestDecision.botUndoPlies(Side.SECOND, sideToMove = Side.SECOND, moveCount = 1))
        // 玩家执先走了一手、机器人还没走：撤 1 手回到开局，合法
        assertEquals(1, GameRequestDecision.botUndoPlies(Side.FIRST, sideToMove = Side.SECOND, moveCount = 1))
    }
}
