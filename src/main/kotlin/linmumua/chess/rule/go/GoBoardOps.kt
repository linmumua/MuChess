package linmumua.chess.rule.go

import linmumua.chess.rule.core.Board
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.core.Square

/**
 * 围棋的块与气运算，纯几何。
 *
 * 围棋所有规则都建立在"气"这一个概念上：提子是气尽、禁自杀是自己气尽、
 * 数子要靠围空归属。所以这里只做两件事 —— 找出一块相连同色棋子，数它有几口气。
 *
 * 不含任何 Bukkit 类型，可脱离服务端跑单测。
 *
 * **相邻只算四邻（上下左右），不含斜向。** 这是围棋与五子棋最根本的差别：
 * 五子棋连五看八方向，围棋的"连接"只认四邻，斜向的两颗子是断开的。
 * 写成八邻会让整块判定失效，提子与数子全错。
 */
object GoBoardOps {

    /**
     * 四邻偏移，顺序无语义。
     *
     * 不用 [linmumua.chess.rule.core.Square.offset] 的多参形式逐个写，
     * 是为了让"只有四个方向"这件事在代码里看得见。
     */
    val NEIGHBORS = listOf(
        1 to 0,
        -1 to 0,
        0 to 1,
        0 to -1,
    )

    /**
     * 取某点在盘内的四邻。
     *
     * 盘外自动剔除，所以角上返回 2 个、边上 3 个、腹地 4 个 ——
     * 这正好是"角上的单子只有两口气"的来源，调用方不必再判边界。
     */
    fun neighborsOf(board: Board, square: Square): List<Square> =
        NEIGHBORS.mapNotNull { (deltaFile, deltaRank) ->
            square.offset(deltaFile, deltaRank)?.takeIf { board.contains(it) }
        }

    /**
     * 从 [square] 出发找出整块相连同色棋子，以及这块的全部气。
     *
     * 用广度优先扩散而不是递归：19×19 最坏情况下一块可达 361 子，
     * 递归深度会顶到栈上限。
     *
     * [square] 是空点时返回空块与空气集 —— 调用方（提子判定）本来就只对有子的点调用，
     * 这里不抛异常是为了让"顺手问一个空点"不必先判一次。
     *
     * @return 这块的全部点位与全部气点位
     */
    fun groupAt(board: Board, square: Square): GoGroup {
        val piece = board[square] ?: return GoGroup(emptySet(), emptySet())
        val side = piece.side

        val stones = LinkedHashSet<Square>()
        val liberties = LinkedHashSet<Square>()
        val queue = ArrayDeque<Square>()
        queue.addLast(square)
        stones += square

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (neighbor in neighborsOf(board, current)) {
                val occupant = board[neighbor]
                when {
                    // 空点就是一口气。用 Set 收，同一口气被多颗子共用时只算一次 ——
                    // 算重了会让气尽的块看起来还有气，整块提不掉。
                    occupant == null -> liberties += neighbor
                    // 同色且没访问过，属于同一块，继续扩散
                    occupant.side == side && stones.add(neighbor) -> queue.addLast(neighbor)
                    // 异色是边界，不属于这块也不是气
                    else -> Unit
                }
            }
        }
        return GoGroup(stones, liberties)
    }

    /**
     * 某点所在的块是否已经气尽。
     *
     * 空点返回 false：没有子就谈不上气尽。
     */
    fun isCaptured(board: Board, square: Square): Boolean {
        val group = groupAt(board, square)
        return group.stones.isNotEmpty() && group.liberties.isEmpty()
    }

    /**
     * 找出落子后应当被提掉的对方棋子。
     *
     * 只检查落点的四邻：围棋规则保证一手棋只可能让**与它相邻**的块气尽，
     * 远处的块气数不会因为这一手改变。全盘扫一遍虽然也对，但 19×19 每手多扫 361 点，
     * 而且会掩盖"提子只与落点相邻"这条规则本身。
     *
     * 同一块可能被落点的两个邻位同时碰到，所以用 Set 去重，
     * 否则一块会被记两遍，撤销时重复恢复。
     *
     * @param placed 刚落下的那颗子的位置
     * @param opponent 被提一方
     * @return 应当被提掉的全部点位；没有则返回空集
     */
    fun capturesAfterPlacing(board: Board, placed: Square, opponent: Side): Set<Square> {
        val captured = LinkedHashSet<Square>()
        for (neighbor in neighborsOf(board, placed)) {
            if (board[neighbor]?.side != opponent) {
                continue
            }
            if (neighbor in captured) {
                // 已经被同一块的另一个邻位收进来了
                continue
            }
            val group = groupAt(board, neighbor)
            if (group.liberties.isEmpty()) {
                captured += group.stones
            }
        }
        return captured
    }
}

/**
 * 一块相连同色棋子及其气。
 *
 * [stones] 与 [liberties] 都是集合而非列表：气会被同一块的多颗子共用，
 * 用列表会把一口气数成多口，气尽判定随之失效。
 */
data class GoGroup(
    /** 这块包含的全部点位 */
    val stones: Set<Square>,
    /** 这块的全部气（相邻空点） */
    val liberties: Set<Square>,
) {
    /** 气数 */
    val libertyCount: Int get() = liberties.size

    /** 这块是否气尽，气尽即应被提掉 */
    val isDead: Boolean get() = stones.isNotEmpty() && liberties.isEmpty()
}
