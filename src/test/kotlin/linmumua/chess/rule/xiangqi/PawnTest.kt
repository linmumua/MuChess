package linmumua.chess.rule.xiangqi

import linmumua.chess.rule.core.PieceType
import linmumua.chess.rule.core.Side
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
 * 兵/卒。
 *
 * 三条硬规矩：过河前只能直进、过河后才能横走、永不后退、无升变。
 * 后退判定错了会让残局的兵变成万能子，横走判定错了会让开局的兵提前散开。
 */
class PawnTest {

    private val rules = XiangqiRules()

    @Test
    @DisplayName("红兵未过河只能向前一步")
    fun `未过河只能直进`() {
        val pawn = Square(4, 6)
        val board = board(pieces = arrayOf(pawn has red(PieceType.PAWN)))
        assertEquals(
            setOf(Square(4, 5)),
            rules.destinationsFrom(board, pawn),
            "红方前进方向是 rank 递减，未过河时只有这一个落点",
        )
    }

    @Test
    @DisplayName("红兵未过河横走不合法，且原因要能区分出来")
    fun `未过河不能横走`() {
        val pawn = Square(4, 6)
        val board = board(pieces = arrayOf(pawn has red(PieceType.PAWN)))
        assertEquals(
            MoveRejection.PAWN_SIDEWAYS_BEFORE_RIVER,
            rules.rejectionOf(board, pawn, Square(3, 6)),
            "没过河就想横走",
        )
    }

    @Test
    @DisplayName("红兵过河后可前、左、右")
    fun `过河后可横走`() {
        val pawn = Square(4, 4)
        val board = board(pieces = arrayOf(pawn has red(PieceType.PAWN)))
        assertEquals(
            setOf(Square(4, 3), Square(3, 4), Square(5, 4)),
            rules.destinationsFrom(board, pawn),
            "过河的兵三个方向，唯独不能后退",
        )
    }

    @Test
    @DisplayName("兵永不后退，过了河也不行")
    fun `兵永不后退`() {
        val pawn = Square(4, 4)
        val board = board(pieces = arrayOf(pawn has red(PieceType.PAWN)))
        assertFalse(Square(4, 5) in rules.destinationsFrom(board, pawn), "红兵不能往 rank 递增方向走")
        assertEquals(
            MoveRejection.PAWN_BACKWARD,
            rules.rejectionOf(board, pawn, Square(4, 5)),
            "后退要给专门的原因，不能混成走法形状不对",
        )
    }

    @Test
    @DisplayName("黑卒方向相反，规则对称")
    fun `黑卒方向相反`() {
        val pawn = Square(4, 3)
        val board = board(sideToMove = Side.SECOND, pieces = arrayOf(pawn has black(PieceType.PAWN)))
        assertEquals(
            setOf(Square(4, 4)),
            rules.destinationsFrom(board, pawn),
            "黑方前进方向是 rank 递增，未过河只有一个落点",
        )
        assertEquals(
            MoveRejection.PAWN_BACKWARD,
            rules.rejectionOf(board, pawn, Square(4, 2)),
            "黑卒往 rank 递减方向就是后退",
        )
    }

    @Test
    @DisplayName("黑卒过河后同样能横走")
    fun `黑卒过河后可横走`() {
        val pawn = Square(4, 5)
        val board = board(sideToMove = Side.SECOND, pieces = arrayOf(pawn has black(PieceType.PAWN)))
        assertEquals(
            setOf(Square(4, 6), Square(3, 5), Square(5, 5)),
            rules.destinationsFrom(board, pawn),
            "黑卒进入红方半场即算过河",
        )
    }

    @Test
    @DisplayName("兵走到底线不升变，只剩横走")
    fun `底线兵不升变`() {
        val pawn = Square(4, 0)
        // 红兵已到黑方底线，前面出盘，象棋没有升变，只剩左右两个落点
        val board = board(
            redKing = Square(4, 9),
            blackKing = Square(3, 1),
            pieces = arrayOf(pawn has red(PieceType.PAWN)),
        )
        assertEquals(
            setOf(Square(3, 0), Square(5, 0)),
            rules.destinationsFrom(board, pawn),
            "底线兵不会变成別的子，只能左右挪",
        )
    }
}
