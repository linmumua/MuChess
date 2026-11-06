package linmumua.chess.rule.core

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * 棋盘尺寸与坐标编码测试。
 *
 * 尺寸参数化是加入五子棋与围棋的前置改动，它动了 [Square] 的编码方式。
 * 编码错了不会抛异常，只会让棋子悄悄落到别的点上，或者让盘外的点被当成盘内 ——
 * 所以这里要盯住三件事：编码往返一致、盘外判定不放过、三个棋种互不串味。
 */
class BoardSpecTest {

    @Test
    @DisplayName("三个棋种的格点编码往返必须一致，否则棋子会落到别的点上")
    fun `packed encoding round trips for every spec`() {
        for (spec in listOf(BoardSpec.XIANGQI, BoardSpec.GOMOKU, BoardSpec.GO)) {
            var count = 0
            for (square in spec.squares()) {
                assertEquals(square, Square(square.file, square.rank), "$square 编码往返不一致")
                count++
            }
            assertEquals(spec.squareCount, count, "${spec.files}×${spec.ranks} 的格点总数不对")
        }
    }

    @Test
    @DisplayName("连续下标与格点必须双向一一对应，存局面的数组靠它不越界")
    fun `index mapping is bijective`() {
        for (spec in listOf(BoardSpec.XIANGQI, BoardSpec.GOMOKU, BoardSpec.GO)) {
            val seen = mutableSetOf<Int>()
            for (square in spec.squares()) {
                val index = spec.indexOf(square)
                assertTrue(index in 0 until spec.squareCount, "$square 的下标 $index 越界")
                assertTrue(seen.add(index), "$square 的下标 $index 与别的点撞了")
                assertEquals(square, spec.squareAt(index), "下标 $index 反查回来不是 $square")
            }
            assertEquals(spec.squareCount, seen.size)
        }
    }

    @Test
    @DisplayName("象棋盘装不下五子棋的点：尺寸判定不能只看编码合法")
    fun `contains rejects squares outside the smaller board`() {
        // (10, 12) 在 15×15 的五子棋盘内，但象棋只有 9 纵线 10 横线
        val beyondXiangqi = Square(10, 12)
        assertTrue(BoardSpec.GOMOKU.contains(beyondXiangqi))
        assertFalse(BoardSpec.XIANGQI.contains(beyondXiangqi), "象棋盘不该认这个点")

        // (16, 16) 连五子棋都超了，只有围棋盘装得下
        val beyondGomoku = Square(16, 16)
        assertTrue(BoardSpec.GO.contains(beyondGomoku))
        assertFalse(BoardSpec.GOMOKU.contains(beyondGomoku), "五子棋盘不该认这个点")
    }

    @Test
    @DisplayName("Board 读写盘外点：读返回空，写直接炸，不能悄悄写进空洞里")
    fun `board guards against off board access`() {
        val board = Board(BoardSpec.XIANGQI)
        val outside = Square(10, 12)

        // 读盘外返回 null，車炮的射线扫描靠这个收尾
        assertNull(board[outside], "盘外的点应读成空")
        assertFalse(board.isEmpty(outside), "盘外不该被当成可落子的空点")

        // 写盘外必须炸：packed 编码带空洞，静默写进去等于数据损坏
        assertThrows<IllegalArgumentException> { board[outside] = Piece(PieceType.KING, Side.FIRST) }
    }

    @Test
    @DisplayName("同一个 packed 值在不同尺寸下解出的 file/rank 一致，这是固定步长编码的前提")
    fun `decoding does not depend on board size`() {
        val square = Square(7, 9)
        assertEquals(7, square.file)
        assertEquals(9, square.rank)
        // 三个棋种都认这个点，且解出来都是同一个 (7, 9)
        for (spec in listOf(BoardSpec.XIANGQI, BoardSpec.GOMOKU, BoardSpec.GO)) {
            assertTrue(spec.contains(square))
            assertEquals(7, square.file)
            assertEquals(9, square.rank)
        }
    }

    @Test
    @DisplayName("空点列表要按尺寸给全，五子棋与围棋的着法生成依赖它")
    fun `empty squares covers the whole board`() {
        val gomoku = Board(BoardSpec.GOMOKU)
        assertEquals(225, gomoku.emptySquares().size, "15×15 空盘应有 225 个空点")

        gomoku[Square(7, 7)] = Piece(PieceType.PAWN, Side.FIRST)
        assertEquals(224, gomoku.emptySquares().size, "下了一子后空点应少一个")
        assertFalse(gomoku.emptySquares().contains(Square(7, 7)))

        assertEquals(361, Board(BoardSpec.GO).emptySquares().size, "19×19 空盘应有 361 个空点")
    }

    @Test
    @DisplayName("超出编码步长的尺寸要当场拒绝，不能等到坐标错乱才发现")
    fun `spec rejects sizes beyond the encoding stride`() {
        assertThrows<IllegalArgumentException> { BoardSpec(files = Square.STRIDE + 1, ranks = 10) }
        assertThrows<IllegalArgumentException> { BoardSpec(files = 9, ranks = 0) }
    }
}
