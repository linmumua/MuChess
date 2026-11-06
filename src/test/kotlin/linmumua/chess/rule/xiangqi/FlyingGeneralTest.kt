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
 * 飞将（白脸将）。
 *
 * 两将同纵线且中间无子的局面本身非法。实现上把将当車看：
 * 己方将若能沿直线直接"吃到"对方将，就算被将军。
 * 这么做的好处是不用单独写一条对脸校验 —— 走成对脸自然被送将过滤挡掉，
 * 而且残局里"用将逼对方将"这种战术判定也一并对了。
 */
class FlyingGeneralTest {

    private val rules = XiangqiRules()

    @Test
    @DisplayName("两将同线中间无子，就算被将军")
    fun `已成对脸即被将军`() {
        // 两将都在 file 4，中间空无一子
        val board = board(redKing = Square(4, 9), blackKing = Square(4, 0))
        assertTrue(rules.isInCheck(board, Side.FIRST), "红方处在对脸局面，视为被将军")
        assertTrue(rules.isInCheck(board, Side.SECOND), "对脸是相互的，黑方同样被将军")
    }

    @Test
    @DisplayName("两将同线但中间有子，不算对脸")
    fun `中间有子不算对脸`() {
        val board = board(
            redKing = Square(4, 9),
            blackKing = Square(4, 0),
            pieces = arrayOf(Square(4, 5) has red(PieceType.PAWN)),
        )
        assertFalse(rules.isInCheck(board, Side.FIRST), "中间垫了一枚子，对脸不成立")
        assertFalse(rules.isInCheck(board, Side.SECOND), "同上")
    }

    @Test
    @DisplayName("走成对脸的着法要被否掉，原因是送将")
    fun `走成对脸不合法`() {
        // 红将在 (3,9)，黑将在 (4,0)，file 4 上除两将外无子
        val redKing = Square(3, 9)
        val board = board(redKing = redKing, blackKing = Square(4, 0))
        assertEquals(
            MoveRejection.SELF_CHECK,
            rules.rejectionOf(board, redKing, Square(4, 9)),
            "挪到 file 4 就与黑将对脸，属于送将",
        )
        assertFalse(
            Square(4, 9) in rules.destinationsFrom(board, redKing),
            "对脸的落点不该出现在合法落点里",
        )
    }

    @Test
    @DisplayName("挪走垫在两将之间的子也算送将")
    fun `挪开垫子也是送将`() {
        // 红兵垫在 file 4 上，两将靠它隔开。这里取 rank 4 是因为兵必须已过河才能横走，
        // 否则横走会先被"未过河不能横走"否掉，测不到送将这一层
        val pawn = Square(4, 4)
        val board = board(
            sideToMove = Side.FIRST,
            redKing = Square(4, 9),
            blackKing = Square(4, 0),
            pieces = arrayOf(pawn has red(PieceType.PAWN)),
        )
        assertEquals(
            MoveRejection.SELF_CHECK,
            rules.rejectionOf(board, pawn, Square(3, 4)),
            "垫子一横走两将对脸，这步不能走",
        )
        assertEquals(
            setOf(Square(4, 3)),
            rules.destinationsFrom(board, pawn),
            "只有沿 file 4 继续向前才不破坏遮挡",
        )
    }

    @Test
    @DisplayName("对脸局面下必须先解除对脸")
    fun `对脸局面要先解除`() {
        val redKing = Square(4, 9)
        val board = board(sideToMove = Side.FIRST, redKing = redKing, blackKing = Square(4, 0))
        val destinations = rules.destinationsFrom(board, redKing)
        assertTrue(destinations.isNotEmpty(), "红将可以横向挪出 file 4")
        assertTrue(
            destinations.all { it.file != 4 },
            "所有合法落点都必须离开 file 4，否则对脸没解除",
        )
    }

    @Test
    @DisplayName("对脸判定只认直线，不受同线以外的子影响")
    fun `不同纵线不构成对脸`() {
        val board = board(redKing = Square(4, 9), blackKing = Square(3, 0))
        assertFalse(rules.isInCheck(board, Side.FIRST), "两将不同纵线，谈不上对脸")
    }

    @Test
    @DisplayName("对脸判定与車的判定共用一条扫描：敌車同样能沿直线将军")
    fun `車将军`() {
        val board = board(
            redKing = Square(4, 9),
            blackKing = Square(3, 0),
            pieces = arrayOf(Square(4, 4) has black(PieceType.CHARIOT)),
        )
        assertTrue(rules.isInCheck(board, Side.FIRST), "黑車在同纵线且中间无子，红方被将军")
    }
}
