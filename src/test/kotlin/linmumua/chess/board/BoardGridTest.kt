package linmumua.chess.board

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 批量铺棋盘的网格排布测试。
 *
 * 这段几何唯一要保证的事是"算出来的 N 个位置互不重叠"。算错的后果不是抛异常，
 * 而是 `/chess debug test` 铺到一半开始报 NoSpace，压测数据直接失去意义 ——
 * 而且看日志会以为是放置校验有问题，排查方向全错。所以重叠必须由测试钉死。
 */
class BoardGridTest {

    @Test
    @DisplayName("数量为 0 或负数时不产出任何位置")
    fun `non positive count yields nothing`() {
        assertTrue(BoardGrid.offsets(0, 2).isEmpty())
        assertTrue(BoardGrid.offsets(-1, 2).isEmpty())
        assertEquals(0 to 0, BoardGrid.extent(0, 2))
    }

    @Test
    @DisplayName("产出的位置数与请求数一致")
    fun `offset count matches request`() {
        listOf(1, 2, 7, 16, 64, 100).forEach { count ->
            assertEquals(count, BoardGrid.offsets(count, 2).size, "请求 $count 副就该给 $count 个位置")
        }
    }

    @Test
    @DisplayName("2x2 棋盘：任意两副的占位都不重叠")
    fun `two by two boards never overlap`() {
        assertNoOverlap(count = 64, footprintSize = 2)
    }

    @Test
    @DisplayName("3x3 棋盘（围棋）：任意两副的占位都不重叠")
    fun `three by three boards never overlap`() {
        // 围棋是 3x3 占位，stride 必须跟着变大，否则会互相压边
        assertNoOverlap(count = 32, footprintSize = 3)
    }

    @Test
    @DisplayName("相邻棋盘之间确实留出了空隙，不是紧贴")
    fun `adjacent boards keep a gap`() {
        val offsets = BoardGrid.offsets(4, 2)
        // 4 副铺成 2x2 网格，第 0 与第 1 副同行相邻
        val (x0, _) = offsets[0]
        val (x1, _) = offsets[1]
        // 占位 2 格 + 空隙 1 格 = 步距 3
        assertEquals(3, x1 - x0, "步距应是占位边长加 GAP，紧贴会让 hitbox 抢点击")
        assertEquals(BoardGrid.GAP, x1 - x0 - 2)
    }

    @Test
    @DisplayName("铺成接近正方形，不是拉成一长条")
    fun `grid stays roughly square`() {
        // 64 副铺 8 列，而不是 1 行 64 副（那会拉出 190 格长的带子）
        assertEquals(8, BoardGrid.columnsFor(64))
        assertEquals(1, BoardGrid.columnsFor(1))
        assertEquals(2, BoardGrid.columnsFor(2))
        assertEquals(3, BoardGrid.columnsFor(7), "7 的平方根向上取整是 3")
        assertEquals(4, BoardGrid.columnsFor(16))
        assertEquals(5, BoardGrid.columnsFor(17), "17 要 5 列，不能因浮点误差给 4")
    }

    @Test
    @DisplayName("完全平方数的列数不能因浮点误差少一列")
    fun `perfect squares do not lose a column`() {
        // sqrt 在某些数上会给出 x.9999999，直接 toInt 会少一列导致最后一行溢出
        listOf(4, 9, 16, 25, 36, 49, 64, 81, 100, 121, 144, 169, 196, 225, 256).forEach { n ->
            val columns = BoardGrid.columnsFor(n)
            assertEquals(
                Math.sqrt(n.toDouble()).toInt(),
                columns,
                "$n 是完全平方数，列数应正好是它的平方根",
            )
            assertTrue(columns * columns >= n, "$n 列数不足会让部分棋盘挤到网格外")
        }
    }

    @Test
    @DisplayName("extent 覆盖全部位置，且不含尾部空隙")
    fun `extent covers every offset without trailing gap`() {
        val count = 10
        val size = 2
        val offsets = BoardGrid.offsets(count, size)
        val (width, depth) = BoardGrid.extent(count, size)

        val maxX = offsets.maxOf { it.first + size }
        val maxZ = offsets.maxOf { it.second + size }
        assertEquals(maxZ, depth, "深度应正好到最后一行的边，不多留空隙")
        assertTrue(width >= maxX, "宽度必须覆盖最靠右那副棋盘")
        // 10 副铺 4 列，最后一行只有 2 副，宽度按满行算所以可能比 maxX 大
        assertEquals(4 * (size + BoardGrid.GAP) - BoardGrid.GAP, width)
    }

    /**
     * 断言 [count] 副 [footprintSize] 占位的棋盘两两不重叠。
     *
     * 直接把每副棋盘占的格子摊平进一个集合，重复即重叠 ——
     * 比逐对比较区间更难写错，也顺带验证了总格子数。
     */
    private fun assertNoOverlap(count: Int, footprintSize: Int) {
        val used = HashSet<Pair<Int, Int>>()
        BoardGrid.offsets(count, footprintSize).forEachIndexed { index, (dx, dz) ->
            for (x in dx until dx + footprintSize) {
                for (z in dz until dz + footprintSize) {
                    assertTrue(
                        used.add(x to z),
                        "第 $index 副棋盘在 ($x, $z) 与前面某副重叠",
                    )
                }
            }
        }
        assertEquals(
            count * footprintSize * footprintSize,
            used.size,
            "占用格子总数应等于 副数 × 单副格数",
        )
    }
}
