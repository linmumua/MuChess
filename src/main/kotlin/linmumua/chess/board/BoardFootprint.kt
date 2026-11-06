package linmumua.chess.board

/**
 * 棋盘占位的纯几何。
 *
 * 不依赖 Bukkit，可单测。世界中的棋盘是**一个** CraftEngine 家具，
 * 但逻辑占位覆盖多个方块；这里负责最小角 origin、占位格坐标与家具中心
 * pivot 之间的换算。
 *
 * 边长不再写死：象棋与五子棋 2x2，围棋 3x3。各方法的 `size` 参数默认 [SIZE]，
 * 只关心 2x2 的调用点不必传；围棋侧显式传 `GameType.footprintSize`。
 *
 * 奇偶边长的旋转中心语义不同，CE 侧靠 `alignment` 区分：
 * - 2x2 偶数边长，旋转中心落在四格交界处，用 `alignment: corner`
 * - 3x3 奇数边长，旋转中心落在正中那一格，用 `alignment: center`
 *
 * **整数** pivot 与 origin 之间的换算式两者一致（见 [pivotOffsetOf]），这部分不分奇偶。
 * 但上游那一步——把家具实体的浮点位置取整成 pivot——必须按奇偶分开：corner 的家具
 * 中心是整数，center 的家具中心是「整数 + 0.5」。这一步见 [pivotFromFurnitureCenter]。
 */
object BoardFootprint {

    /** 棋盘物品与家具 id */
    const val BOARD_ID = "xiangqi_board"

    /**
     * 默认占位边长（象棋与五子棋的 2x2）。
     *
     * 各方法的 `size` 参数都默认取这个值，所以只关心 2x2 的调用点不必改。
     * 围棋是 3x3，由调用方显式传 `GameType.footprintSize`。
     */
    const val SIZE = 2

    /**
     * origin 到中心 pivot 的 X/Z 偏移。
     *
     * 整数除法在两种边长下都正好对：2x2 得 1，pivot 落在四格交界处（CE `alignment: corner`）；
     * 3x3 得 1，pivot 落在正中那一格本身（CE `alignment: center`）。
     * 两者语义不同但换算式一致，所以不需要按奇偶分支。
     */
    const val PIVOT_OFFSET = SIZE / 2

    /** 2x2 四格相对 origin 的偏移，顺序固定但不承载任何 CE 资源含义 */
    val BLOCK_OFFSETS = listOf(
        0 to 0,
        1 to 0,
        0 to 1,
        1 to 1,
    )

    /** 某边长对应的 pivot 偏移，[PIVOT_OFFSET] 是它在默认边长下的取值 */
    fun pivotOffsetOf(size: Int): Int = size / 2

    /**
     * 家具实体中心的世界 X/Z → 中心 pivot 的方块坐标。
     *
     * 取整方式必须按占位边长奇偶分开，这是 CE `alignment` 语义的直接后果：
     * - 偶数边长用 `alignment: corner`，家具中心吸附在四格交界上，世界坐标是**整数**，
     *   四舍五入即可；round 同时兜住浮点尾差，99.999999 不会掉到相邻方块。
     * - 奇数边长用 `alignment: center`，家具中心吸附在正中那一格的**格心**，
     *   世界坐标是「整数 + 0.5」。此时 round 会向上多跳一整格（`round(15.5)` = 16），
     *   必须用 floor 取家具中心所在的方块。Java `Math.round` 对 .5 一律朝 +∞ 取，
     *   负坐标下同样偏 +1，所以两个方向的错法一致，不会互相抵消。
     *
     * 算错不会抛异常，表现是整盘沿 +X/+Z 偏移**一整格**：pivot 是整数，差 1 即差 1 格。
     * 围棋 19 路盘的格间距只有 (3 − 2 × 0.12) / 18 ≈ 0.153 格，一整格约合 6.5 个格间距，
     * 棋子完全压不住贴图上的交叉点，`BoardAnchor.centerLocation` 也随之偏出盘心 1/3 盘宽，
     * 表现是悬浮字与粒子飘到棋盘一角。必须靠 [BoardFootprintTest] 的往返用例兜住。
     *
     * @param size 占位边长，默认 [SIZE]（2x2）；围棋传 3
     */
    fun pivotFromFurnitureCenter(
        centerX: Double,
        centerZ: Double,
        size: Int = SIZE,
    ): Pair<Int, Int> = if (size % 2 == 0) {
        Math.round(centerX).toInt() to Math.round(centerZ).toInt()
    } else {
        Math.floor(centerX).toInt() to Math.floor(centerZ).toInt()
    }

    /**
     * 按 origin 算出占位范围内全部方块坐标。
     *
     * @param size 占位边长，默认 [SIZE]（2x2）；围棋传 3
     */
    fun blocksOf(
        originX: Int,
        originY: Int,
        originZ: Int,
        size: Int = SIZE,
    ): List<Triple<Int, Int, Int>> {
        val blocks = ArrayList<Triple<Int, Int, Int>>(size * size)
        for (offsetZ in 0 until size) {
            for (offsetX in 0 until size) {
                blocks += Triple(originX + offsetX, originY, originZ + offsetZ)
            }
        }
        return blocks
    }

    /**
     * 某个方块坐标是否落在 origin 对应的棋盘占位内。
     *
     * @param size 占位边长，默认 [SIZE]（2x2）；围棋传 3
     */
    fun containsBlock(
        originX: Int,
        originY: Int,
        originZ: Int,
        blockX: Int,
        blockY: Int,
        blockZ: Int,
        size: Int = SIZE,
    ): Boolean =
        blockY == originY &&
            blockX in originX until originX + size &&
            blockZ in originZ until originZ + size

    /**
     * 某个方块是否落在"棋盘平面"上，也就是原版兜底命中该认的那两层。
     *
     * 与 [containsBlock] 的区别是多认一层承托方块：CE 缺失时棋盘那一格是空气
     * （棋盘本体是家具实体），玩家能点到的是它下面那块，顶面正好是棋盘平面。
     *
     * 这道限制是必需的：命中解析用的 `squareAt` 只比对 x/z、不看 Y，没有它的话
     * 棋盘 2x2 立柱上下**任意高度**的右键都会被当成落子并取消事件，
     * 表现就是棋盘下方放不了方块。
     *
     * @param blockY 被点方块的 Y
     * @param size 占位边长，默认 [SIZE]（2x2）；围棋传 3
     * @return 在棋盘层或其正下方的承托层内返回 true，其余高度返回 false
     */
    fun containsBoardPlane(
        originX: Int,
        originY: Int,
        originZ: Int,
        blockX: Int,
        blockY: Int,
        blockZ: Int,
        size: Int = SIZE,
    ): Boolean =
        (blockY == originY || blockY == originY - 1) &&
            blockX in originX until originX + size &&
            blockZ in originZ until originZ + size

    /** 占位最小角 → 家具中心 pivot */
    fun pivotOf(
        originX: Int,
        originY: Int,
        originZ: Int,
        size: Int = SIZE,
    ): Triple<Int, Int, Int> {
        val offset = pivotOffsetOf(size)
        return Triple(originX + offset, originY, originZ + offset)
    }

    /** 家具中心 pivot → 占位最小角 */
    fun originOfPivot(
        pivotX: Int,
        pivotY: Int,
        pivotZ: Int,
        size: Int = SIZE,
    ): Triple<Int, Int, Int> {
        val offset = pivotOffsetOf(size)
        return Triple(pivotX - offset, pivotY, pivotZ - offset)
    }

    /**
     * 两副棋盘的占位是否重叠。
     *
     * 只要同 Y 层且 X/Z 区间有交集就算重叠，不要求 origin 完全相同。
     * 错开 1 格的两副 2x2 棋盘（如 origin A=(0,64,0)、B=(1,64,0)）仍然各占两格，
     * X 区间 [0,2) 与 [1,3) 有交集，判定重叠。
     *
     * 两个尺寸分开传是必需的：一副 3x3 围棋盘旁边可以是 2x2 象棋盘，
     * 用同一个尺寸算会在"大盘压小盘"这个方向漏判 —— 漏判的表现是两副棋盘
     * 的家具 hitbox 互相串道，点一副的边缘会走到另一副的棋局里。
     *
     * 纯逻辑，不依赖 Bukkit，可单测。
     *
     * @param sizeA 第一副的占位边长，默认 [SIZE]
     * @param sizeB 第二副的占位边长，默认 [SIZE]
     */
    fun overlaps(
        originAX: Int,
        originAY: Int,
        originAZ: Int,
        originBX: Int,
        originBY: Int,
        originBZ: Int,
        sizeA: Int = SIZE,
        sizeB: Int = SIZE,
    ): Boolean =
        originAY == originBY &&
            originAX < originBX + sizeB && originBX < originAX + sizeA &&
            originAZ < originBZ + sizeB && originBZ < originAZ + sizeA

    /**
     * 2x2 占位层 + 正下方承托层共 8 格坐标。
     *
     * 用于扫描 CE 家具时的检查范围：
     * - 占位层：家具与棋盘同 Y 则会发生 hitbox 重叠，交互命中会串道。
     * - 承托层（Y-1）：CE 家具不是实体方块，groundLevel 的向下扫描会直接穿过它
     *   继续找地面，导致棋盘沉进家具里或错层。按"一律禁止"决策，承托层有 CE
     *   家具时同样拒绝放置。
     */
    fun blocksWithSupport(
        originX: Int,
        originY: Int,
        originZ: Int,
        size: Int = SIZE,
    ): List<Triple<Int, Int, Int>> =
        blocksOf(originX, originY, originZ, size) +
            blocksOf(originX, originY - 1, originZ, size)
}
