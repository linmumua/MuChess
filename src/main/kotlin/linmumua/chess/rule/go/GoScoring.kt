package linmumua.chess.rule.go

import linmumua.chess.rule.core.Board
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.core.Square

/**
 * 简化数子（中国数子法）。
 *
 * 口径：**己方盘上子数 + 只被己方围住的空点**，白方另加贴子。
 * 两色都能到的空点（公气）不计入任何一方。
 *
 * 选数子法而不是数目法（日韩）的直接好处：只看终局盘面就能算出结果，
 * 不需要记录整局的提子累计数，因此 `BoardRecord` 与存档编解码零改动。
 *
 * ## 与正式中国规则的差别（必须知道）
 *
 * - **不做死活判定**。盘上没提干净的死子会被算成活子。正式规则下终局要先确认
 *   死子并清理，这里做不到，所以双方应当自己把该提的提干净再连续虚着。
 * - **不做还棋头**。
 *
 * 这是"能判出胜负"的简化实现，不是竞赛级裁判。棋力接近时结果可能与正式规则不同。
 *
 * 不含任何 Bukkit 类型，可脱离服务端跑单测。
 */
object GoScoring {

    /**
     * 数子并给出双方得分。
     *
     * @param komi 贴给后行方（白）的子数
     */
    fun score(board: Board, komi: Double): GoScore {
        var firstStones = 0
        var secondStones = 0
        for (square in board.spec.squares()) {
            when (board[square]?.side) {
                Side.FIRST -> firstStones++
                Side.SECOND -> secondStones++
                null -> Unit
            }
        }

        val territory = territories(board)

        return GoScore(
            firstScore = (firstStones + territory.first).toDouble(),
            // 贴子只加给白方，加在子数与围空之后
            secondScore = (secondStones + territory.second).toDouble() + komi,
        )
    }

    /**
     * 数出双方各自"只被己方围住"的空点数。
     *
     * @return first 为黑方围空数，second 为白方围空数
     */
    private fun territories(board: Board): Pair<Int, Int> {
        var firstTerritory = 0
        var secondTerritory = 0
        for (region in emptyRegions(board)) {
            when (region.owner) {
                Side.FIRST -> firstTerritory += region.points.size
                Side.SECOND -> secondTerritory += region.points.size
                null -> Unit
            }
        }
        return firstTerritory to secondTerritory
    }

    /**
     * 把盘上的空点按连通区域分解（只认四邻），并记下每片贴着哪些颜色。
     *
     * 按连通区域整片判而不是逐点看四邻，是因为围空的归属是区域性质：
     * 一个大空区中央的点四邻全是空点，逐点看根本判不出它属于谁。
     *
     * 之所以做成 public：机器人裁候选时也要知道"哪片空区已经被一色围死"
     * （见 `GoInfluence.settledEmptyPoints`）。两处必须用同一个定义 ——
     * 各写一遍 flood 迟早会分叉，那时机器人避让的区域与真正计分的区域不是同一片。
     */
    fun emptyRegions(board: Board): List<EmptyRegion> {
        val visited = HashSet<Square>()
        val regions = ArrayList<EmptyRegion>()

        for (start in board.spec.squares()) {
            if (board[start] != null || start in visited) {
                continue
            }
            // 扩散出一整片相连空点，同时记下这片贴着哪些颜色
            val region = ArrayList<Square>()
            val borderSides = HashSet<Side>()
            val queue = ArrayDeque<Square>()
            queue.addLast(start)
            visited += start
            region += start

            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                for (neighbor in GoBoardOps.neighborsOf(board, current)) {
                    val occupant = board[neighbor]
                    if (occupant != null) {
                        borderSides += occupant.side
                        continue
                    }
                    if (visited.add(neighbor)) {
                        region += neighbor
                        queue.addLast(neighbor)
                    }
                }
            }
            regions += EmptyRegion(region, borderSides)
        }
        return regions
    }
}

/**
 * 一片相连的空点，以及它贴着的颜色。
 *
 * @param points 这片区域的所有空点
 * @param borderSides 这片区域四邻碰到过的颜色，空盘时为空集
 */
data class EmptyRegion(
    val points: List<Square>,
    val borderSides: Set<Side>,
) {

    /**
     * 这片空区归谁：只贴一种颜色就归那一方，公气或空盘为 null。
     */
    val owner: Side?
        get() = if (borderSides.size == 1) borderSides.first() else null
}

/**
 * 数子结果。
 *
 * 分数用 [Double] 而不是 [Int]：贴子是 3.75 这类小数，
 * 用整数会把它截断成 3，白方凭空少 0.75 子。
 */
data class GoScore(
    /** 先行方（黑）得分 */
    val firstScore: Double,
    /** 后行方（白）得分，含贴子 */
    val secondScore: Double,
) {

    /**
     * 胜方，平局返回 null。
     *
     * 默认贴子 3.75 带 .75 尾数，双方得分不可能相等，所以平局实际只在
     * 服主把贴子配成整数时才可能出现。仍然要处理 —— 配置允许配 0。
     */
    val winner: Side?
        get() = when {
            firstScore > secondScore -> Side.FIRST
            secondScore > firstScore -> Side.SECOND
            else -> null
        }
}
