package linmumua.chess.board

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 单家具棋盘的 2x2 占位与中心锚点测试。
 *
 * 偶数尺寸家具的 pivot 算错半格时，模型旋转、点击坐标和四格占位会一起错位；
 * 这类问题不会抛异常，只能靠 origin/pivot 往返与跨零坐标测试提前钉住。
 */
class BoardFootprintTest {

    @Test
    @DisplayName("四个逻辑占位正好覆盖 2x2，不重不漏")
    fun `offsets cover the two by two area exactly`() {
        assertEquals(4, BoardFootprint.BLOCK_OFFSETS.size)
        assertEquals(
            setOf(0 to 0, 1 to 0, 0 to 1, 1 to 1),
            BoardFootprint.BLOCK_OFFSETS.toSet(),
        )
    }

    @Test
    @DisplayName("origin 到家具中心 pivot 再返回必须完全一致")
    fun `origin and pivot round trip`() {
        val origin = Triple(100, 64, -30)
        val pivot = BoardFootprint.pivotOf(origin.first, origin.second, origin.third)
        assertEquals(Triple(101, 64, -29), pivot, "2x2 中心应在最小角 X/Z 各加 1")
        assertEquals(origin, BoardFootprint.originOfPivot(pivot.first, pivot.second, pivot.third))
    }

    @Test
    @DisplayName("负坐标与跨零边界不能因取整方向偏一格")
    fun `negative coordinates and zero crossing work`() {
        val origin = Triple(-1, 70, -1)
        val pivot = BoardFootprint.pivotOf(origin.first, origin.second, origin.third)
        assertEquals(Triple(0, 70, 0), pivot)
        assertEquals(origin, BoardFootprint.originOfPivot(pivot.first, pivot.second, pivot.third))

        val blocks = BoardFootprint.blocksOf(origin.first, origin.second, origin.third)
        assertTrue(Triple(0, 70, 0) in blocks, "2x2 占位应能跨过坐标 0")
    }

    @Test
    @DisplayName("四格必须共面且全部保持 origin 的 Y")
    fun `all occupied blocks share the same y`() {
        val blocks = BoardFootprint.blocksOf(3, -20, 7)
        assertEquals(4, blocks.size)
        assertEquals(setOf(-20), blocks.map { it.second }.toSet())
    }

    @Test
    @DisplayName("菜单兜底只认实际 2x2 占位，旁边与上下方块都不能误命中")
    fun `contains block only accepts the exact footprint plane`() {
        val occupied = BoardFootprint.blocksOf(-3, 64, 8)
        assertTrue(occupied.all { (x, y, z) -> BoardFootprint.containsBlock(-3, 64, 8, x, y, z) })
        assertFalse(BoardFootprint.containsBlock(-3, 64, 8, -4, 64, 8))
        assertFalse(BoardFootprint.containsBlock(-3, 64, 8, -1, 64, 8))
        assertFalse(BoardFootprint.containsBlock(-3, 64, 8, -3, 65, 8))
        assertFalse(BoardFootprint.containsBlock(-3, 64, 8, -3, 63, 8))
    }

    // ---- overlaps ----

    @Test
    @DisplayName("完全分离的两副棋盘不重叠（X 方向）")
    fun `non overlapping boards do not overlap x`() {
        // A 在 (0,64,0)，B 在 (3,64,0)：A 占 X[0,2)，B 占 X[3,5)，有 1 格间距
        assertFalse(BoardFootprint.overlaps(0, 64, 0, 3, 64, 0))
    }

    @Test
    @DisplayName("完全分离的两副棋盘不重叠（Z 方向）")
    fun `non overlapping boards do not overlap z`() {
        assertFalse(BoardFootprint.overlaps(0, 64, 0, 0, 64, 3))
    }

    @Test
    @DisplayName("origin 完全相同必须判重叠")
    fun `identical origin overlaps`() {
        assertTrue(BoardFootprint.overlaps(5, 64, 5, 5, 64, 5))
    }

    @Test
    @DisplayName("错开 1 格仍共享方块，必须判重叠——修复旧的 origin 精确相等漏洞")
    fun `offset by one still overlaps`() {
        // 旧代码 origin 精确相等才算重叠，这个场景会漏；本用例就是该漏洞的回归测试
        assertTrue(BoardFootprint.overlaps(0, 64, 0, 1, 64, 0), "X 错开 1 格，X[0,2) 与 X[1,3) 相交")
        assertTrue(BoardFootprint.overlaps(0, 64, 0, 0, 64, 1), "Z 错开 1 格，Z[0,2) 与 Z[1,3) 相交")
        assertTrue(BoardFootprint.overlaps(0, 64, 0, 1, 64, 1), "X/Z 各错 1 格，两轴都相交")
    }

    @Test
    @DisplayName("不同 Y 层的棋盘不算重叠（不同楼层各放一副）")
    fun `different y layers do not overlap`() {
        assertFalse(BoardFootprint.overlaps(0, 64, 0, 0, 65, 0))
    }

    @Test
    @DisplayName("恰好相邻（间距为 0）不算重叠")
    fun `exactly adjacent boards do not overlap`() {
        // A 占 X[0,2)，B 从 X=2 开始占 X[2,4)，刚好不交叉
        assertFalse(BoardFootprint.overlaps(0, 64, 0, 2, 64, 0))
        assertFalse(BoardFootprint.overlaps(0, 64, 0, 0, 64, 2))
    }

    // ---- blocksWithSupport ----

    @Test
    @DisplayName("blocksWithSupport 应返回 8 格：占位层 4 格 + 承托层 4 格")
    fun `blocks with support returns eight cells`() {
        val blocks = BoardFootprint.blocksWithSupport(0, 64, 0)
        assertEquals(8, blocks.size, "占位层 4 + 承托层 4")
    }

    @Test
    @DisplayName("blocksWithSupport 的承托层 Y 必须是 originY - 1")
    fun `blocks with support contains support layer`() {
        val originY = 64
        val blocks = BoardFootprint.blocksWithSupport(0, originY, 0)
        val ys = blocks.map { it.second }.toSet()
        assertTrue(originY in ys, "必须包含占位层 Y=$originY")
        assertTrue((originY - 1) in ys, "必须包含承托层 Y=${originY - 1}")
    }

    @Test
    @DisplayName("blocksWithSupport 的 X/Z 覆盖与 blocksOf 一致")
    fun `blocks with support xz matches blocks of`() {
        val xzFromSupport = BoardFootprint.blocksWithSupport(3, 64, -5)
            .map { (x, _, z) -> x to z }.toSet()
        val xzFromBlocks = BoardFootprint.blocksOf(3, 64, -5)
            .map { (x, _, z) -> x to z }.toSet()
        // 两层都是同一个 2x2，X/Z 集合应完全相同
        assertEquals(xzFromBlocks, xzFromSupport)
    }
}
