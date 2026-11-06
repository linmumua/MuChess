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
 * 帥/將 与 仕/士 的九宫限制。
 *
 * 这两枚子出了九宫整个局面就没法判将军了，所以限制必须硬。
 *
 * 注意本类的局面都在 file 4 上摆了一枚黑卒当挡子：两将都在中路时中间无子即飞将，
 * 那会让帥的落点被送将规则连带裁掉，测不出九宫限制本身。飞将有专门的用例。
 */
class PalaceTest {

    private val rules = XiangqiRules()

    /** 挡住中路的黑卒，避免两将对脸干扰九宫限制的验证 */
    private val riverBlocker = Square(4, 4) has black(PieceType.PAWN)

    @Test
    @DisplayName("帥在九宫中心只有四个落点，出宫不合法")
    fun `帥不出九宫`() {
        val king = Square(4, 8)
        val board = board(redKing = king, blackKing = Square(4, 0), pieces = arrayOf(riverBlocker))
        assertEquals(
            setOf(Square(3, 8), Square(5, 8), Square(4, 7), Square(4, 9)),
            rules.destinationsFrom(board, king),
            "九宫中心的帥四个方向各一步",
        )
    }

    @Test
    @DisplayName("帥走一步就出九宫时，原因是出宫而不是走法不对")
    fun `出宫的原因要能区分`() {
        // 帥在 (4,7) 已经在九宫最前沿，再往前一步的 (4,6) 就出宫了。
        // 这一步在"走一格直线"这个形状上是成立的，所以否决原因必须是出宫
        val king = Square(4, 7)
        val board = board(redKing = king, blackKing = Square(4, 0), pieces = arrayOf(riverBlocker))
        assertEquals(
            MoveRejection.KING_OUT_OF_PALACE,
            rules.rejectionOf(board, king, Square(4, 6)),
            "形状对但出了九宫，要给出宫这个专门的原因",
        )
        assertEquals(
            MoveRejection.ADVISOR_OUT_OF_PALACE,
            rules.rejectionOf(
                board(
                    redKing = Square(4, 9),
                    blackKing = Square(4, 0),
                    pieces = arrayOf(Square(3, 7) has red(PieceType.ADVISOR), riverBlocker),
                ),
                Square(3, 7),
                Square(2, 6),
            ),
            "仕出宫与帥出宫是两个不同的原因",
        )
    }

    @Test
    @DisplayName("帥走一步，不走斜线也不走两格")
    fun `帥只走一步直线`() {
        val king = Square(4, 8)
        val board = board(redKing = king, blackKing = Square(4, 0), pieces = arrayOf(riverBlocker))
        assertEquals(
            MoveRejection.INVALID_PATTERN,
            rules.rejectionOf(board, king, Square(3, 7)),
            "帥不走斜线",
        )
        assertEquals(
            MoveRejection.INVALID_PATTERN,
            rules.rejectionOf(board, king, Square(2, 8)),
            "帥不走两格",
        )
    }

    @Test
    @DisplayName("仕斜走一步且不出九宫")
    fun `仕不出九宫`() {
        val advisor = Square(4, 8)
        val board = board(
            redKing = Square(4, 9),
            blackKing = Square(4, 0),
            pieces = arrayOf(advisor has red(PieceType.ADVISOR), riverBlocker),
        )
        val destinations = rules.destinationsFrom(board, advisor)
        assertEquals(
            setOf(Square(3, 7), Square(5, 7), Square(3, 9), Square(5, 9)),
            destinations,
            "九宫中心的仕四个斜向各一步",
        )
        assertFalse(Square(4, 7) in destinations, "仕不走直线")
    }

    @Test
    @DisplayName("仕在九宫角上只能回中心")
    fun `仕在角上`() {
        val advisor = Square(3, 7)
        val board = board(
            redKing = Square(4, 9),
            blackKing = Square(4, 0),
            pieces = arrayOf(advisor has red(PieceType.ADVISOR), riverBlocker),
        )
        assertEquals(
            setOf(Square(4, 8)),
            rules.destinationsFrom(board, advisor),
            "九宫角上的仕只有中心一个落点，另外三个斜向都出宫",
        )
        assertEquals(
            MoveRejection.ADVISOR_OUT_OF_PALACE,
            rules.rejectionOf(board, advisor, Square(2, 6)),
            "出宫要给专门的原因",
        )
    }

    @Test
    @DisplayName("黑方九宫在棋盘另一头")
    fun `黑方九宫`() {
        val blackKing = Square(4, 1)
        val board = board(
            sideToMove = Side.SECOND,
            redKing = Square(4, 9),
            blackKing = blackKing,
            pieces = arrayOf(riverBlocker),
        )
        val destinations = rules.destinationsFrom(board, blackKing)
        assertTrue(Square(4, 2) in destinations, "黑将可走到 rank 2")
        assertFalse(Square(4, 3) in destinations, "rank 3 已出黑方九宫")
    }
}
