package linmumua.chess.interact

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import linmumua.chess.rule.core.Board
import linmumua.chess.rule.core.Piece
import linmumua.chess.rule.core.PieceType
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.core.Square
import linmumua.chess.rule.xiangqi.MoveRejection
import linmumua.chess.rule.xiangqi.XiangqiRules
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 送将的三种典型局面。
 *
 * 规则层按项目约定把飞将对脸、不应将、走进攻击范围统一归到 SELF_CHECK。
 * 交互层原来会把这三种再细分成三句不同的提示，现在提示全部取消了，
 * 但这三个局面本身仍然是必须守住的规则用例 —— 任何一个判错都会让玩家能走出非法棋。
 *
 * 所以这里保留原有的局面构造，把断言从"映射到哪个语言键"改成"规则层是否否决"。
 */
class SelfCheckKeyTest {

    /** 摆一个空盘，只放需要的子，避免开局局面干扰判定 */
    private fun emptyBoard(sideToMove: Side = Side.FIRST): Board = Board(sideToMove = sideToMove)

    /** 规则层实例，判定走一遍公开 API；用默认规则选项 */
    private val rules = XiangqiRules()

    /**
     * 走开挡在两将之间的子构成飞将，必须被否决。
     *
     * 局面：红帥 (4,9)，黑將 (4,0)，中间只隔一个红兵 (4,4)。
     * 红兵横走离开纵线之后两将之间就空了。这一步在规则上等于把自己的将暴露给对方将，
     * 判不出来的话玩家可以随手走出对脸局面，整局规则就崩了。
     *
     * 兵放在 rank 4 而不是 rank 5：红方过河线是 rank ≤ 4，没过河的兵不能横走，
     * 放 rank 5 会先被 PAWN_SIDEWAYS_BEFORE_RIVER 拦下，根本走不到送将判定。
     */
    @Test
    @DisplayName("走开挡在两将之间的子必须被否决")
    fun `moving the blocker into a face off is rejected`() {
        val board = emptyBoard(sideToMove = Side.FIRST)
        board[Square(4, 9)] = Piece(PieceType.KING, Side.FIRST)
        board[Square(4, 0)] = Piece(PieceType.KING, Side.SECOND)
        // 红兵挡在两将之间且已过河，走开就照面
        board[Square(4, 4)] = Piece(PieceType.PAWN, Side.FIRST)

        // 过河的兵可以横走，横走一格就离开 file 4
        val rejection = rules.rejectionOf(board, Square(4, 4), Square(3, 4))
        assertTrue(
            rejection == MoveRejection.SELF_CHECK,
            "挡子走开会让两将照面，必须按送将否决，实际 $rejection",
        )
    }

    /**
     * 走之前已被将军，随便走一步不解将的棋必须被否决。
     *
     * 局面：红帥 (4,9)，黑車 (4,0) 照着红帥所在纵线且中间无子，红方已被将军。
     * 红方挪一个远处的兵，将军并没有解除。允许这一步等于允许玩家无视将军。
     */
    @Test
    @DisplayName("被将军时走无关的一步必须被否决")
    fun `ignoring an active check is rejected`() {
        val board = emptyBoard(sideToMove = Side.FIRST)
        board[Square(4, 9)] = Piece(PieceType.KING, Side.FIRST)
        board[Square(3, 0)] = Piece(PieceType.KING, Side.SECOND)
        // 黑車照着红帥的纵线，红方已被将军
        board[Square(4, 0)] = Piece(PieceType.CHARIOT, Side.SECOND)
        // 一个跟将军无关的红兵，走它不解将
        board[Square(0, 5)] = Piece(PieceType.PAWN, Side.FIRST)

        val rejection = rules.rejectionOf(board, Square(0, 5), Square(0, 4))
        assertTrue(
            rejection == MoveRejection.SELF_CHECK,
            "走之前已被将军且这一步没解将，必须否决，实际 $rejection",
        )
    }

    /**
     * 主动走进敌子射程必须被否决。
     *
     * 局面：红帥 (4,9)，黑將 (3,0)，黑車 (5,0) 照着 file 5。
     * 红帥现在在 file 4 安全，往右走到 (5,9) 就撞进車的射程。
     */
    @Test
    @DisplayName("主动走进敌子射程必须被否决")
    fun `stepping into an attacked square is rejected`() {
        val board = emptyBoard(sideToMove = Side.FIRST)
        board[Square(4, 9)] = Piece(PieceType.KING, Side.FIRST)
        board[Square(3, 0)] = Piece(PieceType.KING, Side.SECOND)
        // 黑車控制 file 5
        board[Square(5, 0)] = Piece(PieceType.CHARIOT, Side.SECOND)

        val rejection = rules.rejectionOf(board, Square(4, 9), Square(5, 9))
        assertTrue(
            rejection == MoveRejection.SELF_CHECK,
            "走之前不被将军、走完才被攻击，必须按送将否决，实际 $rejection",
        )
    }

    /**
     * 否决判定不能改动原局面。
     *
     * 判定要在快照上试走。如果不小心动了真局面，玩家点一次非法落点就会把棋盘走坏，
     * 这是最危险的一类 bug —— 而且现在没有提示文字了，走坏了玩家更难察觉。
     */
    @Test
    @DisplayName("否决判定不会改动原局面")
    fun `rejection check does not mutate the board`() {
        val board = emptyBoard(sideToMove = Side.FIRST)
        board[Square(4, 9)] = Piece(PieceType.KING, Side.FIRST)
        board[Square(3, 0)] = Piece(PieceType.KING, Side.SECOND)
        board[Square(5, 0)] = Piece(PieceType.CHARIOT, Side.SECOND)
        val before = board.toFen()

        val move = board.moveAt(Square(4, 9), Square(5, 9))
        assertNotNull(move, "这一步应该能构造出 Move 对象")
        rules.rejectionOf(board, Square(4, 9), Square(5, 9))

        assertEquals(before, board.toFen(), "判定否决原因时不允许改动原局面")
    }
}
