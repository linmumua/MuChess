package linmumua.chess.game.bot

import linmumua.chess.rule.core.Board
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.core.Square
import linmumua.chess.rule.go.GoBoardOps
import linmumua.chess.rule.go.GoScoring

/**
 * 围棋估值用到的纯量计算。
 *
 * 只做围棋里**定义明确、算得准**的三件事：数子分、气的安全性、值得考虑的点。
 * 刻意不做"厚薄""势""影响力图"这类项 —— 那些没有可靠的静态算法，
 * 编一个出来只是把随机数包装成看着专业的东西。
 *
 * 纯逻辑，不含 Bukkit 类型，可脱离服务端单测。
 */
object GoInfluence {

    /** 气数达到这个值就算安全，不再罚分 */
    const val SAFE_LIBERTIES = 3

    /** 只剩 1 气（被叫吃）的罚分，下一手就会被提，罚得最重 */
    const val ATARI_PENALTY = 8

    /** 只剩 2 气的罚分，还有周旋余地，罚轻一档 */
    const val LOW_LIBERTY_PENALTY = 2

    /** 收集候选时认为"危险"的气数上限，气数 ≤ 此值的块值得救或值得提 */
    const val DANGER_LIBERTIES = 2

    /**
     * 数子分差，[side] 视角。
     *
     * 直接用 [GoScoring] 的口径（子数 + 只被己方围住的空点），与终局判定同源 ——
     * 机器人的目标函数与真正的胜负判据必须是同一个，否则它会朝着一个赢不了的方向努力。
     *
     * **贴子不计入**：它是常数，对差值没有任何影响，算它只是白费。
     */
    fun scoreDifference(board: Board, side: Side): Int {
        // komi 传 0：这里要的是纯盘面差，贴子对差值无贡献
        val score = GoScoring.score(board, komi = 0.0)
        val mine = if (side == Side.FIRST) score.firstScore else score.secondScore
        val theirs = if (side == Side.FIRST) score.secondScore else score.firstScore
        // 数子分在不带贴子时必然是整数，转 Int 不丢精度
        return (mine - theirs).toInt()
    }

    /**
     * 气安全分，[side] 视角：己方危块的罚分之和取负，对方危块反向计入。
     *
     * 这一项是机器人"会逃被叫吃的子、会去叫吃对方"的全部来源。没有它，
     * 机器人对"这块下一手就被提掉 10 子"完全无感 —— 数子分要等真被提了才变化，
     * 那时已经晚了一手。
     *
     * 按**块**罚而不是按子罚，且罚分乘以块的大小：被叫吃的一块 10 子比一块 1 子
     * 严重得多，不乘大小会让机器人拿大块去换小块。
     */
    fun libertySafety(board: Board, side: Side): Int {
        var mine = 0
        var theirs = 0
        forEachGroup(board) { stones, liberties, groupSide ->
            val penalty = penaltyFor(liberties.size) * stones
            if (penalty == 0) {
                return@forEachGroup
            }
            if (groupSide == side) {
                mine += penalty
            } else {
                theirs += penalty
            }
        }
        // 己方危块扣分，对方危块加分
        return theirs - mine
    }

    /** 某个气数对应的罚分，安全则 0 */
    fun penaltyFor(liberties: Int): Int = when {
        liberties <= 0 -> ATARI_PENALTY
        liberties == 1 -> ATARI_PENALTY
        liberties == 2 -> LOW_LIBERTY_PENALTY
        else -> 0
    }

    /**
     * 盘上某一方的子数。
     *
     * 位置性落子惩罚要用它 —— 见 `GoEvaluator.evaluate` 的公式说明。
     */
    fun stoneCount(board: Board, side: Side): Int {
        var count = 0
        for (square in board.spec.squares()) {
            if (board[square]?.side == side) {
                count++
            }
        }
        return count
    }

    /**
     * 危块的气位：气数 ≤ [DANGER_LIBERTIES] 的块，它们的气就是关键点。
     *
     * 己方的危块 → 那些点是救命处；对方的危块 → 那些点是提子处。
     * 两者都必须显式收集，**不能指望"已有子附近"覆盖到** ——
     * 一块的救命点可能正好落在候选半径之外，漏了就等于机器人不会救子。
     *
     * @param side 要找哪一方的危块
     * @return 这些块的全部气点位，已去重
     */
    fun criticalPoints(board: Board, side: Side): Set<Square> {
        val points = LinkedHashSet<Square>()
        forEachGroup(board) { _, liberties, groupSide ->
            if (groupSide != side || liberties.size > DANGER_LIBERTIES) {
                return@forEachGroup
            }
            points += liberties
        }
        return points
    }

    /**
     * 已有子周围半径内的空点。
     *
     * 围棋的远处大场其实有价值，裁到附近纯粹是为了跑得动：19×19 中盘有 300 多个
     * 合法点，两层全搜要几十秒（实测 legalMoves 单次约 1ms）。这是可行性妥协，
     * 不是"远处无意义"——与五子棋裁剪的理由不同，别照搬那边的注释。
     *
     * 用 packed 去重：`Square` 是 value class，直接进 HashSet 会装箱，
     * 而这个方法每个搜索节点都要跑一次。
     */
    fun nearbyEmptySquares(board: Board, radius: Int): List<Square> {
        val seen = HashSet<Int>()
        val result = ArrayList<Square>()
        for (origin in board.spec.squares()) {
            if (board[origin] == null) {
                continue
            }
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

    /**
     * 已被单色围死的空点，机器人不该往这些点里下。
     *
     * ## 为什么必须有这一层
     *
     * [scoreDifference] 用的是 [GoScoring] 的口径，而那个口径下一片空区
     * **只在只贴一种颜色时**才算某方围空。于是往对方已围死的大空区里插一颗子，
     * 那片空区立刻变成公气、对方围空归零：实测 19 路一颗子能让分差跳 +153，
     * 估值 +1510。机器人当然会抢着下。
     *
     * 可那颗子在真实围棋里是死子，插进去毫无价值。判断它死活需要读棋，
     * 而这个机器人明确不做死活（见 `GoEvaluator` 类注释），所以没法靠估值修 ——
     * 只能从候选里把这类点摘掉。
     *
     * 两类点都在这里被摘掉，理由不同：
     * - **对方围死的区域** → 无法读活的打入，摘掉是避免上面那个估值悬崖
     * - **己方围死的区域** → 自填己空，本来就是 −1 的亏手
     *
     * 摘掉之后终局性就是结构性的了，不再依赖 `STONE_COST` 的算术：
     * 公气被填完之后每片空区都成了单色，候选里只剩虚着，双方连续虚着即终局。
     *
     * ## 代价
     *
     * 机器人从此**永不打入**，哪怕对方那片"围死"其实薄得能破。这是明确的棋力损失，
     * 但方向是安全的：不打入只会让它少赚，乱打入会让它把子送光还永不终局。
     *
     * ## 开局期护栏
     *
     * 任一方一颗子都没有时直接返回空集。否则开局黑下一手之后，全盘那片大空区
     * 只贴黑一色，会被整片摘掉 —— 白方无处可下、直接虚着，一局棋两手就结束了。
     * 双方都有子之后，中央大空区必然同时贴到两色，不会被误摘。
     */
    fun settledEmptyPoints(board: Board): Set<Square> {
        // 开局护栏：任一方无子时不做任何摘除
        if (stoneCount(board, Side.FIRST) == 0 || stoneCount(board, Side.SECOND) == 0) {
            return emptySet()
        }
        val settled = HashSet<Square>()
        for (region in GoScoring.emptyRegions(board)) {
            if (region.owner != null) {
                settled += region.points
            }
        }
        return settled
    }

    /**
     * 遍历盘上每一块，每块只回调一次。
     *
     * 用 visited 集合避免同一块被它的每颗子各触发一次 ——
     * 一块 20 子的棋若回调 20 次，罚分会被放大 20 倍，估值彻底失真。
     *
     * @param action 参数依次是：块的子数、块的气集合、块的归属方
     */
    private inline fun forEachGroup(
        board: Board,
        action: (stones: Int, liberties: Set<Square>, side: Side) -> Unit,
    ) {
        val visited = HashSet<Int>()
        for (square in board.spec.squares()) {
            val piece = board[square] ?: continue
            if (square.packed in visited) {
                continue
            }
            val group = GoBoardOps.groupAt(board, square)
            group.stones.forEach { visited += it.packed }
            action(group.stones.size, group.liberties, piece.side)
        }
    }
}
