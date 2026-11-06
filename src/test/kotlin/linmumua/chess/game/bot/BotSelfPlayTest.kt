package linmumua.chess.game.bot

import kotlin.random.Random
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import linmumua.chess.rule.core.GameRules
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.gomoku.GomokuRules
import linmumua.chess.rule.xiangqi.XiangqiRules
import org.junit.jupiter.api.Test

/**
 * 机器人自对局测试。
 *
 * 单步测试能证明"机器人在某个局面下选得对"，但证不了"它能把一局棋下完"。
 * 真实对局里会出现单步测试摆不出来的局面：快满盘的五子棋、只剩单车的象棋残局、
 * 连续被将军十几手。这些局面上一旦选出非法着法或返回 null，
 * 对局就会永久卡在机器人这边 —— 而这正是 debug 性能测试要大量跑的场景。
 *
 * 所以这里让两个机器人自己下完整局，逐手校验合法性，并断言棋局真的会结束。
 * 阶段 4 的 `/chess debug test` 依赖的就是这条性质。
 */
class BotSelfPlayTest {

    /** 自对局的结果统计 */
    private class PlayResult(
        val moves: Int,
        val finished: Boolean,
        val elapsedMillis: Long,
        val totalNodes: Long,
    )

    /**
     * 让两个机器人下一局，逐手校验。
     *
     * @param maxMoves 手数上限，用于兜住"棋局永远不结束"的情况。
     *   象棋在没有限着判和时理论上能无限走下去，所以必须有上限；
     *   到上限没结束不算失败，只算"这局没下完"。
     */
    private fun selfPlay(
        rules: GameRules,
        options: BotOptions,
        maxMoves: Int,
        seed: Int,
    ): PlayResult {
        val board = rules.initialBoard()
        val random = Random(seed)
        var moves = 0
        var nodes = 0L
        val start = System.currentTimeMillis()

        while (moves < maxMoves) {
            val side = board.sideToMove
            if (rules.outcome(board, side).isFinished) {
                break
            }
            val result = BotBrain.chooseMoveWithStats(rules, board, side, options, random)
            val move = result.move ?: break
            nodes += result.visitedNodes

            // 每一手都必须过规则层的判定 —— 机器人不能有后门
            assertNull(
                rules.rejectionOf(board, move.from, move.to, side),
                "第 $moves 手选出了非法着法 $move（走棋方 $side）",
            )
            rules.apply(board, move)
            moves++
        }

        val finished = rules.outcome(board, board.sideToMove).isFinished ||
            rules.legalMoves(board, board.sideToMove).isEmpty()
        return PlayResult(moves, finished, System.currentTimeMillis() - start, nodes)
    }

    /**
     * 五子棋机器人对机器人必须能下出胜负。
     *
     * 五子棋一定会终局（最多 225 手就满盘），所以这里可以硬性要求"下完"。
     * 下不完只有两种原因：选出了非法着法被规则层拒，或者在还有空点时返回了 null。
     * 两种都会让 debug 对局永久挂着。
     */
    @Test
    fun `五子棋自对局能下到终局`() {
        val rules = GomokuRules()
        val options = BotOptions(depth = 2, maxCandidates = 12, timeLimitMillis = 0)
        val result = selfPlay(rules, options, maxMoves = 226, seed = 1)

        assertTrue(result.finished, "五子棋自对局没下到终局，走了 ${result.moves} 手就停了")
        // 两个都会堵四的机器人不会 9 手就结束；太短说明有一方在乱下
        assertTrue(
            result.moves >= 9,
            "五子棋自对局只走了 ${result.moves} 手，可能有一方完全没在防守",
        )
    }

    /**
     * 五子棋自对局的每一手都合法（由 [selfPlay] 内部逐手断言）。
     *
     * 换几个随机种子跑，因为同分打散会让不同种子走出不同的棋。
     * 只跑一个种子的话，某条只在特定局面出现的 bug 可能一直碰不到。
     */
    @Test
    fun `五子棋多种随机种子下都不出非法手`() {
        val rules = GomokuRules()
        val options = BotOptions(depth = 2, maxCandidates = 10, timeLimitMillis = 0)
        for (seed in 1..3) {
            val result = selfPlay(rules, options, maxMoves = 226, seed = seed)
            assertTrue(result.moves > 0, "种子 $seed 下一手都没走出来")
        }
    }

    /**
     * 象棋机器人对机器人要能连续走满一定手数不出非法手。
     *
     * 不要求"下到终局"：两个只搜两层的机器人在象棋上很可能长期僵持，
     * 而自然限着判和默认 60 回合，跑满会很慢。这里只要求它能稳定走满 40 手 ——
     * 足够穿过开局进入中局，把"吃子后局面变化剧烈"的阶段覆盖到。
     */
    @Test
    fun `象棋自对局连续走满四十手不出非法手`() {
        val rules = XiangqiRules()
        val options = BotOptions(depth = 2, maxCandidates = 16, timeLimitMillis = 0)
        val result = selfPlay(rules, options, maxMoves = 40, seed = 2)

        assertTrue(
            result.moves == 40 || result.finished,
            "象棋自对局在第 ${result.moves} 手停了，但棋局并未结束",
        )
    }

    /**
     * 默认参数下单手搜索耗时要在可接受范围。
     *
     * 这条是阶段 4 debug 性能测试的前提：如果一手棋要几秒，
     * 同时开几十盘就会把异步线程池堵死。
     *
     * 阈值给得很宽（平均 500ms/手），因为测试机性能差异大 ——
     * 真正要拦住的是"某次改动让搜索慢了一个数量级"这种回归。
     */
    @Test
    fun `默认参数下单手搜索耗时可接受`() {
        val rules = GomokuRules()
        val options = BotOptions.DEFAULT
        val result = selfPlay(rules, options, maxMoves = 20, seed = 3)

        assertTrue(result.moves > 0, "一手都没走出来")
        val perMove = result.elapsedMillis / result.moves
        assertTrue(
            perMove < 500,
            "默认参数下平均每手 ${perMove}ms，超出可接受范围（共 ${result.moves} 手，" +
                "${result.totalNodes} 个节点）",
        )
    }

    /**
     * 认不出的棋种要退回随机合法着法，而不是返回 null 把对局卡死。
     *
     * 这条保的是"以后加了新棋种但忘了写估值器"时的行为：
     * 机器人应该下得差，不该整个不动。围棋（阶段 5）接进来时会先走到这条路上。
     */
    @Test
    fun `未注册估值器的棋种退回随机合法着法`() {
        val rules = UnknownRules()
        assertNull(BotBrain.evaluatorFor(rules, BotOptions.DEFAULT))
        val board = rules.initialBoard()
        val move = assertNotNull(
            BotBrain.chooseMove(rules, board, Side.FIRST, BotOptions.DEFAULT, Random(1)),
            "认不出的棋种没有退回随机着法",
        )
        assertNull(rules.rejectionOf(board, move.from, move.to))
    }

    /**
     * 一个没有专属估值器的棋种，用来验证兜底路径。
     *
     * 直接复用五子棋规则的行为，但类型上不是 `GomokuRules`，
     * 所以 `BotBrain.evaluatorFor` 认不出它。
     */
    private class UnknownRules : GameRules {
        private val delegate = GomokuRules()
        override val spec get() = delegate.spec
        override val isPlacement get() = delegate.isPlacement
        override fun initialBoard() = delegate.initialBoard()
        override fun legalMoves(board: linmumua.chess.rule.core.Board, side: Side) =
            delegate.legalMoves(board, side)
        override fun legalMovesFrom(board: linmumua.chess.rule.core.Board, square: linmumua.chess.rule.core.Square) =
            delegate.legalMovesFrom(board, square)
        override fun rejectionOf(
            board: linmumua.chess.rule.core.Board,
            from: linmumua.chess.rule.core.Square,
            to: linmumua.chess.rule.core.Square,
        ) = delegate.rejectionOf(board, from, to)
        override fun rejectionOf(
            board: linmumua.chess.rule.core.Board,
            from: linmumua.chess.rule.core.Square,
            to: linmumua.chess.rule.core.Square,
            mover: Side,
        ) = delegate.rejectionOf(board, from, to, mover)
        override fun moveAt(
            board: linmumua.chess.rule.core.Board,
            from: linmumua.chess.rule.core.Square,
            to: linmumua.chess.rule.core.Square,
        ) = delegate.moveAt(board, from, to)
        override fun apply(board: linmumua.chess.rule.core.Board, move: linmumua.chess.rule.core.Move) =
            delegate.apply(board, move)
        override fun undo(board: linmumua.chess.rule.core.Board, move: linmumua.chess.rule.core.Move) =
            delegate.undo(board, move)
        override fun needsWarning(board: linmumua.chess.rule.core.Board, side: Side) =
            delegate.needsWarning(board, side)
        override fun outcome(board: linmumua.chess.rule.core.Board, sideToMove: Side) =
            delegate.outcome(board, sideToMove)
        override fun toFen(board: linmumua.chess.rule.core.Board) = delegate.toFen(board)
        override fun fromFen(fen: String) = delegate.fromFen(fen)
    }
}
