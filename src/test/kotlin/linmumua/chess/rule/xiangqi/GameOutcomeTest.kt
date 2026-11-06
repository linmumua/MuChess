package linmumua.chess.rule.xiangqi

import linmumua.chess.rule.core.PieceType
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.core.Square
import linmumua.chess.rule.xiangqi.BoardFixture.black
import linmumua.chess.rule.xiangqi.BoardFixture.board
import linmumua.chess.rule.xiangqi.BoardFixture.has
import linmumua.chess.rule.xiangqi.BoardFixture.red
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 终局判定。
 *
 * 最要紧的一条是**困毙判负**：未被将军但无子可动的一方输棋。
 * 国际象棋的同名局面是和棋，如果实现照搬国际象棋会把胜局判成平局，
 * 所以这里必须有独立用例把"困毙 = 负"钉住。
 */
class GameOutcomeTest {

    private val rules = XiangqiRules()

    @Test
    @DisplayName("开局是进行中，双方各有着法")
    fun `开局进行中`() {
        val board = rules.initialBoard()
        assertEquals(GameOutcome.Ongoing, rules.outcome(board, Side.FIRST))
        assertFalse(rules.isInCheck(board, Side.FIRST), "开局谁也没被将军")
        assertFalse(rules.isInCheck(board, Side.SECOND))
    }

    @Test
    @DisplayName("将死：被将军且无路可走，被将死的一方判负")
    fun `将死判负`() {
        // 黑将困在 (4,0)，红双車一封锁死：一車叫将，一車封住退路
        val board = board(
            sideToMove = Side.SECOND,
            redKing = Square(4, 9),
            blackKing = Square(4, 0),
            pieces = arrayOf(
                // 沿 file 4 叫将
                Square(4, 5) has red(PieceType.CHARIOT),
                // 封住 rank 1，黑将退不下去
                Square(0, 1) has red(PieceType.CHARIOT),
                // 封住 file 3 与 file 5，黑将横不开
                Square(3, 8) has red(PieceType.CHARIOT),
                Square(5, 8) has red(PieceType.CHARIOT),
            ),
        )
        assertTrue(rules.isInCheck(board, Side.SECOND), "黑方被 file 4 上的红車将军")
        assertTrue(rules.legalMoves(board, Side.SECOND).isEmpty(), "黑方无着法可走")

        val outcome = rules.outcome(board, Side.SECOND)
        val checkmate = assertInstanceOf(GameOutcome.Checkmate::class.java, outcome)
        assertEquals(Side.SECOND, checkmate.loser, "被将死的是黑方")
        assertEquals(Side.FIRST, checkmate.winner, "红方胜")
        assertTrue(outcome.isFinished)
    }

    @Test
    @DisplayName("困毙：未被将军但无着法可走，同样判负而不是和棋")
    fun `困毙判负`() {
        // 黑方只剩将在 (4,0)。三枚红車分别控住 rank 1、file 3、file 5，但没有一枚叫将 ——
        // 黑将三个方向全被控，自己却不在任何攻击线上。
        // 红将特意放在 file 3 而不是 file 4：否则两将在 file 4 上对脸，黑方就变成被将军了，
        // 那测的就是将死不是困毙
        val board = board(
            sideToMove = Side.SECOND,
            redKing = Square(3, 9),
            blackKing = Square(4, 0),
            pieces = arrayOf(
                // 控住 rank 1 但不在 file 4 上，所以不叫将
                Square(0, 1) has red(PieceType.CHARIOT),
                // 控住 file 3
                Square(3, 8) has red(PieceType.CHARIOT),
                // 控住 file 5
                Square(5, 8) has red(PieceType.CHARIOT),
            ),
        )
        assertFalse(rules.isInCheck(board, Side.SECOND), "黑将不在任何攻击线上，没被将军")
        assertTrue(rules.legalMoves(board, Side.SECOND).isEmpty(), "三个方向全被控，黑方无子可动")

        val outcome = rules.outcome(board, Side.SECOND)
        val stalemate = assertInstanceOf(GameOutcome.Stalemate::class.java, outcome)
        assertEquals(Side.SECOND, stalemate.loser, "困毙的一方判负，这与国际象棋的和棋处理不同")
        assertEquals(Side.FIRST, stalemate.winner)
    }

    @Test
    @DisplayName("自然限着判和，回合数可配置")
    fun `自然限着判和`() {
        val board = rules.initialBoard()
        // 直接把无吃子计数顶到阈值，省去摆 120 步废着
        board.halfMovesSinceCapture = XiangqiRuleOptions.DEFAULT.moveLimitHalfMoves
        val outcome = rules.outcome(board, Side.FIRST)
        val draw = assertInstanceOf(GameOutcome.DrawByMoveLimit::class.java, outcome)
        assertEquals(60, draw.fullMoves, "默认 60 回合无吃子判和")
    }

    @Test
    @DisplayName("关掉自然限着后不再判和")
    fun `自然限着可关闭`() {
        val relaxed = XiangqiRules(XiangqiRuleOptions(moveLimitDrawEnabled = false))
        val board = relaxed.initialBoard()
        board.halfMovesSinceCapture = 1000
        assertEquals(
            GameOutcome.Ongoing,
            relaxed.outcome(board, Side.FIRST),
            "服主关掉限着后，无吃子多久都继续下",
        )
    }

    @Test
    @DisplayName("已将死的局面不会被限着抢先判成和棋")
    fun `将死优先于限着`() {
        val board = board(
            sideToMove = Side.SECOND,
            redKing = Square(4, 9),
            blackKing = Square(4, 0),
            pieces = arrayOf(
                Square(4, 5) has red(PieceType.CHARIOT),
                Square(0, 1) has red(PieceType.CHARIOT),
                Square(3, 8) has red(PieceType.CHARIOT),
                Square(5, 8) has red(PieceType.CHARIOT),
            ),
        )
        board.halfMovesSinceCapture = 500
        assertInstanceOf(
            GameOutcome.Checkmate::class.java,
            rules.outcome(board, Side.SECOND),
            "无着法要先判将死，不能被限着截胡",
        )
    }

    @Test
    @DisplayName("吃子会把无吃子计数清零，撤销后还原")
    fun `吃子清零计数`() {
        val board = board(
            sideToMove = Side.FIRST,
            redKing = Square(4, 9),
            blackKing = Square(3, 0),
            pieces = arrayOf(
                Square(0, 9) has red(PieceType.CHARIOT),
                Square(0, 4) has black(PieceType.PAWN),
            ),
        )
        board.halfMovesSinceCapture = 30
        val capture = rules.legalMovesFrom(board, Square(0, 9)).single { it.to == Square(0, 4) }
        rules.apply(board, capture)
        assertEquals(0, board.halfMovesSinceCapture, "吃子后计数归零")
        rules.undo(board, capture)
        assertEquals(30, board.halfMovesSinceCapture, "撤销后计数还原")
    }
}
