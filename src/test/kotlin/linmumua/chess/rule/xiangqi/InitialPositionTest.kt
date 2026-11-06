package linmumua.chess.rule.xiangqi

import linmumua.chess.rule.core.PieceType
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.core.Square
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 初始局面与局面序列化。
 *
 * 摆子表是渲染层与配置层的共同依据：32 枚子的位置错一枚，
 * 世界里的棋子就会落错交叉点，而且开局着法数也会跟着错。
 */
class InitialPositionTest {

    private val rules = XiangqiRules()

    @Test
    @DisplayName("开局共 32 枚子，每方 16 枚")
    fun `开局棋子数`() {
        val board = rules.initialBoard()
        assertEquals(32, board.pieceCount(), "象棋开局 32 枚子")
        assertEquals(16, board.squaresOf(Side.FIRST).size, "红方 16 枚")
        assertEquals(16, board.squaresOf(Side.SECOND).size, "黑方 16 枚")
        assertEquals(32, rules.initialPlacements.size, "对外暴露的摆子表也应是 32 项")
    }

    @Test
    @DisplayName("每方各兵种数量正确")
    fun `各兵种数量`() {
        val board = rules.initialBoard()
        for (side in Side.entries) {
            val counts = board.squaresOf(side)
                .mapNotNull { board[it] }
                .groupingBy { it.type }
                .eachCount()
            assertEquals(1, counts[PieceType.KING], "$side 一枚将")
            assertEquals(2, counts[PieceType.ADVISOR], "$side 两枚士")
            assertEquals(2, counts[PieceType.ELEPHANT], "$side 两枚象")
            assertEquals(2, counts[PieceType.HORSE], "$side 两枚馬")
            assertEquals(2, counts[PieceType.CHARIOT], "$side 两枚車")
            assertEquals(2, counts[PieceType.CANNON], "$side 两枚炮")
            assertEquals(5, counts[PieceType.PAWN], "$side 五枚兵")
        }
    }

    @Test
    @DisplayName("底线排布是車馬象士將士象馬車，红先")
    fun `底线排布`() {
        val board = rules.initialBoard()
        val expected = listOf(
            PieceType.CHARIOT, PieceType.HORSE, PieceType.ELEPHANT, PieceType.ADVISOR,
            PieceType.KING, PieceType.ADVISOR, PieceType.ELEPHANT, PieceType.HORSE,
            PieceType.CHARIOT,
        )
        for ((file, type) in expected.withIndex()) {
            assertEquals(type, board[Square(file, 9)]?.type, "红方底线 file $file")
            assertEquals(type, board[Square(file, 0)]?.type, "黑方底线 file $file")
        }
        assertEquals(Side.FIRST, board.sideToMove, "红先")
        assertEquals(Square(4, 9), board.kingSquare(Side.FIRST))
        assertEquals(Square(4, 0), board.kingSquare(Side.SECOND))
    }

    @Test
    @DisplayName("开局红方有 44 个合法着法")
    fun `开局着法数`() {
        val board = rules.initialBoard()
        // 車 2×2 + 馬 2×2 + 相 2×2 + 仕 2×1 + 帥 1 + 炮 2×(4+4+4) 之类的具体拆分见下面断言，
        // 这里先钉总数：象棋开局着法数是公认的 44
        assertEquals(44, rules.legalMoves(board, Side.FIRST).size, "象棋开局着法数为 44")
        assertEquals(44, rules.legalMoves(board, Side.SECOND).size, "黑方对称，同样 44")
    }

    @Test
    @DisplayName("开局各子的着法数分布")
    fun `开局各子着法`() {
        val board = rules.initialBoard()
        assertEquals(2, rules.legalMovesFrom(board, Square(0, 9)).size, "角車只能沿本纵线上到 rank 8、rank 7，横向被自己的馬堵住")
        assertEquals(2, rules.legalMovesFrom(board, Square(1, 9)).size, "馬两个落点，另两个被自己的兵挡住马腿或占位")
        assertEquals(2, rules.legalMovesFrom(board, Square(2, 9)).size, "相两个落点")
        assertEquals(1, rules.legalMovesFrom(board, Square(3, 9)).size, "仕只能上九宫中心")
        assertEquals(1, rules.legalMovesFrom(board, Square(4, 9)).size, "帥只能上一步")
        assertEquals(1, rules.legalMovesFrom(board, Square(0, 6)).size, "兵只能直进一步")
    }

    @Test
    @DisplayName("局面串能写出也能读回，内容一致")
    fun `局面串往返一致`() {
        val board = rules.initialBoard()
        val fen = rules.toFen(board)
        val restored = rules.fromFen(fen)
        assertEquals(fen, rules.toFen(restored), "往返后局面串应完全一致")
        assertEquals(32, restored.pieceCount())
        assertEquals(Side.FIRST, restored.sideToMove)
        for (square in board.spec.squares()) {
            assertEquals(board[square], restored[square], "$square 上的子应一致")
        }
    }

    @Test
    @DisplayName("局面串带走棋方与无吃子计数")
    fun `局面串带状态`() {
        val board = rules.initialBoard()
        val move = rules.legalMoves(board, Side.FIRST).first()
        rules.apply(board, move)
        val restored = rules.fromFen(rules.toFen(board))
        assertEquals(Side.SECOND, restored.sideToMove, "走棋方要带上")
        assertEquals(1, restored.halfMovesSinceCapture, "无吃子计数要带上")
    }

    @Test
    @DisplayName("坏掉的局面串必须抛错，不能静默摆出错局面")
    fun `坏局面串抛错`() {
        assertThrows(IllegalArgumentException::class.java) { rules.fromFen("") }
        assertThrows(IllegalArgumentException::class.java) { rules.fromFen("9/9/9 r 0") }
        assertThrows(IllegalArgumentException::class.java) { rules.fromFen("9/9/9/9/9/9/9/9/9/8 r 0") }
        assertThrows(IllegalArgumentException::class.java) {
            rules.fromFen("9/9/9/9/9/9/9/9/9/8z r 0")
        }
    }
}
