package linmumua.chess.game.bot

import kotlin.random.Random
import linmumua.chess.rule.core.Board
import linmumua.chess.rule.core.GameRules
import linmumua.chess.rule.core.Move
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.go.GoRules
import linmumua.chess.rule.gomoku.GomokuRules
import linmumua.chess.rule.xiangqi.XiangqiRules

/**
 * 机器人选步入口。
 *
 * 按规则实例挑对应的估值器，再交给同一份 [AlphaBetaSearch]。
 * 上层（`MoveExecutor`）只调 [chooseMove]，不关心是哪个棋种、搜了几层。
 *
 * 纯逻辑，不含 Bukkit 类型，可脱离服务端单测。
 *
 * ## 为什么不做成 object
 *
 * 搜索会在传入的 board 上试走再撤销，实例本身还记着 visitedNodes 这类统计。
 * 做成单例的话，debug 命令同时开几十盘机器人对局时统计会互相踩。
 * 每次选步现造一个实例，开销只是几个对象，相比搜索本身可以忽略。
 */
object BotBrain {

    /**
     * 为某棋种规则挑估值器。
     *
     * 认不出的规则返回 null，调用方退回随机合法着法 ——
     * 加了新棋种但忘了写估值器时，机器人应该"下得差"而不是"整个卡死"。
     */
    fun evaluatorFor(rules: GameRules, options: BotOptions): BotEvaluator? = when (rules) {
        is XiangqiRules -> XiangqiEvaluator(rules, options)
        is GomokuRules -> GomokuEvaluator(rules, options)
        // 围棋的候选上限单独一档：19 路要比五子棋更狠的裁剪，而调小共用的
        // options.maxCandidates 会连带削弱五子棋的防守
        is GoRules -> GoEvaluator(rules, options, goMaxCandidates)
        else -> null
    }

    /**
     * 围棋机器人的候选上限，由配置层在启动与重载时写入。
     *
     * 放这里而不是让 [evaluatorFor] 直接读 `ChessConfig`：本包是纯逻辑，
     * 不该依赖配置系统，否则单测要先把配置系统跑起来。
     */
    @Volatile
    var goMaxCandidates: Int = GoEvaluator.DEFAULT_MAX_CANDIDATES

    /**
     * 选一手棋。
     *
     * @param rules 该盘棋的规则实例
     * @param board 当前局面。搜索会在上面试走再撤销，返回时局面与传入时一致
     * @param side 要走棋的一方
     * @param options 搜索参数
     * @param random 同分打散用，注入以便单测复现
     * @return 选中的着法；无合法着法（将死、困毙、满盘）时返回 null
     */
    fun chooseMove(
        rules: GameRules,
        board: Board,
        side: Side,
        options: BotOptions = BotOptions.DEFAULT,
        random: Random = Random.Default,
    ): Move? {
        val evaluator = evaluatorFor(rules, options)
            ?: return fallbackMove(rules, board, side, random)
        return AlphaBetaSearch(rules, evaluator, options).chooseMove(board, side, random)
    }

    /**
     * 选一手棋并带回搜索统计。
     *
     * debug 性能测试要看节点数与实际搜索层数，正常对局用不到，
     * 所以单独一个方法，不让 [chooseMove] 的返回类型变复杂。
     */
    fun chooseMoveWithStats(
        rules: GameRules,
        board: Board,
        side: Side,
        options: BotOptions = BotOptions.DEFAULT,
        random: Random = Random.Default,
    ): SearchResult {
        val evaluator = evaluatorFor(rules, options)
            ?: return SearchResult(fallbackMove(rules, board, side, random), 0, 0)
        val search = AlphaBetaSearch(rules, evaluator, options)
        val move = search.chooseMove(board, side, random)
        return SearchResult(move, search.visitedNodes, search.completedDepth)
    }

    /** 认不出棋种时的兜底：随机一个合法着法，好过什么都不走 */
    private fun fallbackMove(rules: GameRules, board: Board, side: Side, random: Random): Move? {
        val moves = rules.legalMoves(board, side)
        if (moves.isEmpty()) {
            return null
        }
        return moves[random.nextInt(moves.size)]
    }

    /**
     * 一次选步的结果与统计。
     *
     * @param move 选中的着法，无着可走时为 null
     * @param visitedNodes 搜索访问的节点数
     * @param completedDepth 实际搜完的层数，超时提前收手时小于配置深度
     */
    data class SearchResult(
        val move: Move?,
        val visitedNodes: Int,
        val completedDepth: Int,
    )
}
