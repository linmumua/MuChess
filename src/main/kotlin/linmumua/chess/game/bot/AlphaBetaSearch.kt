package linmumua.chess.game.bot

import kotlin.random.Random
import linmumua.chess.rule.core.Board
import linmumua.chess.rule.core.GameRules
import linmumua.chess.rule.core.Move
import linmumua.chess.rule.core.Side

/**
 * 极小化极大搜索 + alpha-beta 剪枝。
 *
 * 算法思路来自公开资料，**代码为本项目自行实现，未拷贝任何仓库**：
 * - Russell & Norvig《Artificial Intelligence: A Modern Approach》第三版 5.3 节
 *   的 alpha-beta 伪码（negamax 形式）
 * - 五子棋侧的"威胁分级估值 + 候选点限制在已有子附近"是该领域通行做法，
 *   参考过 GitHub 上 JMOriggi/AI-Minimax-Algorithm-for-Gomoku、JiachenRen/GomokuZero
 *   等仓库的**思路描述**（威胁空间搜索、活四/冲四分级），实现从零写起。
 *
 * 这一层完全不懂棋种：候选、估值、终局判定全部委托给 [BotEvaluator]。
 * 所以同一份搜索同时服务象棋与五子棋，加棋种只要写一个 evaluator。
 *
 * 纯逻辑，不含 Bukkit 类型，可脱离服务端单测。
 *
 * 状态约定：搜索**会在传入的 board 上试走再撤销**，结束时局面与传入时一致。
 * 不整盘拷贝是因为一次搜索要试走成千上万次，每次拷贝 225 格开销太大。
 * 调用方若要并发搜索同一副棋盘，必须各自先 [Board.copy]。
 *
 * @param rules 走子与撤销走同一套规则，不给机器人开后门
 * @param evaluator 棋种相关的估值与候选
 * @param options 搜索参数
 */
class AlphaBetaSearch(
    private val rules: GameRules,
    private val evaluator: BotEvaluator,
    private val options: BotOptions = BotOptions.DEFAULT,
) {

    /** 本次搜索访问过的节点数，性能测试要看它 */
    var visitedNodes: Int = 0
        private set

    /** 本次搜索实际完成的层数，超时提前收手时会小于 [BotOptions.depth] */
    var completedDepth: Int = 0
        private set

    /**
     * 最后一层完整搜完时，选中着法的得分。
     *
     * 必走手不经过搜索，此时保持 0。
     *
     * 暴露出来有两个用处：debug 性能测试可以打出机器人对局面的判断，
     * 单测可以拿它与不带剪枝的纯 minimax 对照 —— 剪枝的唯一卖点是
     * "结果一样但更快"，没有这个值就没法验证"结果一样"那半句。
     */
    var bestScore: Int = 0
        private set

    /**
     * 选一手棋。
     *
     * 顺序是"必走手 → 逐层加深搜索"：
     * 必走手（自己能赢、对手要赢）不进搜索，这样即使深度配成 1 层或候选被截断，
     * 机器人也一定会赢该赢的、堵该堵的。
     *
     * 逐层加深而不是一次搜到底：浅层结果能给深层做着法排序，且超时时手里
     * 始终有一份**完整**的上层结果。只搜一次到底的话超时就只能交半层结果，
     * 那份结果里有的分支算过有的没算，选出来的着法可能比不搜还差。
     *
     * @param board 当前局面，搜索结束后保持原样
     * @param side 要走棋的一方
     * @param random 同分打散用，注入以便单测复现
     * @return 选中的着法；无合法着法时返回 null
     */
    fun chooseMove(board: Board, side: Side, random: Random = Random.Default): Move? {
        visitedNodes = 0
        completedDepth = 0
        bestScore = 0

        evaluator.forcedMove(board, side)?.let {
            // 必走手不计入搜索层数：它根本没搜
            return it
        }

        var candidates = evaluator.candidates(board, side)
        if (candidates.isEmpty()) {
            return null
        }
        if (candidates.size == 1) {
            return candidates.first()
        }

        val deadline = deadlineFrom(System.currentTimeMillis())
        var best: Move? = null

        // 逐层加深：每层都从上一层的最佳着法开始试，剪枝效率更高
        for (depth in 1..options.depth) {
            val result = searchRoot(board, side, depth, candidates, deadline, random)
                ?: break // 这一层没搜完，丢掉半成品，保留上一层结果
            best = result.move
            completedDepth = depth
            bestScore = result.score
            // 用本层的排序结果给下一层做着法顺序
            candidates = result.ordered
        }

        return best ?: candidates.first()
    }

    /** 根节点搜索结果：选中的着法、它的得分、重排后的候选顺序 */
    private class RootResult(val move: Move, val score: Int, val ordered: List<Move>)

    /**
     * 根节点一层搜索。
     *
     * 与递归节点分开写，因为根节点要额外做两件事：记住哪一手最好、
     * 把候选按本层得分重排给下一层用。
     *
     * @return 搜完返回结果；中途超时返回 null，调用方据此丢掉这一层
     */
    private fun searchRoot(
        board: Board,
        side: Side,
        depth: Int,
        candidates: List<Move>,
        deadline: Long?,
        random: Random,
    ): RootResult? {
        var alpha = -INFINITY
        val scored = ArrayList<Pair<Move, Int>>(candidates.size)

        for (move in candidates) {
            if (timedOut(deadline)) {
                return null
            }
            rules.apply(board, move)
            val score = if (evaluator.isWinningMove(board, move)) {
                // 这一手直接赢了，不再往下搜。减 depth 让"早点赢"优于"晚点赢"，
                // 否则机器人可能选一条同样能赢但绕远的路。
                evaluator.winScore - depth
            } else {
                -negamax(board, side.opposite, depth - 1, -INFINITY, -alpha, deadline)
            }
            rules.undo(board, move)

            scored += move to score
            if (score > alpha) {
                alpha = score
            }
        }

        if (timedOut(deadline)) {
            return null
        }

        // 局部名不叫 bestScore，避免与同名属性混淆
        val topScore = scored.maxOf { it.second }
        val bestMoves = scored.filter { it.second == topScore }.map { it.first }
        // 同分打散：不打散的话机器人每局开头都走同一步，看着很假
        val chosen = bestMoves[random.nextInt(bestMoves.size)]
        val ordered = scored.sortedByDescending { it.second }.map { it.first }
        return RootResult(chosen, topScore, ordered)
    }

    /**
     * negamax 递归，站在"当前该走棋一方"视角。
     *
     * negamax 形式而不是分 max/min 两套：两方评价函数完全对称（`evaluate` 带 side），
     * 所以对手的得分就是自己得分的相反数，一套代码够了。
     *
     * @param alpha 当前一方已知能保证的下限
     * @param beta 对手已知不会允许超过的上限；超过就剪枝
     */
    private fun negamax(
        board: Board,
        side: Side,
        depth: Int,
        alpha: Int,
        beta: Int,
        deadline: Long?,
    ): Int {
        visitedNodes++

        if (depth <= 0) {
            return evaluator.evaluate(board, side)
        }
        // 超时就退回静态估值。这里不能返回一个哨兵值：叶子返回的分数会一路
        // 参与父节点比较，塞个假分数会污染整条分支。
        if (timedOut(deadline)) {
            return evaluator.evaluate(board, side)
        }

        val moves = evaluator.candidates(board, side)
        if (moves.isEmpty()) {
            // 象棋无着可走是负（将死/困毙），五子棋是和棋，由 evaluator 决定
            return evaluator.scoreWhenNoMoves
        }

        var current = alpha
        for (move in moves) {
            rules.apply(board, move)
            val score = if (evaluator.isWinningMove(board, move)) {
                evaluator.winScore - depth
            } else {
                -negamax(board, side.opposite, depth - 1, -beta, -current, deadline)
            }
            rules.undo(board, move)

            if (score >= beta) {
                // 对手不会走到这个局面来，这条分支不必再算
                return score
            }
            if (score > current) {
                current = score
            }
        }
        return current
    }

    /** 思考时限的绝对时间点，0 表示不限时 */
    private fun deadlineFrom(now: Long): Long? =
        if (options.timeLimitMillis <= 0) null else now + options.timeLimitMillis

    private fun timedOut(deadline: Long?): Boolean =
        deadline != null && System.currentTimeMillis() >= deadline

    private companion object {
        /**
         * alpha-beta 的初始边界。
         *
         * 必须比任何 evaluator 的 winScore 都大，否则"赢"这个分数会被边界夹掉，
         * 机器人就分不出"赢"和"很占优"。用 Int.MAX_VALUE/2 留出取负与减 depth 的余量，
         * 直接用 MAX_VALUE 会在取负加减时溢出。
         */
        const val INFINITY = Int.MAX_VALUE / 2
    }
}
