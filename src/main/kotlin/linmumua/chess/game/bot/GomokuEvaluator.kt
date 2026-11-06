package linmumua.chess.game.bot

import linmumua.chess.rule.core.Board
import linmumua.chess.rule.core.Move
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.core.Square
import linmumua.chess.rule.gomoku.GomokuRules

/**
 * 五子棋估值器。
 *
 * 三件事：
 * 1. **必胜必防**（[forcedMove]）—— 自己能连五就下，对手能连五就堵。不进搜索。
 * 2. **候选裁剪**（[candidates]）—— 只看已有子附近的空点，按威胁分排序。
 * 3. **静态估值**（[evaluate]）—— 己方威胁减对方威胁，见 [GomokuThreat]。
 *
 * 纯逻辑，不含 Bukkit 类型。
 *
 * @param rules 规则实例，用来判"这手是否成五"与生成合法着法
 * @param options 搜索参数，这里只用候选半径与候选上限
 */
class GomokuEvaluator(
    private val rules: GomokuRules,
    private val options: BotOptions = BotOptions.DEFAULT,
) : BotEvaluator {

    override val winScore: Int get() = GomokuThreat.FIVE

    /**
     * 五子棋无处可下就是满盘和棋，得分 0。
     *
     * 写成负分会让机器人在快满盘时为了"避免和棋"去下明显更差的手。
     */
    override val scoreWhenNoMoves: Int get() = 0

    override fun evaluate(board: Board, side: Side): Int = GomokuThreat.boardScore(board, side)

    /**
     * 候选着法：已有子周围 [BotOptions.candidateRadius] 格内的空点，按威胁分降序。
     *
     * 为什么必须裁剪：15×15 空盘有 225 个空点，两层搜索就是 5 万个局面，
     * 而且离战场十格远的点在五子棋里毫无意义 —— 五子棋的价值完全是局部的。
     *
     * 排序用"进攻分 + 防守分"：既要看自己下这里能成什么型，也要看对手下这里能成什么型。
     * 只按进攻排序的机器人会一路自己连子，完全无视对手的活三。
     *
     * 空盘（对手也还没下）时返回天元一点：五子棋开局下中心是常识，
     * 而空盘上所有点的威胁分都是 0，不特判会退化成随机选点。
     */
    override fun candidates(board: Board, side: Side): List<Move> {
        val occupied = board.spec.squares().filter { !board.isEmpty(it) }.toList()
        if (occupied.isEmpty()) {
            val center = Square(board.spec.files / 2, board.spec.ranks / 2)
            return listOf(GomokuRules.placementAt(center, side))
        }

        val nearby = nearbyEmptySquares(board, occupied)
        if (nearby.isEmpty()) {
            // 附近全满（极少见，只有棋子铺开到无空隙时）；退回全部合法着法，
            // 不能返回空 —— 那会被搜索当成"无着可走"判成和棋
            return rules.legalMoves(board, side)
        }

        return nearby
            .sortedByDescending { scoreForOrdering(board, it, side) }
            .take(options.maxCandidates)
            .map { GomokuRules.placementAt(it, side) }
    }

    /**
     * 判"这手落下后是否成五"。
     *
     * 调用时 move 已经落在 board 上，所以直接查它所在的四条线。
     */
    override fun isWinningMove(board: Board, move: Move): Boolean =
        rules.isWinningPlacement(board, move.to, move.piece.side)

    /**
     * 必胜必防。
     *
     * 顺序不能反：**先看自己能不能赢**，再看要不要堵。
     * 反过来的话，双方都成活四时机器人会去堵对手而不是自己先连五 ——
     * 己方已经能连五时，堵不堵对手都无所谓，赢了就结束了。
     *
     * 这一手不走搜索，因此深度配成 1 层、候选被 maxCandidates 截断，
     * 它也一定出得来。这就是"会玩"与"随便下"的分界。
     */
    override fun forcedMove(board: Board, side: Side): Move? {
        val empties = board.emptySquares()
        // 自己能连五：直接赢
        empties.firstOrNull { rules.isWinningPlacement(board, it, side) }
            ?.let { return GomokuRules.placementAt(it, side) }
        // 对手能连五：占掉那个点
        empties.firstOrNull { rules.isWinningPlacement(board, it, side.opposite) }
            ?.let { return GomokuRules.placementAt(it, side) }
        return null
    }

    /**
     * 候选排序分：自己下这里的价值 + 阻断对手的价值。
     *
     * 防守项按 [GomokuThreat.DEFENSE_ORDER_WEIGHT] 略微加权（110%），
     * 因为五子棋先手优势极大，同分时先看防守点更稳。
     *
     * 这个偏向只影响**先看哪些点**，不影响分数比较，所以不会像放进
     * `boardScore` 那样破坏估值的反对称性。
     */
    private fun scoreForOrdering(board: Board, square: Square, side: Side): Int =
        GomokuThreat.offenseScore(board, square, side) +
            GomokuThreat.defenseScore(board, square, side) *
            GomokuThreat.DEFENSE_ORDER_WEIGHT / 100

    /**
     * 已有子周围半径内的空点。
     *
     * 用 packed 值去重：`Square` 是 value class，直接放进 HashSet 会装箱，
     * 而这个方法每个搜索节点都要跑一次。
     */
    private fun nearbyEmptySquares(board: Board, occupied: List<Square>): List<Square> {
        val radius = options.candidateRadius
        val seen = HashSet<Int>()
        val result = ArrayList<Square>()
        for (origin in occupied) {
            for (deltaRank in -radius..radius) {
                for (deltaFile in -radius..radius) {
                    if (deltaFile == 0 && deltaRank == 0) {
                        continue
                    }
                    val target = origin.offset(deltaFile, deltaRank) ?: continue
                    if (!board.isEmpty(target)) {
                        continue
                    }
                    if (seen.add(target.packed)) {
                        result += target
                    }
                }
            }
        }
        return result
    }
}
