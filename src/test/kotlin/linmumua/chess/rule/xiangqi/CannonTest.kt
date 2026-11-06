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
 * 炮与車。
 *
 * 炮的核心是"吃子必须跨过**恰好一枚**炮架"，所以 0 枚、1 枚、2 枚三种情形必须分别验证。
 * 少验一种就可能放过"隔山打牛"或"贴身炮能吃"这类错误。
 */
class CannonTest {

    private val rules = XiangqiRules()

    @Test
    @DisplayName("炮跨过恰好一枚炮架时可以吃子")
    fun `一枚炮架可以吃`() {
        val cannon = Square(4, 9)
        val board = board(
            redKing = Square(3, 9),
            blackKing = Square(5, 0),
            pieces = arrayOf(
                cannon has red(PieceType.CANNON),
                Square(4, 6) has red(PieceType.PAWN),
                Square(4, 3) has black(PieceType.PAWN),
            ),
        )
        val move = rules.legalMovesFrom(board, cannon).single { it.to == Square(4, 3) }
        assertTrue(move.isCapture, "跨过一枚炮架吃到炮架后的第一枚敌子")
    }

    @Test
    @DisplayName("炮不跨炮架时吃不了子")
    fun `零枚炮架不能吃`() {
        val cannon = Square(4, 9)
        val board = board(
            redKing = Square(3, 9),
            blackKing = Square(5, 0),
            pieces = arrayOf(
                cannon has red(PieceType.CANNON),
                Square(4, 3) has black(PieceType.PAWN),
            ),
        )
        assertEquals(
            MoveRejection.CANNON_NO_SCREEN,
            rules.rejectionOf(board, cannon, Square(4, 3)),
            "中间一枚子都没有，炮不能吃",
        )
        assertFalse(
            Square(4, 3) in rules.destinationsFrom(board, cannon),
            "无炮架时那枚敌子不该出现在落点里",
        )
    }

    @Test
    @DisplayName("炮跨两枚子吃不了：隔山打牛不成立")
    fun `两枚炮架不能吃`() {
        val cannon = Square(4, 9)
        val board = board(
            redKing = Square(3, 9),
            blackKing = Square(5, 0),
            pieces = arrayOf(
                cannon has red(PieceType.CANNON),
                Square(4, 6) has red(PieceType.PAWN),
                Square(4, 5) has red(PieceType.PAWN),
                Square(4, 3) has black(PieceType.PAWN),
            ),
        )
        assertEquals(
            MoveRejection.CANNON_TOO_MANY_SCREENS,
            rules.rejectionOf(board, cannon, Square(4, 3)),
            "跨两枚子不合法，且原因要与无炮架区分开",
        )
    }

    @Test
    @DisplayName("炮架不分敌我")
    fun `炮架不分敌我`() {
        val cannon = Square(4, 9)
        val board = board(
            redKing = Square(3, 9),
            blackKing = Square(5, 0),
            pieces = arrayOf(
                cannon has red(PieceType.CANNON),
                // 拿敌方的子当炮架
                Square(4, 6) has black(PieceType.PAWN),
                Square(4, 3) has black(PieceType.PAWN),
            ),
        )
        assertTrue(
            Square(4, 3) in rules.destinationsFrom(board, cannon),
            "敌方的子照样能当炮架",
        )
        assertFalse(
            Square(4, 6) in rules.destinationsFrom(board, cannon),
            "炮架本身不能被这门炮直接吃",
        )
    }

    @Test
    @DisplayName("炮不吃子时走法同車，路上不能有子")
    fun `炮不吃子时同車`() {
        val cannon = Square(4, 9)
        val board = board(
            redKing = Square(3, 9),
            blackKing = Square(5, 0),
            pieces = arrayOf(
                cannon has red(PieceType.CANNON),
                Square(4, 6) has red(PieceType.PAWN),
            ),
        )
        val destinations = rules.destinationsFrom(board, cannon)
        assertTrue(Square(4, 7) in destinations, "炮架前的空点可以走")
        assertEquals(
            MoveRejection.PATH_BLOCKED,
            rules.rejectionOf(board, cannon, Square(4, 5)),
            "越过子落到空点不合法，这条要与炮架数量的原因区分开",
        )
    }

    @Test
    @DisplayName("車直横走任意距离但不能越子")
    fun `車不越子`() {
        val chariot = Square(0, 9)
        val board = board(
            redKing = Square(4, 9),
            blackKing = Square(3, 0),
            pieces = arrayOf(
                chariot has red(PieceType.CHARIOT),
                Square(0, 5) has black(PieceType.PAWN),
            ),
        )
        val destinations = rules.destinationsFrom(board, chariot)
        assertTrue(Square(0, 5) in destinations, "可以吃掉路上第一枚敌子")
        assertFalse(Square(0, 4) in destinations, "不能越过它继续走")
        assertEquals(
            MoveRejection.PATH_BLOCKED,
            rules.rejectionOf(board, chariot, Square(0, 3)),
            "越子要给出路被挡住的原因",
        )
    }
}
