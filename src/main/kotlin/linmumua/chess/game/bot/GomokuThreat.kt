package linmumua.chess.game.bot

import linmumua.chess.rule.core.Board
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.core.Square

/**
 * 五子棋的威胁模式分级与打分。
 *
 * 思路来自五子棋 AI 的通行做法（活四 / 冲四 / 活三 / 眠三 / 活二 分级估值），
 * 参考过公开仓库的思路描述，**代码自行实现**。
 *
 * 纯函数，不碰对局状态也不碰 Bukkit。
 *
 * ## 已知简化
 *
 * 只识别**连续**棋型，不识别带空隙的跳棋型（如 `X_XX`、`XX_X`）。
 * 后果是机器人会低估"跳活三"这类威胁：对手做出 `XX_X` 时它可能不去堵，
 * 直到对手补成连续四子才反应。这是"勉强会玩"与"懂棋理"之间的差距，
 * 补上要把每条线按位串匹配，代价是估值开销上一个量级。
 * 必胜必防（对手能一步连五）不受此影响 —— 那条走 [GomokuEvaluator.forcedMove]，
 * 判的是"落子后是否成五"，跳型补上最后一子同样会被判出来。
 */
internal object GomokuThreat {

    /**
     * 一条线上的棋型。
     *
     * @param count 含假想落子在内的连续同色子数
     * @param openEnds 这段连续子的两端有几端是空点（0、1、2）
     */
    data class Line(val count: Int, val openEnds: Int)

    /**
     * 假设 [side] 在 [square] 落一子，算这一手在四个方向各自形成的棋型。
     *
     * 不需要真的落子：`countThrough` 把 origin 当作己方子计入，
     * 所以对空点直接调用就是"落子后"的结果。省掉一次 apply/undo，
     * 候选排序要对上百个点各算一遍，这个省法值得。
     */
    fun linesAt(board: Board, square: Square, side: Side): List<Line> =
        DIRECTIONS.map { (deltaFile, deltaRank) ->
            lineAt(board, square, side, deltaFile, deltaRank)
        }

    /** 单方向棋型 */
    fun lineAt(
        board: Board,
        square: Square,
        side: Side,
        deltaFile: Int,
        deltaRank: Int,
    ): Line {
        val forward = runLength(board, square, side, deltaFile, deltaRank)
        val backward = runLength(board, square, side, -deltaFile, -deltaRank)
        val count = 1 + forward + backward
        var openEnds = 0
        if (isOpenBeyond(board, square, deltaFile, deltaRank, forward + 1)) {
            openEnds++
        }
        if (isOpenBeyond(board, square, -deltaFile, -deltaRank, backward + 1)) {
            openEnds++
        }
        return Line(count, openEnds)
    }

    /**
     * 一手落子的进攻价值。
     *
     * 取四个方向中最高的那一档，再把其余方向的分数按一小部分加进去 ——
     * 同时在两个方向做出活三（双三）比只在一个方向做活三强得多，
     * 只取最大值会让机器人看不出双三的价值。
     */
    fun offenseScore(board: Board, square: Square, side: Side): Int {
        val scores = linesAt(board, square, side).map { scoreOf(it) }.sortedDescending()
        var total = scores.firstOrNull() ?: 0
        for (index in 1 until scores.size) {
            total += scores[index] / SECONDARY_DIVISOR
        }
        return total
    }

    /**
     * 一手落子的防守价值 = 这个点对**对手**的进攻价值。
     *
     * 对手在这里能做出的威胁越大，我们占掉它的收益就越大。
     */
    fun defenseScore(board: Board, square: Square, side: Side): Int =
        offenseScore(board, square, side.opposite)

    /**
     * 棋型打分。
     *
     * 档位之间刻意留出**数量级差距**而不是等差：一个活四必须压过任意多个活三之和，
     * 否则机器人会为了同时做几个活三而放着能赢的活四不走。
     */
    fun scoreOf(line: Line): Int {
        if (line.count >= WIN_LENGTH) {
            return FIVE
        }
        // 两端都被堵死的连子毫无价值，再长也长不到五
        if (line.openEnds == 0) {
            return 0
        }
        return when (line.count) {
            4 -> if (line.openEnds == 2) OPEN_FOUR else FOUR
            3 -> if (line.openEnds == 2) OPEN_THREE else CLOSED_THREE
            2 -> if (line.openEnds == 2) OPEN_TWO else CLOSED_TWO
            else -> if (line.openEnds == 2) OPEN_ONE else 0
        }
    }

    /**
     * 全盘静态估值，站在 [side] 视角。
     *
     * 每条连续同色段只数一次：只在"这一段的起点"上计分（沿该方向的上一格不是同色），
     * 否则一段三连会被它的三枚子各数一遍，长连的分数被平方式放大。
     *
     * ## 为什么两侧权重必须相等
     *
     * 这里是"己方威胁 − 对方威胁"，系数严格 1:1。曾经给对方威胁加过 1.1 倍偏向防守，
     * 那样会破坏估值的**反对称性**（`evaluate(b, A) == -evaluate(b, B)`），
     * 而 negamax 的整个正确性就建立在这条上 —— 它把对手的得分直接取负当自己的得分用。
     * 不反对称的估值会让不同层算出的分数互不可比，alpha-beta 的剪枝跟着全错，
     * 而且是静默的：机器人照样能下，只是选出来的手莫名其妙。
     *
     * 防守偏向改放在**候选排序**里（见 `GomokuEvaluator.candidates`）：
     * 排序不参与分数比较，加偏向不影响搜索的正确性，只影响先看哪些点。
     */
    fun boardScore(board: Board, side: Side): Int =
        runsScore(board, side) - runsScore(board, side.opposite)

    /** 某方所有连续段的分数之和 */
    private fun runsScore(board: Board, side: Side): Int {
        var total = 0
        for (square in board.squaresOf(side)) {
            for ((deltaFile, deltaRank) in DIRECTIONS) {
                // 只在段起点计分，避免同一段被重复统计
                val previous = square.offset(-deltaFile, -deltaRank)
                if (previous != null && board[previous]?.side == side) {
                    continue
                }
                val forward = runLength(board, square, side, deltaFile, deltaRank)
                val count = 1 + forward
                var openEnds = 0
                if (isOpenBeyond(board, square, deltaFile, deltaRank, count)) {
                    openEnds++
                }
                if (isOpenBeyond(board, square, -deltaFile, -deltaRank, 1)) {
                    openEnds++
                }
                total += scoreOf(Line(count, openEnds))
            }
        }
        return total
    }

    /** 从 [square] 沿方向走 [steps] 格那个点是不是空点（走出盘外算堵死） */
    private fun isOpenBeyond(
        board: Board,
        square: Square,
        deltaFile: Int,
        deltaRank: Int,
        steps: Int,
    ): Boolean {
        val target = square.offset(deltaFile * steps, deltaRank * steps) ?: return false
        return board.isEmpty(target)
    }

    /** 单向数同色连续子数，不含起点；遇空点、异色子或盘外即停 */
    private fun runLength(
        board: Board,
        origin: Square,
        side: Side,
        deltaFile: Int,
        deltaRank: Int,
    ): Int {
        var count = 0
        var cursor = origin.offset(deltaFile, deltaRank)
        while (cursor != null && board[cursor]?.side == side) {
            count++
            cursor = cursor.offset(deltaFile, deltaRank)
        }
        return count
    }

    /**
     * 四条判定方向。
     *
     * 只取四条：每条都往正反两边数，再列另外四条等于把每条线数两遍。
     * 与 `GomokuLine.DIRECTIONS` 同一组值，这里单独列一份是因为那个是 rule 层的
     * internal 细节，机器人不该依赖规则层的内部常量。
     */
    val DIRECTIONS = listOf(
        1 to 0,   // 横
        0 to 1,   // 竖
        1 to 1,   // 撇
        1 to -1,  // 捺
    )

    /** 连五取胜所需子数 */
    const val WIN_LENGTH = 5

    /** 成五：直接取胜 */
    const val FIVE = 1_000_000

    /** 活四：两端都空，对手堵不住，下一手必成五 */
    const val OPEN_FOUR = 100_000

    /** 冲四：一端被堵，对手堵另一端即可解 */
    const val FOUR = 10_000

    /** 活三：两端都空，不堵就成活四 */
    const val OPEN_THREE = 5_000

    /** 眠三：一端被堵 */
    const val CLOSED_THREE = 500

    /** 活二 */
    const val OPEN_TWO = 200

    /** 眠二 */
    const val CLOSED_TWO = 20

    /** 孤子两端皆空，价值极低但不为零，用于给候选点排序 */
    const val OPEN_ONE = 5

    /**
     * 次要方向的分数折算除数。
     *
     * 取 4：一个方向的活三算满分，另一个方向的活三再加四分之一。
     * 双活三因此明显高于单活三，但仍远低于一个活四。
     */
    const val SECONDARY_DIVISOR = 4

    /**
     * 候选**排序**时对手威胁的权重（百分比）。
     *
     * 取 110 略微偏向防守：五子棋先手优势极大，同分时先看防守点更稳。
     *
     * 只用于排序，**不能用在** [boardScore] 里 —— 那会破坏估值的反对称性，
     * 详见 [boardScore] 的说明。
     */
    const val DEFENSE_ORDER_WEIGHT = 110
}
