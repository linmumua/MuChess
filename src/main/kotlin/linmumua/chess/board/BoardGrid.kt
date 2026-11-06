package linmumua.chess.board

/**
 * 批量铺棋盘时的网格排布，纯几何。
 *
 * 只服务 `/chess debug test` 的压测放置：要在玩家附近摆下 N 副互不重叠的棋盘，
 * 得先算好每副的最小角 origin。不依赖 Bukkit，能单测 ——
 * "算出来的坐标真的互不重叠"是这段代码唯一要保证的事，靠肉眼看不出来。
 *
 * 排布规则：正方形优先的行列网格，逐行铺满再换行。
 * 相邻棋盘之间留 [GAP] 格空隙，避免 hitbox 贴边互相干扰。
 */
object BoardGrid {

    /**
     * 相邻棋盘之间的空隙格数。
     *
     * 留 1 格而不是紧贴：棋盘家具的 interaction hitbox 按整格算，紧贴时
     * 两副棋盘的判定框会共享边界，点边缘可能命中隔壁那副。
     */
    const val GAP = 1

    /**
     * 算出 [count] 副棋盘的最小角偏移，相对网格原点。
     *
     * @param count 棋盘数量，非正数返回空列表
     * @param footprintSize 单副棋盘的占位边长（象棋与五子棋是 2，围棋是 3）
     * @return 每副棋盘的 (dx, dz) 偏移，按行优先顺序；保证任意两副的占位不重叠
     */
    fun offsets(count: Int, footprintSize: Int): List<Pair<Int, Int>> {
        if (count <= 0) {
            return emptyList()
        }
        val stride = footprintSize + GAP
        val columns = columnsFor(count)
        return (0 until count).map { index ->
            val row = index / columns
            val column = index % columns
            (column * stride) to (row * stride)
        }
    }

    /**
     * 网格列数，取 count 的平方根向上取整。
     *
     * 铺成接近正方形而不是一长条：一行铺 64 副棋盘会拉出 190 格长的带子，
     * 横跨十几个区块，压测时玩家根本不在同一个视野里，实体加载情况也不一样。
     */
    fun columnsFor(count: Int): Int {
        if (count <= 1) {
            return 1
        }
        var columns = Math.sqrt(count.toDouble()).toInt()
        // 向上取整用乘法回推，避免浮点误差在完全平方数上取小一位
        while (columns * columns < count) {
            columns++
        }
        return columns
    }

    /**
     * 网格整体占用的边长（格数），用于把网格摆在玩家周围时算居中偏移。
     *
     * @return (宽, 深) 格数；count 为 0 时返回 (0, 0)
     */
    fun extent(count: Int, footprintSize: Int): Pair<Int, Int> {
        if (count <= 0) {
            return 0 to 0
        }
        val stride = footprintSize + GAP
        val columns = columnsFor(count)
        val rows = (count + columns - 1) / columns
        // 最后一列/行不需要尾部空隙，所以减掉一个 GAP
        val width = columns * stride - GAP
        val depth = rows * stride - GAP
        return width to depth
    }
}
