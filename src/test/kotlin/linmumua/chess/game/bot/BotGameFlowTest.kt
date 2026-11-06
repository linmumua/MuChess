package linmumua.chess.game.bot

import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import linmumua.chess.game.ChessGame
import linmumua.chess.game.GamePhase
import linmumua.chess.game.MoveAttempt
import linmumua.chess.rule.core.GameRules
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.core.Square
import linmumua.chess.rule.gomoku.GomokuRules
import linmumua.chess.rule.xiangqi.XiangqiRules
import org.junit.jupiter.api.Test

/**
 * 双机器人经 [ChessGame] 走完整局。
 *
 * 与 [BotSelfPlayTest] 的分工：那个直接驱动 `rules.apply(board, move)`，只证
 * "搜索不会选出非法着法"；本类走 [ChessGame.move] 这条**真实对局入口**，
 * 覆盖那一层独有的职责 —— 轮次推进、将军标记、终局结算、phase 迁移。
 *
 * 为什么这层必须单独测：`/chess bot add red` + `/chess bot add black` 之后
 * 双方 AI 自主对下，走的就是 `ChessGame.move`（`MoveExecutor.moveAsBot` 里明确
 * 不复用玩家那条路，但校验一律仍走 `ChessGame.move`，不给机器人开后门）。
 * 如果轮次或 phase 在这一层出错，表现是对局卡住或提前判负，而搜索层的测试全绿。
 *
 * [ChessGame] 零 Bukkit 依赖（grep org.bukkit 计数为 0），所以这局棋能在单测里跑完，
 * 不需要服务端。渲染与实体清理不在本类范围 —— 那两项要真人上服看。
 */
class BotGameFlowTest {

    private val red = UUID.randomUUID()
    private val black = UUID.randomUUID()

    /** 一局自对局跑完后的观察结果 */
    private class FlowResult(
        val moves: Int,
        val finished: Boolean,
        /** 出现过将军的手数，用来确认将军标记真的会被算出来 */
        val checkCount: Int,
        /** 每一手实际走棋方的序列，用来验证轮次严格交替 */
        val moverSequence: List<Side>,
    )

    /**
     * 开一局双机器人对局并跑到终局或手数上限。
     *
     * 每一手都用**当前该走的那一方**的 UUID 去调 [ChessGame.move]，
     * 这样轮次判定是真的在起作用，而不是靠测试自己记着该谁走。
     */
    private fun playThrough(
        rules: GameRules,
        options: BotOptions,
        maxMoves: Int,
        seed: Int,
    ): FlowResult {
        val game = ChessGame(rules)
        game.join(red, Side.FIRST)
        game.join(black, Side.SECOND)
        game.start(0L)
        assertEquals(GamePhase.PLAYING, game.phase, "开局后 phase 应为 PLAYING")

        val random = Random(seed)
        val movers = mutableListOf<Side>()
        var checks = 0
        var moves = 0

        while (moves < maxMoves && game.phase == GamePhase.PLAYING) {
            val side = game.sideToMove
            val uuid = if (side == Side.FIRST) red else black
            val chosen = BotBrain.chooseMove(rules, game.board, side, options, random) ?: break

            val attempt = game.move(uuid, chosen.from, chosen.to, moves.toLong())
            // 机器人选出的着法必须能过 ChessGame 这一层。被拒说明搜索与规则层对不上，
            // 或者轮次判定错了 —— 两种都会让真实对局卡死在机器人这边。
            val success = attempt as? MoveAttempt.Success
            assertNotNull(
                success,
                "第 $moves 手被 ChessGame 拒了：$attempt（走棋方 $side，着法 $chosen）",
            )
            // 字段是 opponentInCheck：走完之后被将的是**对手**，不是自己
            if (success.opponentInCheck) {
                checks++
            }
            movers += side
            moves++
        }

        return FlowResult(moves, game.phase == GamePhase.FINISHED, checks, movers)
    }

    /**
     * 五子棋双机器人经 ChessGame 能下到终局并正确结算。
     *
     * 选五子棋是因为它必然终局（最多 225 手满盘），可以硬性断言 phase 走到 FINISHED
     * 且 result 非空。象棋两个浅层机器人容易长期僵持，不适合断言终局。
     */
    @Test
    fun `五子棋双机器人经对局入口能下到终局并结算`() {
        val options = BotOptions(depth = 2, maxCandidates = 12, timeLimitMillis = 0)
        val result = playThrough(GomokuRules(), options, maxMoves = 226, seed = 1)

        assertTrue(result.finished, "对局没走到 FINISHED，停在第 ${result.moves} 手")
        assertTrue(result.moves >= 9, "只走了 ${result.moves} 手，可能有一方没在防守")
    }

    /**
     * 轮次必须严格交替，且第一手是先手方。
     *
     * 这条是本类的核心：`ChessGame.move` 里的轮次判定若失效，会出现同一方连走两手。
     * 那种局面在真实对局里表现为"对手的 AI 一直不动"，而搜索层完全测不出来。
     * 用实际走棋方的序列来验，而不是信任测试自己的记账。
     */
    @Test
    fun `双机器人对局的轮次严格交替`() {
        val options = BotOptions(depth = 1, maxCandidates = 8, timeLimitMillis = 0)
        val result = playThrough(GomokuRules(), options, maxMoves = 30, seed = 7)

        assertTrue(result.moves >= 4, "手数太少，验不出交替，只走了 ${result.moves} 手")
        assertEquals(Side.FIRST, result.moverSequence.first(), "第一手应由先手方走")
        result.moverSequence.zipWithNext().forEachIndexed { index, (prev, next) ->
            assertEquals(
                prev.opposite,
                next,
                "第 ${index + 1} 手与第 ${index + 2} 手都是 $prev，轮次没有交替",
            )
        }
    }

    /**
     * 象棋双机器人对局中将军标记要真的被算出来。
     *
     * 将军播报依赖 `MoveAttempt.Success.opponentInCheck`（由 `rules.needsWarning` 算）。
     * 若这一路恒为 false，玩家永远收不到将军提示，而规则层的将军检测单测仍全绿 ——
     * 断的是 ChessGame 到规则层的这段接线。
     *
     * 走 80 手：两个浅层机器人在这个长度内几乎必然互相将过，
     * 但不硬性要求终局（象棋僵持很常见，自然限着判和默认 60 回合，跑满太慢）。
     */
    @Test
    fun `象棋双机器人对局会产生将军标记`() {
        val options = BotOptions(depth = 2, maxCandidates = 16, timeLimitMillis = 0)
        val result = playThrough(XiangqiRules(), options, maxMoves = 80, seed = 3)

        assertTrue(
            result.moves == 80 || result.finished,
            "象棋对局在第 ${result.moves} 手停了，但棋局并未结束",
        )
        assertTrue(
            result.checkCount > 0,
            "走了 ${result.moves} 手一次将军都没算出来，opponentInCheck 这条线可能断了",
        )
    }

    /**
     * 终局之后不再接受走子。
     *
     * 真实对局里 AI 的落子是延迟调度的（`scheduleBotIfNeeded` 带 think-ticks），
     * 延迟期间对局可能已经结束。若终局后仍能落子，会出现"棋局已判胜负还在走"的
     * 错乱局面。`moveAsBot` 自己也复查 phase，但这一层的门必须独立成立。
     */
    @Test
    fun `终局后不再接受走子`() {
        val options = BotOptions(depth = 2, maxCandidates = 12, timeLimitMillis = 0)
        val rules = GomokuRules()
        val game = ChessGame(rules)
        game.join(red, Side.FIRST)
        game.join(black, Side.SECOND)
        game.start(0L)

        val random = Random(11)
        var guard = 0
        while (game.phase == GamePhase.PLAYING && guard < 226) {
            val side = game.sideToMove
            val uuid = if (side == Side.FIRST) red else black
            val chosen = BotBrain.chooseMove(rules, game.board, side, options, random) ?: break
            game.move(uuid, chosen.from, chosen.to, guard.toLong())
            guard++
        }

        assertEquals(GamePhase.FINISHED, game.phase, "这局应已终局")
        assertNotNull(game.result, "终局后 result 不应为空")

        // 终局后再让任意一方尝试落子，必须被拒。
        // 坐标随便给：ChessGame.move 第一件事就是查 phase，走不到规则判定那一步，
        // 所以这里不需要构造合法着法 —— 用固定坐标反而让"拦在入口"这件事更明确。
        val side = game.sideToMove
        val uuid = if (side == Side.FIRST) red else black
        val anywhere = Square(0, 0)
        val after = game.move(uuid, anywhere, anywhere, 999L)
        assertTrue(after is MoveAttempt.NotPlaying, "终局后仍接受走子，返回了 $after")
    }
}
