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
 * 蹩马腿。
 *
 * 这条规则重要在于：马腿贴着**起点**，不是起终点中点。
 * 判定点搞错会让马的走法变成对称的，那是另一种棋，
 * 也会让反向探测将军时漏判或误判。
 */
class HorseLegTest {

    private val rules = XiangqiRules()

    @Test
    @DisplayName("空盘的马有八个落点")
    fun `空盘八方向`() {
        val horse = Square(4, 5)
        val board = board(pieces = arrayOf(horse has red(PieceType.HORSE)))
        assertEquals(8, rules.legalMovesFrom(board, horse).size, "空处的马应有八个日字落点")
    }

    @Test
    @DisplayName("马腿被自己的子别住，那个方向的两个落点一起没了")
    fun `自己的子也蹩腿`() {
        val horse = Square(4, 5)
        // (4,4) 是马向 rank 递减方向走两格时的马腿，挡住它就断掉 (3,3) 与 (5,3)
        val board = board(
            pieces = arrayOf(
                horse has red(PieceType.HORSE),
                Square(4, 4) has red(PieceType.PAWN),
            ),
        )
        val destinations = rules.destinationsFrom(board, horse)
        assertFalse(Square(3, 3) in destinations, "马腿在 (4,4)，走不到 (3,3)")
        assertFalse(Square(5, 3) in destinations, "马腿在 (4,4)，走不到 (5,3)")
        assertEquals(6, destinations.size, "只应少掉共用同一条马腿的两个落点")
    }

    @Test
    @DisplayName("马腿被对方的子别住同样走不了：蹩腿不分敌我")
    fun `敌子也蹩腿`() {
        val horse = Square(4, 5)
        val board = board(
            pieces = arrayOf(
                horse has red(PieceType.HORSE),
                Square(4, 4) has black(PieceType.PAWN),
            ),
        )
        assertEquals(
            MoveRejection.HORSE_LEG_BLOCKED,
            rules.rejectionOf(board, horse, Square(3, 3)),
            "敌方的子挡在马腿上一样蹩腿",
        )
    }

    @Test
    @DisplayName("马腿不是起终点中点：中点有子不影响走马")
    fun `中点有子不蹩腿`() {
        val horse = Square(4, 5)
        // (3,4)、(5,4) 这类点是"日"字的斜中位置，不是马腿，摆子不应挡住马
        val board = board(
            pieces = arrayOf(
                horse has red(PieceType.HORSE),
                Square(3, 4) has red(PieceType.PAWN),
                Square(5, 4) has red(PieceType.PAWN),
            ),
        )
        val destinations = rules.destinationsFrom(board, horse)
        assertTrue(Square(3, 3) in destinations, "马腿在 (4,4) 而非 (3,4)，(3,3) 仍可走")
        assertTrue(Square(5, 3) in destinations, "马腿在 (4,4) 而非 (5,4)，(5,3) 仍可走")
    }

    @Test
    @DisplayName("马的走法是非对称的：红马能吃黑马，黑马吃不了红马")
    fun `蹩马腿导致走法非对称`() {
        val redHorse = Square(2, 5)
        val blackHorse = Square(3, 3)
        // 红马 (2,5)->(3,3) 走的是 (+1,-2)，rank 走两格，马腿在 (2,4)
        // 黑马 (3,3)->(2,5) 走的是 (-1,+2)，rank 走两格，马腿在 (3,4)
        // 只在 (3,4) 摆一枚子：红马照吃，黑马被蹩住
        val board = board(
            sideToMove = Side.FIRST,
            pieces = arrayOf(
                redHorse has red(PieceType.HORSE),
                blackHorse has black(PieceType.HORSE),
                Square(3, 4) has red(PieceType.PAWN),
            ),
        )

        assertTrue(
            Square(3, 3) in rules.destinationsFrom(board, redHorse),
            "红马的马腿在 (2,4)，那里是空的，可以吃黑马",
        )
        // 用只判规则的重载：这里要问的是"黑马能不能这么走"，与轮到谁走无关
        assertEquals(
            MoveRejection.HORSE_LEG_BLOCKED,
            rules.rejectionOf(board, blackHorse, redHorse),
            "黑马的马腿在 (3,4) 被占，吃不了红马 —— 这就是马的非对称性",
        )
        assertEquals(
            MoveRejection.WRONG_TURN,
            rules.rejectionOf(board, blackHorse, redHorse, Side.SECOND),
            "带轮次的重载要先报不该你走，两条路径的语义不能混",
        )
    }

    @Test
    @DisplayName("横向走两格时马腿在横向第一格")
    fun `横向蹩腿`() {
        val horse = Square(4, 5)
        // 走 (+2,±1) 的马腿在 (5,5)
        val board = board(
            pieces = arrayOf(
                horse has red(PieceType.HORSE),
                Square(5, 5) has black(PieceType.PAWN),
            ),
        )
        val destinations = rules.destinationsFrom(board, horse)
        assertFalse(Square(6, 4) in destinations, "马腿在 (5,5)，走不到 (6,4)")
        assertFalse(Square(6, 6) in destinations, "马腿在 (5,5)，走不到 (6,6)")
        assertTrue(Square(2, 4) in destinations, "另一侧的 (3,5) 是空的，(2,4) 可走")
    }
}
