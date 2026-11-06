package linmumua.chess.rule.xiangqi

import linmumua.chess.rule.core.PieceType
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.core.Square
import linmumua.chess.rule.xiangqi.BoardFixture.black
import linmumua.chess.rule.xiangqi.BoardFixture.board
import linmumua.chess.rule.xiangqi.BoardFixture.has
import linmumua.chess.rule.xiangqi.BoardFixture.red
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 反向探测式将军检测。
 *
 * 从己方将出发反查各兵种，而不是生成对方全部着法。
 * 反查马是最容易写错的一处：马腿要按**马自己那一侧**算，
 * 把马的正向偏移取个反照抄会把马腿算到将这一侧，判定就全错了。
 */
class CheckDetectorTest {

    private val rules = XiangqiRules()

    @Test
    @DisplayName("敌馬能将军，马腿被别住就不算将军")
    fun `馬将军与蹩腿`() {
        val redKing = Square(4, 9)
        // 黑馬在 (3,7)，走 (+1,+2) 正好踩到 (4,9)，它的马腿在 (3,8)
        val checking = board(
            redKing = redKing,
            blackKing = Square(3, 0),
            pieces = arrayOf(Square(3, 7) has black(PieceType.HORSE)),
        )
        assertTrue(rules.isInCheck(checking, Side.FIRST), "黑馬能跳到将位上，算将军")

        val blocked = board(
            redKing = redKing,
            blackKing = Square(3, 0),
            pieces = arrayOf(
                Square(3, 7) has black(PieceType.HORSE),
                // 马腿在马自己那一侧的 (3,8)，堵住它将军就不成立
                Square(3, 8) has red(PieceType.ADVISOR),
            ),
        )
        assertFalse(
            rules.isInCheck(blocked, Side.FIRST),
            "马腿在 (3,8) 被堵，黑馬跳不过来，不算将军",
        )
    }

    @Test
    @DisplayName("反查马腿不能照抄马的正向偏移：将这一侧有子不解将")
    fun `将这侧有子不解将`() {
        val redKing = Square(4, 9)
        // 黑馬在 (3,7) 走 (+1,+2)，马腿在 (3,8)。(4,8) 是"将这一侧"的点，
        // 若实现把马腿误算成这里，摆上一枚子就会错判为不将军
        val board = board(
            redKing = redKing,
            blackKing = Square(3, 0),
            pieces = arrayOf(
                Square(3, 7) has black(PieceType.HORSE),
                Square(4, 8) has red(PieceType.ADVISOR),
            ),
        )
        assertTrue(
            rules.isInCheck(board, Side.FIRST),
            "(4,8) 不是马腿，垫在这里挡不住馬，仍然被将军",
        )
    }

    @Test
    @DisplayName("敌炮隔一枚子将军，无炮架或两枚炮架都不算")
    fun `炮将军`() {
        val redKing = Square(4, 9)
        val screen = Square(4, 5) has red(PieceType.PAWN)
        val cannon = Square(4, 2) has black(PieceType.CANNON)

        val checking = board(redKing = redKing, blackKing = Square(3, 0), pieces = arrayOf(screen, cannon))
        assertTrue(rules.isInCheck(checking, Side.FIRST), "隔一枚炮架，黑炮将军")

        val noScreen = board(redKing = redKing, blackKing = Square(3, 0), pieces = arrayOf(cannon))
        assertFalse(rules.isInCheck(noScreen, Side.FIRST), "没有炮架，黑炮打不到")

        val twoScreens = board(
            redKing = redKing,
            blackKing = Square(3, 0),
            pieces = arrayOf(screen, cannon, Square(4, 4) has red(PieceType.PAWN)),
        )
        assertFalse(rules.isInCheck(twoScreens, Side.FIRST), "隔两枚子，黑炮同样打不到")
    }

    @Test
    @DisplayName("过河的敌卒能将军，未过河的不能")
    fun `卒将军`() {
        val redKing = Square(4, 9)
        // 黑卒在 (4,8)，往前一步（rank 递增）正好踩到 (4,9)
        val front = board(
            redKing = redKing,
            blackKing = Square(3, 0),
            pieces = arrayOf(Square(4, 8) has black(PieceType.PAWN)),
        )
        assertTrue(rules.isInCheck(front, Side.FIRST), "黑卒正对将，能吃到")

        // 黑卒在 (3,9)，横吃一步到 (4,9)。它已过河，横走合法
        val side = board(
            redKing = redKing,
            blackKing = Square(3, 0),
            pieces = arrayOf(Square(3, 9) has black(PieceType.PAWN)),
        )
        assertTrue(rules.isInCheck(side, Side.FIRST), "过河的黑卒能横吃，算将军")

        // 己方的兵摆在同样位置不构成将军
        val friendly = board(
            redKing = redKing,
            blackKing = Square(3, 0),
            pieces = arrayOf(Square(4, 8) has red(PieceType.PAWN)),
        )
        assertFalse(rules.isInCheck(friendly, Side.FIRST), "自己的兵不会将自己")
    }

    @Test
    @DisplayName("黑卒在将的前方不算将军：卒不后退")
    fun `卒不后退所以不将军`() {
        val redKing = Square(4, 7)
        // 黑卒在 (4,6)，黑方前进方向是 rank 递增，它要吃 (4,7) 得往前走 —— 这是合法的
        val behind = board(
            redKing = redKing,
            blackKing = Square(3, 0),
            pieces = arrayOf(Square(4, 6) has black(PieceType.PAWN)),
        )
        assertTrue(rules.isInCheck(behind, Side.FIRST), "黑卒往 rank 递增方向走，能吃到 (4,7)")

        // 黑卒在 (4,8)，要吃 (4,7) 得往 rank 递减方向走，那是后退，不允许
        val ahead = board(
            redKing = redKing,
            blackKing = Square(3, 0),
            pieces = arrayOf(Square(4, 8) has black(PieceType.PAWN)),
        )
        assertFalse(ahead.let { rules.isInCheck(it, Side.FIRST) }, "黑卒不能后退吃将")
    }

    @Test
    @DisplayName("象与士永远不能将军")
    fun `象士不将军`() {
        val redKing = Square(4, 9)
        val elephant = board(
            redKing = redKing,
            blackKing = Square(3, 0),
            // 黑象根本过不了河，摆在这里只是为了证明探测不会误判
            pieces = arrayOf(Square(2, 7) has black(PieceType.ELEPHANT)),
        )
        assertFalse(rules.isInCheck(elephant, Side.FIRST), "象不可能将军")

        val advisor = board(
            redKing = redKing,
            blackKing = Square(3, 0),
            pieces = arrayOf(Square(3, 8) has black(PieceType.ADVISOR)),
        )
        assertFalse(rules.isInCheck(advisor, Side.FIRST), "士不可能将军")
    }

    @Test
    @DisplayName("炮架不分敌我：己方的子也能给敌炮当炮架")
    fun `己方子当炮架`() {
        val board = board(
            redKing = Square(4, 9),
            blackKing = Square(3, 0),
            pieces = arrayOf(
                Square(4, 5) has red(PieceType.CHARIOT),
                Square(4, 2) has black(PieceType.CANNON),
            ),
        )
        assertTrue(
            rules.isInCheck(board, Side.FIRST),
            "自己的車给对方的炮当了炮架，一样被将军",
        )
    }
}
