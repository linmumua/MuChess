package linmumua.chess.game

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import linmumua.chess.board.BoardFootprint
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 单家具棋盘的放置校验范围测试。
 *
 * 家具不再像旧 block_item 那样预先占住一格，因此没有 ignore 分支；命令和物品
 * 放置都必须检查同一 2x2 范围，避免一条路径能把棋盘叠进墙里。
 */
class BoardPlacementCheckTest {

    private val footprint = listOf(0, 1, 2, 3)

    @Test
    @DisplayName("require-flat-area 打开时四格全部检查")
    fun `flat area on checks all four blocks`() {
        assertEquals(footprint, BoardPlacement.selectChecked(footprint, requireFlatArea = true))
    }

    @Test
    @DisplayName("require-flat-area 关闭时只检查 origin 首格")
    fun `flat area off checks only the origin block`() {
        assertEquals(listOf(0), BoardPlacement.selectChecked(footprint, requireFlatArea = false))
    }

    @Test
    @DisplayName("空占位输入保持为空，不凭空生成检查目标")
    fun `empty footprint stays empty`() {
        assertTrue(BoardPlacement.selectChecked(emptyList<Int>(), requireFlatArea = true).isEmpty())
        assertTrue(BoardPlacement.selectChecked(emptyList<Int>(), requireFlatArea = false).isEmpty())
    }

    /**
     * 草必须被顶掉。
     *
     * 不清草的话棋盘只能贴在草尖上：草是 1 格高的方块，薄板压在它上面会整体抬高，
     * 而棋子按 anchor 的 surfaceY 落点，于是棋盘浮在草上、棋子留在草根，两者错开一截。
     */
    @Test
    @DisplayName("草这类可替换方块要被顶掉")
    fun `grass gets cleared`() {
        assertTrue(BoardPlacement.shouldClear(empty = false, replaceable = true))
    }

    /**
     * 空气不该被"清除"。
     *
     * 空气同样满足 `isReplaceable`，不排掉的话会对整片空气白跑 setType，
     * 并把清除数量虚报成 4，日志和测试都失去判断价值。
     */
    @Test
    @DisplayName("空气不计入清除")
    fun `air is not cleared`() {
        assertFalse(BoardPlacement.shouldClear(empty = true, replaceable = true))
    }

    /** 空气：既空又可替换 */
    private val air = BoardPlacement.GroundProbe(empty = true, replaceable = true)

    /** 草、花、雪层这类植被：不空但可替换 */
    private val grass = BoardPlacement.GroundProbe(empty = false, replaceable = true)

    /** 泥土、石头这类实体方块：不空且不可替换 */
    private val solid = BoardPlacement.GroundProbe(empty = false, replaceable = false)

    /**
     * 裸土上放置高度不变。
     *
     * CE 点裸土顶面给出的落点本来就正确（土上方一格），这条修复不能把它再往下拽。
     */
    @Test
    @DisplayName("裸土上落点高度不变")
    fun `bare ground keeps the original height`() {
        // startY 是土上方那格空气，往下一格就是土
        assertEquals(64, BoardPlacement.selectGroundY(64, listOf(air, solid, solid, solid, solid)))
    }

    /**
     * 单层草必须被穿过。
     *
     * 这是"棋盘浮空"最常见的场景：草占着一格，CE 把落点算在草上方，
     * 棋盘就比在裸土上高一格，而棋子按修正后的 anchor 渲染，两者错开。
     */
    @Test
    @DisplayName("单层草被穿过，棋盘落到土顶上")
    fun `single grass layer is skipped`() {
        // startY=65 是草上方空气，64 是草，63 是土 → 棋盘应落在 64
        assertEquals(64, BoardPlacement.selectGroundY(65, listOf(air, grass, solid, solid, solid)))
    }

    /**
     * 两格高草同样要穿过。
     *
     * 高草是两格的，只跳一格仍会浮着。这条确认扫描是持续向下而不是只看一格。
     */
    @Test
    @DisplayName("两格高草全部穿过")
    fun `two block tall grass is skipped`() {
        // startY=66 空气，65/64 两格草，63 土 → 棋盘落在 64
        assertEquals(64, BoardPlacement.selectGroundY(66, listOf(air, grass, grass, solid, solid)))
    }

    /**
     * 一路都没有实体方块时保持原落点。
     *
     * 玩家可能就是想把棋盘放在空中（脚手架拆掉之类）。这时不该把它拽到世界底，
     * 退回 CE 给的落点是最不意外的行为。
     */
    @Test
    @DisplayName("纯空气退回原落点，不往下拽")
    fun `all air falls back to the start`() {
        assertEquals(70, BoardPlacement.selectGroundY(70, listOf(air, air, air, air, air)))
    }

    /**
     * 超过搜索深度就静默退回原落点。
     *
     * MAX_GROUND_SEARCH 是 4 格。地面比这更深时棋盘不会被拉下来 ——
     * 这是这条修复的边界，不是 bug，但必须是可预期的行为而不是拽到世界底。
     */
    @Test
    @DisplayName("地面超出搜索深度时退回原落点")
    fun `ground deeper than the search limit is ignored`() {
        // 5 格都不是实体方块，第 6 格才是土；扫描只看前 5 格（0..4）
        assertEquals(80, BoardPlacement.selectGroundY(80, listOf(air, air, air, air, air)))
    }

    /**
     * 落点自己就是实体方块时往上放一格。
     *
     * 侧面点方块会出现这种落点。棋盘不能埋进方块里，要坐在它顶上。
     */
    @Test
    @DisplayName("落点本身是实体方块时坐到它顶上")
    fun `solid at the start sits on top of it`() {
        assertEquals(65, BoardPlacement.selectGroundY(64, listOf(solid, solid, solid, solid, solid)))
    }

    /**
     * pivot 与 2x2 最小角必须能双向换算回原值。
     *
     * `anchorForFurniture` 拿到的是家具中心 pivot（CE 的 corner 对齐把它吸到整数），
     * 而占位校验、清草、棋子渲染全按最小角算。这一对换算错开一格，整副棋盘的格点
     * 就跟家具外观错位。负坐标尤其要防：整数除法可能把最小角偏到 pivot 另一侧。
     */
    @Test
    @DisplayName("pivot 与 2x2 最小角双向换算一致")
    fun `pivot and footprint origin round trip`() {
        val pivotX = 100
        val pivotZ = -37
        val pivotY = 64
        // pivot 是 2x2 四格交界处，最小角比它小一格；Y 原样传递，换算不该改高度
        assertEquals(
            Triple(pivotX - 1, pivotY, pivotZ - 1),
            BoardFootprint.originOfPivot(pivotX, pivotY, pivotZ),
        )
        // 负坐标不能因为整数除法把最小角偏到 pivot 另一侧
        val (originX, _, originZ) = BoardFootprint.originOfPivot(pivotX, pivotY, pivotZ)
        assertTrue(originX < pivotX, "2x2 最小角的 X 必须在 pivot 之前")
        assertTrue(originZ < pivotZ, "2x2 最小角的 Z 必须在 pivot 之前")
        // 反向换算必须能回到原 pivot，否则清草范围和棋盘占位会整体错开一格
        assertEquals(
            Triple(pivotX, pivotY, pivotZ),
            BoardFootprint.pivotOf(originX, pivotY, originZ),
        )
    }

    /**
     * 实体方块一律不动。
     *
     * 能走到清除这一步说明 canPlace 已经判过该范围可放。真要在这里顺手删实体方块，
     * 就等于绕开校验偷偷改地形，玩家会发现棋盘吃掉了自己的建筑。
     */
    @Test
    @DisplayName("实体方块不会被删掉")
    fun `solid blocks are never cleared`() {
        assertFalse(BoardPlacement.shouldClear(empty = false, replaceable = false))
    }
}
