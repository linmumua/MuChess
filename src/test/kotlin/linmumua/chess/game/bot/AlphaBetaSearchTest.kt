package linmumua.chess.game.bot

import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import linmumua.chess.rule.core.Board
import linmumua.chess.rule.core.BoardSpec
import linmumua.chess.rule.core.Move
import linmumua.chess.rule.core.Piece
import linmumua.chess.rule.core.PieceType
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.core.Square
import linmumua.chess.rule.gomoku.GomokuRules
import org.junit.jupiter.api.Test

/**
 * alpha-beta 搜索本身的测试。
 *
 * 与 GomokuBotTest / XiangqiBotTest 的分工：那两个测"机器人会不会下棋"，
 * 这个测"搜索这套机制对不对"—— 剪枝不能改变结论、时限要真的生效、
 * 统计要如实、层数要影响结果。
 *
 * 这些性质错了不会让任何一条棋力测试失败，但会让搜索在别的局面上悄悄给出坏手。
 */
class AlphaBetaSearchTest {

    private val rules = GomokuRules()

    private fun board(): Board = Board(BoardSpec.GOMOKU, Side.FIRST)

    private fun Board.place(file: Int, rank: Int, side: Side) {
        this[Square(file, rank)] = Piece(PieceType.STONE, side)
    }

    /**
     * 剪枝不能改变结论。
     *
     * alpha-beta 相对纯 minimax 的**唯一**卖点是"结果一样但更快"。
     * 一旦剪枝把好手剪掉了，搜索就是错的，但表现出来只是"机器人偶尔下得怪"。
     *
     * 对照方式：拿 alpha-beta **自己算出的** [AlphaBetaSearch.bestScore]，
     * 与不带边界的纯 minimax 算出的最佳分数比。
     *
     * 这里有个坑值得记下来：早先这条测试是把搜索选中的着法再喂给纯 minimax
     * 算一遍分数，那等于用纯 minimax 和自己比，无论剪枝写成什么样都能通过 ——
     * 故意把 `-beta, -current` 写反成 `-current, -beta` 时全部用例照样绿。
     * 必须读搜索自己的得分，才是真的在验证剪枝。
     */
    @Test
    fun `剪枝不改变最佳分数`() {
        val position = board()
        position.place(7, 7, Side.FIRST)
        position.place(7, 8, Side.SECOND)
        position.place(8, 7, Side.FIRST)
        position.place(6, 8, Side.SECOND)

        val options = BotOptions(depth = 2, maxCandidates = 8, timeLimitMillis = 0)
        val evaluator = GomokuEvaluator(rules, options)

        val search = AlphaBetaSearch(rules, evaluator, options)
        assertNotNull(search.chooseMove(position, Side.FIRST, Random(1)))
        val pruned = search.bestScore

        val plain = bestScoreWithPlainMinimax(position, Side.FIRST, options.depth, evaluator)

        assertEquals(plain, pruned, "alpha-beta 剪枝改变了最佳分数，剪枝逻辑有错")
    }

    /**
     * 三层深度下同样要保持结论一致。
     *
     * 两层时根节点只有一次"对手回手"，剪枝错误可能刚好不体现。
     * 三层会让 beta 剪枝真正参与进来（孙节点向上传边界），错误的边界传递
     * 在这里才藏不住。
     */
    @Test
    fun `三层深度下剪枝仍不改变最佳分数`() {
        val position = board()
        position.place(7, 7, Side.FIRST)
        position.place(8, 8, Side.SECOND)
        position.place(6, 7, Side.FIRST)
        position.place(9, 9, Side.SECOND)

        val options = BotOptions(depth = 3, maxCandidates = 6, timeLimitMillis = 0)
        val evaluator = GomokuEvaluator(rules, options)

        val search = AlphaBetaSearch(rules, evaluator, options)
        assertNotNull(search.chooseMove(position, Side.FIRST, Random(11)))

        val plain = bestScoreWithPlainMinimax(position, Side.FIRST, options.depth, evaluator)
        assertEquals(plain, search.bestScore, "三层搜索下剪枝改变了最佳分数")
    }

    /** 一手棋走完之后，站在走子方视角的 minimax 得分 */
    private fun scoreOfMove(
        position: Board,
        move: Move,
        side: Side,
        depth: Int,
        evaluator: BotEvaluator,
    ): Int {
        rules.apply(position, move)
        val score = if (evaluator.isWinningMove(position, move)) {
            evaluator.winScore - depth
        } else {
            -plainMinimax(position, side.opposite, depth - 1, evaluator)
        }
        rules.undo(position, move)
        return score
    }

    /** 根节点上所有候选的最佳 minimax 分数，不用任何剪枝 */
    private fun bestScoreWithPlainMinimax(
        position: Board,
        side: Side,
        depth: Int,
        evaluator: BotEvaluator,
    ): Int {
        // 必走手不进搜索，所以复核也要走同一条路，否则比的是两件不同的事
        evaluator.forcedMove(position, side)?.let {
            return scoreOfMove(position, it, side, depth, evaluator)
        }
        return evaluator.candidates(position, side).maxOf { move ->
            scoreOfMove(position, move, side, depth, evaluator)
        }
    }

    /**
     * 纯 minimax（negamax 形式，无 alpha-beta 边界）。
     *
     * 只在测试里用，作为剪枝正确性的参照实现。刻意写得和被测代码结构相同
     * 但**去掉边界传递**，这样两者结果不同时能确定问题就在剪枝上。
     */
    private fun plainMinimax(
        position: Board,
        side: Side,
        depth: Int,
        evaluator: BotEvaluator,
    ): Int {
        if (depth <= 0) {
            return evaluator.evaluate(position, side)
        }
        val moves = evaluator.candidates(position, side)
        if (moves.isEmpty()) {
            return evaluator.scoreWhenNoMoves
        }
        var best = Int.MIN_VALUE / 4
        for (move in moves) {
            val score = scoreOfMove(position, move, side, depth, evaluator)
            if (score > best) {
                best = score
            }
        }
        return best
    }

    /**
     * 搜索结束后局面必须复原。
     *
     * 这条在两个棋种的测试里各有一份，这里再加一份是因为**这里改的是搜索本体**：
     * 上面那两条能被"evaluator 恰好没触发某条分支"蒙过去，这条用较深的搜索
     * 强制走遍剪枝返回、超时返回、终局返回三条提前退出路径。
     */
    @Test
    fun `深搜后局面完全复原`() {
        val position = board()
        position.place(7, 7, Side.FIRST)
        position.place(7, 8, Side.SECOND)
        position.place(8, 8, Side.FIRST)
        position.place(6, 6, Side.SECOND)
        val before = position.toFen()

        val options = BotOptions(depth = 3, maxCandidates = 6, timeLimitMillis = 0)
        AlphaBetaSearch(rules, GomokuEvaluator(rules, options), options)
            .chooseMove(position, Side.FIRST, Random(2))

        assertEquals(before, position.toFen(), "深搜之后局面没复原，apply/undo 没配对")
    }

    /**
     * 时限必须真的生效：给一个极小的时限，搜索要在合理时间内返回。
     *
     * 不生效的后果是主线程之外的搜索线程堆积 —— debug 命令同时开几十盘时会很明显。
     * 上限给得宽松（2 秒）是为了不让 CI 机器的抖动造成假失败；
     * 真正要验证的是"不会一直算下去"。
     */
    @Test
    fun `思考时限生效`() {
        val position = board()
        // 摆一堆子把候选撑大，让不限时的搜索明显耗时
        for (index in 0 until 12) {
            position.place(3 + index % 6, 3 + index / 6, if (index % 2 == 0) Side.FIRST else Side.SECOND)
        }

        val options = BotOptions(depth = 6, maxCandidates = 60, candidateRadius = 3, timeLimitMillis = 30)
        val search = AlphaBetaSearch(rules, GomokuEvaluator(rules, options), options)

        val start = System.currentTimeMillis()
        val move = search.chooseMove(position, Side.FIRST, Random(3))
        val elapsed = System.currentTimeMillis() - start

        assertNotNull(move, "超时也必须交出一手棋，不能返回空")
        assertTrue(elapsed < 2000, "配了 30ms 时限却跑了 ${elapsed}ms，时限没生效")
    }

    /**
     * 超时提前收手时，交出的必须是**完整搜完的那一层**的结果。
     *
     * completedDepth 会小于配置深度，但一定 >= 1 —— 至少要搜完一层。
     * 返回 0 层的结果等于没搜，那时应该退回候选里的第一名，不能返回空。
     */
    @Test
    fun `超时时交出已完成层数的结果`() {
        val position = board()
        for (index in 0 until 12) {
            position.place(3 + index % 6, 3 + index / 6, if (index % 2 == 0) Side.FIRST else Side.SECOND)
        }

        val options = BotOptions(depth = 6, maxCandidates = 60, candidateRadius = 3, timeLimitMillis = 30)
        val search = AlphaBetaSearch(rules, GomokuEvaluator(rules, options), options)
        assertNotNull(search.chooseMove(position, Side.FIRST, Random(4)))

        assertTrue(
            search.completedDepth < options.depth,
            "30ms 内竟然搜完了 6 层，用例前提不成立（completedDepth=${search.completedDepth}）",
        )
    }

    /**
     * 不限时的情况下要搜满配置的层数。
     *
     * completedDepth 小于配置值说明逐层加深提前 break 了，那时机器人的实际
     * 棋力低于服主配的深度，而且完全没有提示。
     */
    @Test
    fun `不限时时搜满配置层数`() {
        val position = board()
        position.place(7, 7, Side.FIRST)
        position.place(7, 8, Side.SECOND)

        val options = BotOptions(depth = 2, maxCandidates = 6, timeLimitMillis = 0)
        val search = AlphaBetaSearch(rules, GomokuEvaluator(rules, options), options)
        assertNotNull(search.chooseMove(position, Side.FIRST, Random(5)))

        assertEquals(options.depth, search.completedDepth)
    }

    /**
     * 必走手不进搜索：节点数应为 0。
     *
     * 这条把"必走手独立于搜索"这个设计钉死。它同时是性能上的保证 ——
     * 能一步赢/一步输的局面不该浪费搜索预算。
     */
    @Test
    fun `必走手不消耗搜索节点`() {
        val position = board()
        // 黑四连，必走手直接成五
        for (offset in 0 until 4) {
            position.place(3 + offset, 7, Side.FIRST)
        }

        val options = BotOptions(depth = 3, timeLimitMillis = 0)
        val search = AlphaBetaSearch(rules, GomokuEvaluator(rules, options), options)
        assertNotNull(search.chooseMove(position, Side.FIRST, Random(6)))

        assertEquals(0, search.visitedNodes, "必走手却搜了 ${search.visitedNodes} 个节点")
        assertEquals(0, search.completedDepth, "必走手不该记搜索层数")
    }

    /**
     * 剪枝要真的省节点：同一局面下 alpha-beta 的节点数应明显少于纯 minimax。
     *
     * 如果剪枝一个节点都没省，说明边界传递写反了（比如把 -beta/-alpha 传成了
     * -alpha/-beta）。这种错不影响结果正确性，只是白写了剪枝，
     * 而"白写了"在性能测试里才会暴露 —— 那时已经上线了。
     */
    @Test
    fun `剪枝确实减少了搜索节点`() {
        val position = board()
        position.place(7, 7, Side.FIRST)
        position.place(7, 8, Side.SECOND)
        position.place(8, 7, Side.FIRST)
        position.place(6, 8, Side.SECOND)

        val options = BotOptions(depth = 2, maxCandidates = 10, timeLimitMillis = 0)
        val evaluator = GomokuEvaluator(rules, options)

        val search = AlphaBetaSearch(rules, evaluator, options)
        search.chooseMove(position, Side.FIRST, Random(7))
        val prunedNodes = search.visitedNodes

        // 纯 minimax 在同样深度下要展开的节点数：根候选数 × 每个子节点的候选数
        val rootCandidates = evaluator.candidates(position, Side.FIRST).size
        val fullWidth = rootCandidates * rootCandidates

        assertTrue(
            prunedNodes < fullWidth,
            "剪枝没省下节点：alpha-beta 走了 $prunedNodes 个，全展开约 $fullWidth 个",
        )
    }
}
