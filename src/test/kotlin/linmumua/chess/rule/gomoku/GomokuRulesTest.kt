package linmumua.chess.rule.gomoku

import linmumua.chess.rule.core.Board
import linmumua.chess.rule.core.BoardSpec
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.core.Square
import linmumua.chess.rule.xiangqi.GameOutcome
import linmumua.chess.rule.xiangqi.MoveRejection
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 五子棋规则测试。
 *
 * 重点不是"函数返回了什么"，而是这几条规则各自为什么重要：
 * 连五必须四个方向都算（少一个方向就有一整类赢法不生效）、
 * 落子式的 apply/undo 必须真的能撤掉（from==to 的写法容易写成撤销后子还在）、
 * 满盘要判和（不判会让对局永远结束不了）。
 */
class GomokuRulesTest {

    private val rules = GomokuRules()

    /** 在盘上按 (file, rank) 顺序摆一串同色子 */
    private fun Board.place(side: Side, vararg points: Pair<Int, Int>) {
        for ((file, rank) in points) {
            this[Square(file, rank)] = GomokuRules.placementAt(Square(file, rank), side).piece
        }
    }

    @Test
    @DisplayName("横竖撇捺四个方向的连五都要算胜，漏一个方向就有一类赢法失效")
    fun `line win is detected in all four directions`() {
        val cases = mapOf(
            "横" to listOf(3 to 7, 4 to 7, 5 to 7, 6 to 7, 7 to 7),
            "竖" to listOf(7 to 3, 7 to 4, 7 to 5, 7 to 6, 7 to 7),
            "撇" to listOf(3 to 3, 4 to 4, 5 to 5, 6 to 6, 7 to 7),
            "捺" to listOf(3 to 7, 4 to 6, 5 to 5, 6 to 4, 7 to 3),
        )
        for ((name, points) in cases) {
            val board = Board(BoardSpec.GOMOKU, Side.FIRST)
            board.place(Side.FIRST, *points.toTypedArray())
            assertTrue(rules.hasLine(board, Side.FIRST), "$name 方向的五连没被判胜")
            // 判定的是刚落子那一方：落完之后 sideToMove 已切给对手
            assertEquals(
                GameOutcome.LineWin(Side.FIRST),
                rules.outcome(board, Side.SECOND),
                "$name 方向应判先行方连五取胜",
            )
        }
    }

    @Test
    @DisplayName("四连不算胜，否则会提前一手结束对局")
    fun `four in a row is not a win`() {
        val board = Board(BoardSpec.GOMOKU, Side.FIRST)
        board.place(Side.FIRST, 3 to 7, 4 to 7, 5 to 7, 6 to 7)
        assertFalse(rules.hasLine(board, Side.FIRST), "四连不该判胜")
        assertEquals(GameOutcome.Ongoing, rules.outcome(board, Side.SECOND))
    }

    @Test
    @DisplayName("被对方子截断的五个点不算连五，数连子必须看颜色")
    fun `opponent stone breaks the line`() {
        val board = Board(BoardSpec.GOMOKU, Side.FIRST)
        board.place(Side.FIRST, 3 to 7, 4 to 7, 6 to 7, 7 to 7)
        board.place(Side.SECOND, 5 to 7)
        assertFalse(rules.hasLine(board, Side.FIRST), "中间夹了对方子，不该判连五")
    }

    @Test
    @DisplayName("长连（六子）在无禁手时同样算胜，写成恰好等于五会让六连赢不了")
    fun `overline still wins when forbidden moves are off`() {
        val board = Board(BoardSpec.GOMOKU, Side.FIRST)
        board.place(Side.FIRST, 3 to 7, 4 to 7, 5 to 7, 6 to 7, 7 to 7, 8 to 7)
        assertTrue(rules.hasLine(board, Side.FIRST), "六连应判胜")
    }

    @Test
    @DisplayName("落子只能下在空点，重复落子必须被否掉")
    fun `placement requires an empty point`() {
        val board = rules.initialBoard()
        val point = Square(7, 7)
        assertNull(rules.rejectionOf(board, point, point), "空点应可落子")

        board.place(Side.FIRST, 7 to 7)
        assertEquals(
            MoveRejection.SQUARE_OCCUPIED,
            rules.rejectionOf(board, point, point),
            "已有子的点不该能再落",
        )
        assertNull(rules.moveAt(board, point, point), "落点有子时不该补出着法")
    }

    @Test
    @DisplayName("盘外的点要判越界，不能靠编码碰巧落在数组空洞里")
    fun `off board placement is rejected`() {
        val board = rules.initialBoard()
        // (16, 16) 超出 15×15，但在 Square 的 32 步长编码内是合法值
        val outside = Square(16, 16)
        assertEquals(MoveRejection.OFF_BOARD, rules.rejectionOf(board, outside, outside))
    }

    @Test
    @DisplayName("落子式的 apply/undo 必须真能撤掉，from 与 to 同点的写法极易撤不干净")
    fun `apply and undo round trip for placements`() {
        val board = rules.initialBoard()
        val point = Square(7, 7)
        val move = assertNotNull(rules.moveAt(board, point, point))

        rules.apply(board, move)
        assertEquals(Side.FIRST, board[point]?.side, "落子后该点应是先行方的子")
        assertEquals(Side.SECOND, board.sideToMove, "落子后应轮到对手")

        rules.undo(board, move)
        assertTrue(board.isEmpty(point), "撤销后该点必须变回空点")
        assertEquals(Side.FIRST, board.sideToMove, "撤销后行棋方应还原")
        assertEquals(225, board.emptySquares().size, "撤销后应回到空盘")
    }

    @Test
    @DisplayName("轮次要判，否则一方可以连下两手")
    fun `wrong turn is rejected`() {
        val board = rules.initialBoard()
        val point = Square(7, 7)
        assertEquals(
            MoveRejection.WRONG_TURN,
            rules.rejectionOf(board, point, point, Side.SECOND),
            "空盘开局该先行方下，后行方抢下要被否",
        )
        assertNull(rules.rejectionOf(board, point, point, Side.FIRST))
    }

    @Test
    @DisplayName("满盘且无人连五要判和，否则这局永远结束不了")
    fun `full board without a line is a draw`() {
        val board = Board(BoardSpec.GOMOKU, Side.FIRST)
        // 填色规则 (file/2 + rank) % 2：横竖撇捺四个方向的同色连续长度都不超过 2。
        // 用过 (file/2 + rank/2) % 2，那个在主对角线上是常量，会凑出 15 连。
        for (square in BoardSpec.GOMOKU.squares()) {
            val side = if ((square.file / 2 + square.rank) % 2 == 0) Side.FIRST else Side.SECOND
            board[square] = GomokuRules.placementAt(square, side).piece
        }
        assertTrue(board.emptySquares().isEmpty(), "盘面应已填满")

        val outcome = rules.outcome(board, Side.FIRST)
        // 这个填法若意外凑出连五，说明用例的构造有问题，要先修用例
        assertFalse(rules.hasLine(board, Side.FIRST), "构造的满盘局面不该有先行方连五")
        assertFalse(rules.hasLine(board, Side.SECOND), "构造的满盘局面不该有后行方连五")
        assertEquals(GameOutcome.DrawByFullBoard, outcome)
    }

    @Test
    @DisplayName("空盘有 225 个合法着法，落一子后少一个")
    fun `legal moves cover every empty point`() {
        val board = rules.initialBoard()
        assertEquals(225, rules.legalMoves(board, Side.FIRST).size)

        board.place(Side.FIRST, 7 to 7)
        assertEquals(224, rules.legalMoves(board, Side.SECOND).size)
    }

    @Test
    @DisplayName("落子式棋种不给'某枚子能走哪'，交互层不该拿到象棋那样的落点列表")
    fun `legal moves from is empty for placement rules`() {
        val board = rules.initialBoard()
        board.place(Side.FIRST, 7 to 7)
        assertTrue(rules.legalMovesFrom(board, Square(7, 7)).isEmpty())
        assertTrue(rules.isPlacement, "五子棋必须标记为落子式")
    }

    @Test
    @DisplayName("局面串往返要一致，否则存档再读会错位")
    fun `fen round trip keeps the position`() {
        val board = rules.initialBoard()
        board.place(Side.FIRST, 3 to 7, 4 to 7, 7 to 7)
        board.place(Side.SECOND, 3 to 8, 4 to 8)

        val restored = rules.fromFen(rules.toFen(board))
        assertEquals(rules.toFen(board), rules.toFen(restored))
        for (square in BoardSpec.GOMOKU.squares()) {
            assertEquals(board[square], restored[square], "$square 上的子应一致")
        }
    }

    @Test
    @DisplayName("整行空点会写成两位数，解析必须整段读 —— 一位一位读会让整盘错位")
    fun `fen parses multi digit empty runs`() {
        // 15×15 的空盘每行都是 15 个空点，写出来是 "15"。
        // 按单字符解析会读成 1 和 5，只前进 6 格，宽度校验直接失败。
        val empty = rules.initialBoard()
        val fen = rules.toFen(empty)
        assertTrue(fen.startsWith("15/"), "空行应写成两位数的 15，实际 $fen")

        val restored = rules.fromFen(fen)
        assertEquals(225, restored.emptySquares().size, "还原后应仍是 225 个空点的空盘")

        // 一行里既有子又有长空段，是最容易暴露解析错位的形状
        val mixed = rules.initialBoard()
        mixed[Square(0, 0)] = GomokuRules.placementAt(Square(0, 0), Side.FIRST).piece
        mixed[Square(14, 0)] = GomokuRules.placementAt(Square(14, 0), Side.SECOND).piece
        val mixedBack = rules.fromFen(rules.toFen(mixed))
        assertEquals(Side.FIRST, mixedBack[Square(0, 0)]?.side, "行首的子位置不该偏移")
        assertEquals(Side.SECOND, mixedBack[Square(14, 0)]?.side, "行尾的子位置不该偏移")
    }

    @Test
    @DisplayName("五子棋没有将军，不能给出将军提示")
    fun `gomoku never warns about check`() {
        val board = rules.initialBoard()
        board.place(Side.FIRST, 3 to 7, 4 to 7, 5 to 7, 6 to 7)
        assertFalse(rules.needsWarning(board, Side.FIRST))
        assertFalse(rules.needsWarning(board, Side.SECOND))
    }
}
