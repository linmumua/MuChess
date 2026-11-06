package linmumua.chess.board

import linmumua.chess.rule.core.BoardSpec
import linmumua.chess.rule.core.Square
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 棋盘几何换算测试。
 *
 * 坐标算错不会抛异常，只会让棋子悄悄错位、右键点不中，
 * 所以这里必须靠往返一致性把问题挡在编译期之后、上线之前。
 *
 * 三个棋种的网格与占位都不同（象棋 9×10 铺 2x2、五子棋 15×15 铺 2x2、
 * 围棋 19×19 铺 3x3），所以往返与不重合这两条要对每个棋种各跑一遍 ——
 * 只测象棋的话，新棋种的格点算错了测试照样全绿。
 */
class BoardGeometryTest {

    /** 三个棋种的 (名称, 网格, 占位边长) */
    private val layouts = listOf(
        Triple("象棋", BoardSpec.XIANGQI, 2.0),
        Triple("五子棋", BoardSpec.GOMOKU, 2.0),
        Triple("围棋", BoardSpec.GO, 3.0),
    )

    @Test
    @DisplayName("三个棋种在四种朝向下，格点转世界坐标再转回来必须是同一个格点")
    fun `round trip is stable for every facing`() {
        for ((name, spec, span) in layouts) {
            for (facing in BoardFacing.entries) {
                val geometry = BoardGeometry(facing, spec, span)
                for (square in spec.squares()) {
                    val (x, z) = geometry.localOffsetOf(square)
                    val resolved = geometry.squareAtLocal(x, z)
                    assertEquals(square, resolved, "$name 朝向 $facing 的格点 $square 往返后不一致")
                }
            }
        }
    }

    @Test
    @DisplayName("三个棋种的格点都必须落在各自占位范围内，不能溢出棋盘")
    fun `all squares stay inside the footprint`() {
        for ((name, spec, span) in layouts) {
            for (facing in BoardFacing.entries) {
                val geometry = BoardGeometry(facing, spec, span)
                for (square in spec.squares()) {
                    val (x, z) = geometry.localOffsetOf(square)
                    assertTrue(x in 0.0..span, "$name 朝向 $facing 的 $square 的 x=$x 越出棋盘")
                    assertTrue(z in 0.0..span, "$name 朝向 $facing 的 $square 的 z=$z 越出棋盘")
                }
            }
        }
    }

    @Test
    @DisplayName("三个棋种的格点各自互不重合，否则两枚棋子会叠在一起")
    fun `squares do not collide`() {
        for ((name, spec, span) in layouts) {
            val geometry = BoardGeometry(BoardFacing.NORTH, spec, span)
            val seen = mutableSetOf<Pair<Long, Long>>()
            for (square in spec.squares()) {
                val (x, z) = geometry.localOffsetOf(square)
                // 量化到 1e-4 再比，避免浮点噪声误判
                val key = Math.round(x * 10000) to Math.round(z * 10000)
                assertTrue(seen.add(key), "$name 的 $square 与已有格点重合")
            }
            assertEquals(spec.squareCount, seen.size, "$name 的格点数不对")
        }
    }

    @Test
    @DisplayName("棋子直径不得超过格点间距，否则相邻棋子会互相压边")
    fun `piece scale fits the grid spacing`() {
        // 圆盘占贴图宽度的比例：半径 7.36 像素 / 16 像素贴图 × 2
        val discRatio = 7.36 / 16.0 * 2.0
        // 各棋种的 base-scale，与 ChessSettings 里的默认值一致
        val scales = mapOf("象棋" to 0.17, "五子棋" to 0.116, "围棋" to 0.142)

        for ((name, spec, span) in layouts) {
            val geometry = BoardGeometry(BoardFacing.NORTH, spec, span)
            val spacing = minOf(geometry.fileSpacing, geometry.rankSpacing)
            val diameter = scales.getValue(name) * discRatio
            assertTrue(
                diameter <= spacing,
                "$name 棋子直径 $diameter 超过格点间距 $spacing，相邻棋子会压边",
            )
        }
    }

    @Test
    @DisplayName("点在格点附近要能吸附，点在两格中间的空档要判定为点空")
    fun `snapping respects tolerance`() {
        val geometry = BoardGeometry(BoardFacing.NORTH)
        val square = Square(4, 5)
        val (x, z) = geometry.localOffsetOf(square)

        // 偏移一点仍应命中，玩家不可能像素级精准
        assertEquals(square, geometry.squareAtLocal(x + geometry.fileSpacing * 0.2, z))

        // 落在棋盘外侧远处应判空，不能被硬吸到最外圈
        assertNull(geometry.squareAtLocal(-1.0, -1.0))
    }

    @Test
    @DisplayName("河界两侧的格点间距一致，棋盘不应被河界拉伸")
    fun `river does not distort spacing`() {
        val geometry = BoardGeometry(BoardFacing.NORTH)
        val below = geometry.localOffsetOf(Square(4, 4))
        val above = geometry.localOffsetOf(Square(4, 5))
        val gap = Math.abs(above.second - below.second)
        assertEquals(geometry.rankSpacing, gap, 1e-9, "河界处的间距与其它横线不一致")
    }

    @Test
    @DisplayName("朝向旋转 90 度后，红方底线应换到另一条世界轴上")
    fun `facing actually rotates the board`() {
        val north = BoardGeometry(BoardFacing.NORTH)
        val east = BoardGeometry(BoardFacing.EAST)
        val redBackLine = Square(4, 9)

        val northOffset = north.localOffsetOf(redBackLine)
        val eastOffset = east.localOffsetOf(redBackLine)
        assertNotNull(northOffset)
        // NORTH 下红方底线体现在 z 上，EAST 下应该体现在 x 上
        assertTrue(
            Math.abs(northOffset.second - eastOffset.first) < 1e-9 ||
                Math.abs(northOffset.second - (BoardAnchor.BOARD_SPAN - eastOffset.first)) < 1e-9,
            "EAST 朝向没有把红方底线转到 x 轴上",
        )
    }

    @Test
    @DisplayName("CE 家具 base yaw 多半圈，往返后仍必须得到同一棋盘朝向")
    fun `furniture yaw maps back to the visual facing`() {
        for (facing in BoardFacing.entries) {
            assertEquals(
                facing,
                BoardFacing.fromFurnitureYaw(facing.furnitureYaw),
                "$facing 的家具 yaw 往返后换了方向",
            )
            assertEquals(
                BoardFacing.normalizeYaw(facing.yaw + 180f),
                facing.furnitureYaw,
                "$facing 没给 CE base entity 加半圈偏移",
            )
        }
    }
}
