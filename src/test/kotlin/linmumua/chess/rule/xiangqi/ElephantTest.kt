package linmumua.chess.rule.xiangqi

import linmumua.chess.rule.core.PieceType
import linmumua.chess.rule.core.Square
import linmumua.chess.rule.xiangqi.BoardFixture.black
import linmumua.chess.rule.xiangqi.BoardFixture.board
import linmumua.chess.rule.xiangqi.BoardFixture.destinationsFrom
import linmumua.chess.rule.xiangqi.BoardFixture.has
import linmumua.chess.rule.xiangqi.BoardFixture.red
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 塞象眼与象不过河。
 *
 * 象眼在起终点中点，这与马腿的判定点不同，两者必须分别验证 ——
 * 如果实现里图省事共用一套判定，其中一条一定是错的。
 */
class ElephantTest {

    private val rules = XiangqiRules()

    @Test
    @DisplayName("塞象眼：田字中心有子就走不了")
    fun `塞象眼`() {
        val elephant = Square(2, 9)
        // (3,8) 是 (2,9)->(4,7) 的中点，也就是象眼
        val board = board(
            pieces = arrayOf(
                elephant has red(PieceType.ELEPHANT),
                Square(3, 8) has red(PieceType.PAWN),
            ),
        )
        assertEquals(
            MoveRejection.ELEPHANT_EYE_BLOCKED,
            rules.rejectionOf(board, elephant, Square(4, 7)),
            "象眼被占，飞不过去",
        )
    }

    @Test
    @DisplayName("塞象眼不分敌我")
    fun `敌子也塞象眼`() {
        val elephant = Square(2, 9)
        val board = board(
            pieces = arrayOf(
                elephant has red(PieceType.ELEPHANT),
                Square(1, 8) has black(PieceType.PAWN),
            ),
        )
        assertEquals(
            MoveRejection.ELEPHANT_EYE_BLOCKED,
            rules.rejectionOf(board, elephant, Square(0, 7)),
            "敌子塞在象眼上一样飞不过去",
        )
    }

    @Test
    @DisplayName("象眼空着就能飞，落点上的敌子可以吃")
    fun `象眼空着可以飞并吃子`() {
        val elephant = Square(2, 9)
        val board = board(
            pieces = arrayOf(
                elephant has red(PieceType.ELEPHANT),
                Square(4, 7) has black(PieceType.PAWN),
            ),
        )
        val move = rules.legalMovesFrom(board, elephant).single { it.to == Square(4, 7) }
        assertTrue(move.isCapture, "象飞到有敌子的点上应当算吃子")
    }

    @Test
    @DisplayName("象不可过河")
    fun `象不过河`() {
        // (4,5) 是红方半场靠河的点，往前飞会落到 (2,3) / (6,3)，都在黑方半场
        val elephant = Square(4, 5)
        val board = board(pieces = arrayOf(elephant has red(PieceType.ELEPHANT)))
        val destinations = rules.destinationsFrom(board, elephant)
        assertFalse(Square(2, 3) in destinations, "过河的落点不合法")
        assertFalse(Square(6, 3) in destinations, "过河的落点不合法")
        assertEquals(
            MoveRejection.ELEPHANT_CROSSED_RIVER,
            rules.rejectionOf(board, elephant, Square(2, 3)),
            "象过河要给出专门的否决原因，不能混成走法形状不对",
        )
        assertTrue(Square(2, 7) in destinations, "退回自己半场是合法的")
    }

    @Test
    @DisplayName("象走田字，不走别的形状")
    fun `象只走田字`() {
        val elephant = Square(2, 9)
        val board = board(pieces = arrayOf(elephant has red(PieceType.ELEPHANT)))
        assertEquals(
            MoveRejection.INVALID_PATTERN,
            rules.rejectionOf(board, elephant, Square(3, 8)),
            "斜走一格不是象的走法",
        )
        assertEquals(
            MoveRejection.INVALID_PATTERN,
            rules.rejectionOf(board, elephant, Square(2, 7)),
            "直走两格不是象的走法",
        )
    }
}
