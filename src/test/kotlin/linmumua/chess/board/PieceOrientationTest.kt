package linmumua.chess.board

import kotlin.test.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 棋子双面模型选择测试。
 *
 * 运行期不再输出字面角度；红黑与棋盘方向通过内部 CE 物品 id 选择已固化朝向的 JSON 模型。
 * 测试钉住四向命名与唯一保留的平放俯仰，防止以后又把文字旋转塞回实体变换。
 */
class PieceOrientationTest {

    @Test
    @DisplayName("四种棋盘朝向必须选择四个固定模型 id")
    fun `board facing selects model variant`() {
        val expected = mapOf(
            BoardFacing.NORTH to "red_king_display_north",
            BoardFacing.EAST to "red_king_display_east",
            BoardFacing.SOUTH to "red_king_display_south",
            BoardFacing.WEST to "red_king_display_west",
        )
        for ((facing, itemId) in expected) {
            assertEquals(itemId, PieceOrientation.displayItemKey("red_king", facing))
        }
    }

    @Test
    @DisplayName("红黑使用不同内部模型物品而不是运行时半圈旋转")
    fun `side direction is encoded by item model`() {
        assertEquals("red_king_display_north", PieceOrientation.displayItemKey("red_king", BoardFacing.NORTH))
        assertEquals("black_king_display_north", PieceOrientation.displayItemKey("black_king", BoardFacing.NORTH))
    }

    @Test
    @DisplayName("ItemDisplay 只保留正 90 度平放，不参与文字方向")
    fun `flat pitch only lays model onto board`() {
        assertEquals(90f, PieceOrientation.FLAT_PITCH_DEGREES)
    }
}
