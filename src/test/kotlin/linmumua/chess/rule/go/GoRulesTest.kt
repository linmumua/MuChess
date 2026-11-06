package linmumua.chess.rule.go

import linmumua.chess.rule.core.Board
import linmumua.chess.rule.core.Piece
import linmumua.chess.rule.core.PieceType
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.core.Square
import linmumua.chess.rule.xiangqi.GameOutcome
import linmumua.chess.rule.xiangqi.MoveRejection
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 围棋规则测试。
 *
 * 覆盖提子、禁自杀、打劫、双虚着终局四条核心规则，每条各自独立用例。
 * 这四条的共性是**出错时都不抛异常**：自杀判错只是"这手莫名不让下"，
 * 提子漏了只是"这块怎么还在"，劫判错只是"可以无限提回"。全靠测试兜住。
 */
class GoRulesTest {

    private fun rules() = GoRules()

    private fun board() = Board(linmumua.chess.rule.core.BoardSpec.GO, Side.FIRST)

    private fun Board.put(file: Int, rank: Int, side: Side) {
        this[Square(file, rank)] = Piece(PieceType.STONE, side)
    }

    /** 走一手并断言合法，返回实际落地的着法 */
    private fun GoRules.play(board: Board, file: Int, rank: Int): linmumua.chess.rule.core.Move {
        val square = Square(file, rank)
        assertNull(rejectionOf(board, square, square), "($file,$rank) 这手应当合法")
        val move = moveAt(board, square, square)
        assertNotNull(move)
        apply(board, move)
        return move
    }

    @Test
    @DisplayName("提子：补上最后一口气后整块被提掉，lastCaptures 如实带回点位")
    fun `capture removes the whole group and reports squares`() {
        val rules = rules()
        val board = board()

        // 白两子竖排，黑已围住除 (4,5) 外的所有点
        board.put(5, 5, Side.SECOND)
        board.put(5, 6, Side.SECOND)
        board.put(5, 4, Side.FIRST)
        board.put(5, 7, Side.FIRST)
        board.put(6, 5, Side.FIRST)
        board.put(6, 6, Side.FIRST)
        board.put(4, 6, Side.FIRST)

        rules.play(board, 4, 5)

        assertTrue(board.isEmpty(Square(5, 5)), "被提的子必须从盘上消失")
        assertTrue(board.isEmpty(Square(5, 6)), "整块都要提，不能只提一颗")
        // lastCaptures 是渲染层删显示实体的唯一依据 —— 漏一个点就在世界里留一枚幽灵子
        assertEquals(
            setOf(Square(5, 5), Square(5, 6)),
            rules.lastCaptures().toSet(),
            "lastCaptures 必须如实带回本手提掉的全部点位",
        )
    }

    @Test
    @DisplayName("禁自杀：填进对方围好的空里，自己没气，判 GO_SUICIDE")
    fun `suicide is rejected`() {
        val rules = rules()
        val board = board()

        // 白把 (5,5) 四面围住，黑填进去就是自杀
        board.put(4, 5, Side.SECOND)
        board.put(6, 5, Side.SECOND)
        board.put(5, 4, Side.SECOND)
        board.put(5, 6, Side.SECOND)

        val target = Square(5, 5)
        assertEquals(
            MoveRejection.GO_SUICIDE,
            rules.rejectionOf(board, target, target),
            "填进对方围好的一口空是自杀，必须拒绝",
        )
    }

    @Test
    @DisplayName("紧气自救：填自己最后一口气但能提掉对方，合法 —— 提子必须先于自杀判定")
    fun `capturing rescue is legal because captures resolve first`() {
        val rules = rules()
        val board = board()

        // 构造：黑落 (0,1) 后自己这块只有 (0,0) 一个邻位，而 (0,0) 是白子且会被提掉。
        // 白 (0,0) 在角上，气是 (1,0) 与 (0,1)。(1,0) 已是黑子，所以黑落 (0,1) 提掉它。
        board.put(0, 0, Side.SECOND)
        board.put(1, 0, Side.FIRST)
        // 给黑落点 (0,1) 制造"除了 (0,0) 之外没有别的气"的处境：
        // (0,1) 的邻位是 (0,0)、(1,1)、(0,2)。把 (1,1) 与 (0,2) 都填白子，
        // 于是黑落下去时自己那颗的唯一空邻位就是被提掉后腾出来的 (0,0)。
        board.put(1, 1, Side.SECOND)
        board.put(0, 2, Side.SECOND)

        val target = Square(0, 1)
        // 若实现把"判自己有没有气"放在提子之前，这一手会被误判成 GO_SUICIDE
        assertNull(
            rules.rejectionOf(board, target, target),
            "能提掉对方的一手不是自杀，提子必须先算",
        )

        rules.play(board, 0, 1)
        assertTrue(board.isEmpty(Square(0, 0)), "白角上那颗应被提掉")
        assertEquals(listOf(Square(0, 0)), rules.lastCaptures())
    }

    @Test
    @DisplayName("打劫：立刻提回被拒，隔一手之后可以提")
    fun `ko forbids immediate recapture but allows it later`() {
        val rules = rules()
        val board = board()

        // 标准劫形：中央做一个互相可提的单子对
        //   . B W .
        //   B a b W      a=(2,1) 黑可提, b=(3,1)
        //   . B W .
        // 布局（file, rank）
        board.put(2, 0, Side.FIRST)
        board.put(3, 0, Side.SECOND)
        board.put(1, 1, Side.FIRST)
        board.put(4, 1, Side.SECOND)
        board.put(2, 2, Side.FIRST)
        board.put(3, 2, Side.SECOND)
        // 白先占 (2,1)，它的气只剩 (3,1)
        board.put(2, 1, Side.SECOND)

        // 黑落 (3,1) 提掉白 (2,1)
        rules.play(board, 3, 1)
        assertTrue(board.isEmpty(Square(2, 1)), "黑应提掉白 (2,1)")
        assertEquals(listOf(Square(2, 1)), rules.lastCaptures())

        // 白想立刻在 (2,1) 提回黑 (3,1)：这会让局面还原成上一手之前，是劫
        val koPoint = Square(2, 1)
        assertEquals(
            MoveRejection.GO_KO,
            rules.rejectionOf(board, koPoint, koPoint),
            "立刻提回会还原局面，必须判劫",
        )

        // 白在别处走一手，黑也应一手，劫就解了
        rules.play(board, 15, 15)
        rules.play(board, 16, 16)

        assertNull(
            rules.rejectionOf(board, koPoint, koPoint),
            "隔了一手之后局面不同，同一点可以下了",
        )
    }

    @Test
    @DisplayName("双方连续虚着触发数子终局，单方虚着不终局")
    fun `two consecutive passes end the game`() {
        val rules = rules()
        val board = board()

        assertEquals(GameOutcome.Ongoing, rules.outcome(board, Side.FIRST), "开局不该终局")

        // 黑虚着
        rules.apply(board, rules.passMove(Side.FIRST))
        assertEquals(1, rules.passCount)
        assertEquals(
            GameOutcome.Ongoing,
            rules.outcome(board, board.sideToMove),
            "只有一方虚着时对局继续",
        )

        // 白也虚着 → 终局
        rules.apply(board, rules.passMove(Side.SECOND))
        assertEquals(2, rules.passCount)
        val outcome = rules.outcome(board, board.sideToMove)
        assertTrue(outcome is GameOutcome.ScoreWin, "双方连续虚着应产出数子终局，实际 $outcome")
        assertTrue(outcome.isFinished)
    }

    @Test
    @DisplayName("落子会把连续虚着数清零：虚着→落子→虚着不该终局")
    fun `a placement resets the pass counter`() {
        val rules = rules()
        val board = board()

        rules.apply(board, rules.passMove(Side.FIRST))
        assertEquals(1, rules.passCount)

        // 中间插一手真棋
        rules.play(board, 9, 9)
        assertEquals(0, rules.passCount, "落子必须把虚着数清零")

        rules.apply(board, rules.passMove(board.sideToMove))
        assertEquals(1, rules.passCount, "这只是本轮第一次虚着")
        assertEquals(
            GameOutcome.Ongoing,
            rules.outcome(board, board.sideToMove),
            "虚着→落子→虚着不是连续两手虚着，不该终局",
        )
    }

    @Test
    @DisplayName("虚着切换行棋方，对手仍能正常落子")
    fun `pass switches the turn`() {
        val rules = rules()
        val board = board()

        assertEquals(Side.FIRST, board.sideToMove)
        rules.apply(board, rules.passMove(Side.FIRST))
        assertEquals(Side.SECOND, board.sideToMove, "虚着之后该对方走")

        // 白正常落子
        val target = Square(3, 3)
        assertNull(rules.rejectionOf(board, target, target, Side.SECOND))
        rules.play(board, 3, 3)
        assertEquals(Side.SECOND, board[Square(3, 3)]?.side, "落下的应是白子")
    }

    @Test
    @DisplayName("撤销一手提子后整块恢复原位")
    fun `undo restores every captured stone`() {
        val rules = rules()
        val board = board()

        board.put(5, 5, Side.SECOND)
        board.put(5, 6, Side.SECOND)
        board.put(5, 4, Side.FIRST)
        board.put(5, 7, Side.FIRST)
        board.put(6, 5, Side.FIRST)
        board.put(6, 6, Side.FIRST)
        board.put(4, 6, Side.FIRST)

        val before = board.toFen()
        val move = rules.play(board, 4, 5)
        assertTrue(board.isEmpty(Square(5, 5)))

        rules.undo(board, move)

        assertEquals(before, board.toFen(), "撤销后局面必须与走之前完全一致")
        assertEquals(Side.SECOND, board[Square(5, 5)]?.side, "被提的白子要回到原位")
        assertEquals(Side.SECOND, board[Square(5, 6)]?.side)
        assertTrue(board.isEmpty(Square(4, 5)), "落下的那颗要收回")
    }

    @Test
    @DisplayName("撤销虚着恢复轮次与虚着计数")
    fun `undo of a pass restores turn and counter`() {
        val rules = rules()
        val board = board()

        val pass = rules.passMove(Side.FIRST)
        rules.apply(board, pass)
        assertEquals(1, rules.passCount)
        assertEquals(Side.SECOND, board.sideToMove)

        rules.undo(board, pass)

        assertEquals(0, rules.passCount, "撤销虚着要把计数退回")
        assertEquals(Side.FIRST, board.sideToMove, "撤销虚着要把轮次退回")
    }

    @Test
    @DisplayName("撤销提子手之后，劫快照也要退回，原本被判劫的点重新可下")
    fun `undo also restores the ko snapshot`() {
        val rules = rules()
        val board = board()

        board.put(2, 0, Side.FIRST)
        board.put(3, 0, Side.SECOND)
        board.put(1, 1, Side.FIRST)
        board.put(4, 1, Side.SECOND)
        board.put(2, 2, Side.FIRST)
        board.put(3, 2, Side.SECOND)
        board.put(2, 1, Side.SECOND)

        val capture = rules.play(board, 3, 1)
        val koPoint = Square(2, 1)
        assertEquals(MoveRejection.GO_KO, rules.rejectionOf(board, koPoint, koPoint))

        rules.undo(board, capture)

        // 撤销之后局面回到白 (2,1) 还在盘上的状态，(3,1) 空着
        assertEquals(Side.SECOND, board[koPoint]?.side, "白子应回到 (2,1)")
        assertTrue(board.isEmpty(Square(3, 1)))
    }

    @Test
    @DisplayName("已有子的点不能再下")
    fun `occupied square is rejected`() {
        val rules = rules()
        val board = board()
        board.put(9, 9, Side.SECOND)

        val target = Square(9, 9)
        assertEquals(
            MoveRejection.SQUARE_OCCUPIED,
            rules.rejectionOf(board, target, target),
            "点到已有子的点应明确拒绝",
        )
    }

    @Test
    @DisplayName("不该你走时拒绝")
    fun `wrong turn is rejected`() {
        val rules = rules()
        val board = board()
        val target = Square(9, 9)

        assertEquals(
            MoveRejection.WRONG_TURN,
            rules.rejectionOf(board, target, target, Side.SECOND),
            "开局是黑先，白落子应判轮次错",
        )
    }

    @Test
    @DisplayName("legalMoves 不含虚着，且每个着法都落在空点上")
    fun `legal moves never include a pass`() {
        val rules = rules()
        val board = board()
        board.put(9, 9, Side.FIRST)

        val moves = rules.legalMoves(board, Side.FIRST)

        // 361 个点减去已占的 1 个
        assertEquals(360, moves.size, "19×19 空盘占 1 子后应剩 360 个合法落点")
        assertTrue(moves.none { it.pass }, "legalMoves 不该把虚着混进来")
        assertTrue(
            moves.all { board.isEmpty(it.to) },
            "每个着法都必须落在空点上",
        )
    }

    @Test
    @DisplayName("围棋没有将军概念")
    fun `go never warns`() {
        val rules = rules()
        val board = board()
        assertTrue(!rules.needsWarning(board, Side.FIRST))
        assertTrue(!rules.needsWarning(board, Side.SECOND))
    }

    @Test
    @DisplayName("局面串往返一致，19 路空行不会被拆错")
    fun `fen round trip survives nineteen wide rows`() {
        val rules = rules()
        val board = board()
        board.put(0, 0, Side.FIRST)
        board.put(18, 18, Side.SECOND)
        board.put(9, 9, Side.FIRST)

        val fen = rules.toFen(board)
        val restored = rules.fromFen(fen)

        assertEquals(fen, rules.toFen(restored), "往返之后局面串必须一致")
        assertEquals(Side.FIRST, restored[Square(0, 0)]?.side)
        assertEquals(Side.SECOND, restored[Square(18, 18)]?.side)
        assertEquals(Side.FIRST, restored[Square(9, 9)]?.side)
    }
}
