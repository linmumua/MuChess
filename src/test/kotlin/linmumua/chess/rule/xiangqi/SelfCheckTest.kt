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
 * 送将。
 *
 * 这一条是整个合法性判定的收口：走完己方将被攻击的着法一律非法。
 * 它同时覆盖三种情形 —— 走进敌子攻击范围、被将军时不应将、以及走成对脸。
 * 少了它，规则引擎会允许"把自己的将送掉"，那局棋根本没法判胜负。
 */
class SelfCheckTest {

    private val rules = XiangqiRules()

    @Test
    @DisplayName("将不能走进敌車的攻击线")
    fun `不能走进攻击范围`() {
        val redKing = Square(4, 9)
        val board = board(
            redKing = redKing,
            blackKing = Square(3, 0),
            // 黑車控住 file 5 整条线
            pieces = arrayOf(Square(5, 0) has black(PieceType.CHARIOT)),
        )
        assertFalse(rules.isInCheck(board, Side.FIRST), "此刻红将并未被将军")
        assertEquals(
            MoveRejection.SELF_CHECK,
            rules.rejectionOf(board, redKing, Square(5, 9)),
            "挪到 file 5 就撞进黑車的攻击线，属于送将",
        )
        assertFalse(
            Square(5, 9) in rules.destinationsFrom(board, redKing),
            "送将的落点不该出现在合法落点里",
        )
    }

    @Test
    @DisplayName("被将军时必须应将，无关的着法一律非法")
    fun `必须应将`() {
        val redKing = Square(4, 9)
        val chariot = Square(0, 8)
        val board = board(
            sideToMove = Side.FIRST,
            redKing = redKing,
            blackKing = Square(3, 0),
            pieces = arrayOf(
                // 黑車在 file 4 上直接叫将
                Square(4, 4) has black(PieceType.CHARIOT),
                // 红車在别处，随手走一步是不解将的
                chariot has red(PieceType.CHARIOT),
            ),
        )
        assertTrue(rules.isInCheck(board, Side.FIRST), "红方正被黑車将军")
        assertEquals(
            MoveRejection.SELF_CHECK,
            rules.rejectionOf(board, chariot, Square(1, 8)),
            "不解将的着法一律否掉",
        )

        // 只有三类应法：挪将躲开、垫子挡线、吃掉将军的子
        val legalMoves = rules.legalMoves(board, Side.FIRST)
        assertTrue(legalMoves.isNotEmpty(), "这个局面是有解的")
        assertTrue(
            legalMoves.all { move ->
                val after = rules.applyToCopy(board, move)
                !rules.isInCheck(after, Side.FIRST)
            },
            "所有合法着法走完后都必须不再被将军",
        )
        assertTrue(
            legalMoves.any { it.from == redKing && it.to.file != 4 },
            "挪将出 file 4 是一种应法",
        )
        assertTrue(
            legalMoves.any { it.from == chariot && it.to == Square(4, 8) },
            "红車垫到 file 4 挡住是另一种应法",
        )
    }

    @Test
    @DisplayName("被牵住的子不能动：挪开它等于送将")
    fun `牵住的子不能动`() {
        val redKing = Square(4, 9)
        val pinned = Square(4, 7)
        val board = board(
            sideToMove = Side.FIRST,
            redKing = redKing,
            blackKing = Square(3, 0),
            pieces = arrayOf(
                // 红馬挡在将与黑車之间，被牵住了
                pinned has red(PieceType.HORSE),
                Square(4, 2) has black(PieceType.CHARIOT),
            ),
        )
        assertFalse(rules.isInCheck(board, Side.FIRST), "有馬挡着，眼下没被将军")
        assertTrue(
            rules.destinationsFrom(board, pinned).isEmpty(),
            "馬的每个落点都会离开 file 4，一走黑車就将军，所以它一步也走不了",
        )
        assertEquals(
            MoveRejection.SELF_CHECK,
            rules.rejectionOf(board, pinned, Square(3, 5)),
            "被牵住的子挪开就是送将",
        )
    }

    @Test
    @DisplayName("吃掉将军的子是合法应法")
    fun `吃掉将军的子`() {
        val redKing = Square(4, 9)
        val chariot = Square(0, 4)
        val board = board(
            sideToMove = Side.FIRST,
            redKing = redKing,
            blackKing = Square(3, 0),
            pieces = arrayOf(
                Square(4, 4) has black(PieceType.CHARIOT),
                chariot has red(PieceType.CHARIOT),
            ),
        )
        assertTrue(rules.isInCheck(board, Side.FIRST), "红方被将军")
        val capture = rules.legalMoves(board, Side.FIRST).single { it.to == Square(4, 4) }
        assertTrue(capture.isCapture, "红車横过去吃掉黑車，将军解除")
        assertFalse(
            rules.isInCheck(rules.applyToCopy(board, capture), Side.FIRST),
            "吃掉将军的子之后不再被将军",
        )
    }

    @Test
    @DisplayName("apply 与 undo 严格配对，试走不留痕迹")
    fun `试走后局面还原`() {
        val board = rules.initialBoard()
        val before = board.toFen()
        // 合法着法生成内部要对每个伪合法着法试走再撤销，跑一遍不该改变局面
        rules.legalMoves(board, Side.FIRST)
        assertEquals(before, board.toFen(), "生成合法着法不能污染原局面")

        val move = rules.legalMoves(board, Side.FIRST).first()
        rules.apply(board, move)
        assertEquals(Side.SECOND, board.sideToMove, "走完一步该换手")
        rules.undo(board, move)
        assertEquals(before, board.toFen(), "撤销后应完全还原，包含走棋方与无吃子计数")
    }
}
