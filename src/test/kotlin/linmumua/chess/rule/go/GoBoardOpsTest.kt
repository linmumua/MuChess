package linmumua.chess.rule.go

import linmumua.chess.rule.core.Board
import linmumua.chess.rule.core.BoardSpec
import linmumua.chess.rule.core.Piece
import linmumua.chess.rule.core.PieceType
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.core.Square
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 气与块的判定测试。
 *
 * 围棋所有规则都建立在"气"上：提子是气尽、禁自杀是自己气尽、数子靠围空归属。
 * 气数错一个，提子、自杀、劫、终局会同时错，而且都不抛异常 ——
 * 只会表现成"这块怎么没提掉"或"这手为什么不让下"。所以气必须单独钉死。
 */
class GoBoardOpsTest {

    private fun emptyBoard() = Board(BoardSpec.GO, Side.FIRST)

    private fun Board.put(file: Int, rank: Int, side: Side) {
        this[Square(file, rank)] = Piece(PieceType.STONE, side)
    }

    @Test
    @DisplayName("腹地单子四口气，边上三口，角上两口")
    fun `liberty count depends on position`() {
        val board = emptyBoard()

        // 腹地
        board.put(9, 9, Side.FIRST)
        assertEquals(4, GoBoardOps.groupAt(board, Square(9, 9)).libertyCount, "腹地单子应有 4 口气")

        // 边上（rank = 0 那条边）
        board.put(9, 0, Side.FIRST)
        assertEquals(3, GoBoardOps.groupAt(board, Square(9, 0)).libertyCount, "边上单子应有 3 口气")

        // 角上
        board.put(0, 0, Side.SECOND)
        assertEquals(2, GoBoardOps.groupAt(board, Square(0, 0)).libertyCount, "角上单子应有 2 口气")

        // 另外三个角一并验，避免只有 (0,0) 这个特例对
        board.put(18, 0, Side.SECOND)
        board.put(0, 18, Side.SECOND)
        board.put(18, 18, Side.SECOND)
        listOf(Square(18, 0), Square(0, 18), Square(18, 18)).forEach { corner ->
            assertEquals(2, GoBoardOps.groupAt(board, corner).libertyCount, "$corner 角上应有 2 口气")
        }
    }

    @Test
    @DisplayName("相邻只算四邻：斜向的两颗子是两块，不是一块")
    fun `diagonal stones are separate groups`() {
        val board = emptyBoard()
        board.put(5, 5, Side.FIRST)
        board.put(6, 6, Side.FIRST)

        val group = GoBoardOps.groupAt(board, Square(5, 5))
        assertEquals(1, group.stones.size, "斜向不相连，这块只有 1 颗子")
        assertFalse(Square(6, 6) in group.stones, "斜向的子不该被算进同一块")
    }

    @Test
    @DisplayName("直线相连的子算一块，共用的气只数一次")
    fun `connected stones share liberties counted once`() {
        val board = emptyBoard()
        // 横排三子
        board.put(5, 5, Side.FIRST)
        board.put(6, 5, Side.FIRST)
        board.put(7, 5, Side.FIRST)

        val group = GoBoardOps.groupAt(board, Square(6, 5))
        assertEquals(3, group.stones.size, "三子相连应是一块")
        // 三子横排：上下各 3 口，左右各 1 口，共 8。
        // 若把共用气重复计数会得到更大的值，那样气尽的块永远提不掉。
        assertEquals(8, group.libertyCount, "横排三子应有 8 口气")
    }

    @Test
    @DisplayName("被围死的单子判定为气尽")
    fun `surrounded stone is captured`() {
        val board = emptyBoard()
        board.put(5, 5, Side.FIRST)
        // 四面围住
        board.put(4, 5, Side.SECOND)
        board.put(6, 5, Side.SECOND)
        board.put(5, 4, Side.SECOND)
        board.put(5, 6, Side.SECOND)

        assertTrue(GoBoardOps.isCaptured(board, Square(5, 5)), "四面被围的单子应判气尽")
        assertEquals(0, GoBoardOps.groupAt(board, Square(5, 5)).libertyCount)
        assertTrue(GoBoardOps.groupAt(board, Square(5, 5)).isDead)
    }

    @Test
    @DisplayName("还剩一口气的子不算气尽")
    fun `stone with one liberty is alive`() {
        val board = emptyBoard()
        board.put(5, 5, Side.FIRST)
        board.put(4, 5, Side.SECOND)
        board.put(6, 5, Side.SECOND)
        board.put(5, 4, Side.SECOND)
        // (5,6) 留空

        assertFalse(GoBoardOps.isCaptured(board, Square(5, 5)), "还有一口气就不能提")
        assertEquals(1, GoBoardOps.groupAt(board, Square(5, 5)).libertyCount)
    }

    @Test
    @DisplayName("空点问块返回空，不抛异常")
    fun `empty square yields empty group`() {
        val board = emptyBoard()
        val group = GoBoardOps.groupAt(board, Square(3, 3))
        assertTrue(group.stones.isEmpty())
        assertTrue(group.liberties.isEmpty())
        assertFalse(group.isDead, "空点不该被当成死块")
        assertFalse(GoBoardOps.isCaptured(board, Square(3, 3)))
    }

    @Test
    @DisplayName("提子只找落点相邻的块，远处气尽的块不受这一手影响")
    fun `captures only consider neighbours of the placed stone`() {
        val board = emptyBoard()
        // 角上做一个白子，黑已经占了 (1,0)，只剩 (0,1) 一口气
        board.put(0, 0, Side.SECOND)
        board.put(1, 0, Side.FIRST)

        // 在远处落一手黑，不该提掉角上那颗白子（它还有气，且与落点不相邻）
        val far = GoBoardOps.capturesAfterPlacing(board, Square(10, 10), Side.SECOND)
        assertTrue(far.isEmpty(), "远处落子不该提掉任何子")

        // 补上最后一口气才提掉
        board.put(0, 1, Side.FIRST)
        val captured = GoBoardOps.capturesAfterPlacing(board, Square(0, 1), Side.SECOND)
        assertEquals(setOf(Square(0, 0)), captured, "补上最后一口气应提掉角上白子")
    }

    @Test
    @DisplayName("提一整块：同一块被落点的两个邻位碰到时只收一次")
    fun `capturing a whole group deduplicates`() {
        val board = emptyBoard()
        // 白两子竖排在 (5,5)(5,6)，黑围住除 (4,5) 之外的所有点
        board.put(5, 5, Side.SECOND)
        board.put(5, 6, Side.SECOND)
        board.put(5, 4, Side.FIRST)
        board.put(5, 7, Side.FIRST)
        board.put(6, 5, Side.FIRST)
        board.put(6, 6, Side.FIRST)
        board.put(4, 6, Side.FIRST)

        // 落在 (4,5) 补最后一口气，这一手的两个邻位 (5,5) 都属同一块
        board.put(4, 5, Side.FIRST)
        val captured = GoBoardOps.capturesAfterPlacing(board, Square(4, 5), Side.SECOND)

        assertEquals(
            setOf(Square(5, 5), Square(5, 6)),
            captured,
            "整块两子都该被提，且不重复",
        )
    }
}
