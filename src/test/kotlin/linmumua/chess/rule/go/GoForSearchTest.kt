package linmumua.chess.rule.go

import linmumua.chess.rule.core.Piece
import linmumua.chess.rule.core.PieceType
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.core.Square
import linmumua.chess.rule.gomoku.GomokuRules
import linmumua.chess.rule.xiangqi.MoveRejection
import linmumua.chess.rule.xiangqi.XiangqiRules
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 搜索用规则实例的隔离测试。
 *
 * 这组是本阶段最重要的测试。机器人搜索跑在异步线程，棋盘传的是快照，
 * 但规则实例原先是**直接共用**的。围棋规则持有三份可变状态（撤销栈、劫快照、
 * 虚着计数），共用会让搜索与主局互相踩。
 *
 * 最糟的后果不是搜索算错，而是**主局被搞坏**：搜索展开虚着分支会自增虚着计数，
 * 主线程此刻判终局就会在无人虚着的情况下直接数子结束对局。玩家看到的是
 * "下着棋突然就终局了"，而且完全无法复现（取决于线程时序）。
 */
class GoForSearchTest {

    private fun Board_put(board: linmumua.chess.rule.core.Board, file: Int, rank: Int, side: Side) {
        board[Square(file, rank)] = Piece(PieceType.STONE, side)
    }

    @Test
    @DisplayName("围棋的搜索实例是另一个对象，不是主局那个")
    fun `go returns a distinct instance for search`() {
        val main = GoRules()
        val search = main.forSearch()

        assertNotSame(main, search, "共用同一个实例就是这组测试要防的那个 bug")
        assertTrue(search is GoRules)
    }

    @Test
    @DisplayName("在搜索实例上连续虚着，主局的虚着计数必须不动")
    fun `passing on the search instance never touches the main game`() {
        val main = GoRules()
        val board = main.initialBoard()
        val search = main.forSearch() as GoRules
        val searchBoard = board.copy()

        // 模拟搜索展开虚着分支：连续两手虚着
        search.apply(searchBoard, search.passMove(Side.FIRST))
        search.apply(searchBoard, search.passMove(Side.SECOND))

        assertEquals(2, search.passCount, "搜索实例自己应当记到 2")
        // 这一条是核心：主局一次虚着都没有过
        assertEquals(0, main.passCount, "主局的虚着计数必须为 0，否则会凭空终局")
        assertTrue(
            !main.outcome(board, Side.FIRST).isFinished,
            "主局不该因为搜索虚着过就判终局",
        )
    }

    @Test
    @DisplayName("搜索实例继承劫快照：主局刚形成的劫，搜索也认")
    fun `search instance inherits the ko snapshot`() {
        val rules = GoRules()
        val board = rules.initialBoard()

        // 摆标准劫形并让黑提掉白一子
        Board_put(board, 2, 0, Side.FIRST)
        Board_put(board, 3, 0, Side.SECOND)
        Board_put(board, 1, 1, Side.FIRST)
        Board_put(board, 4, 1, Side.SECOND)
        Board_put(board, 2, 2, Side.FIRST)
        Board_put(board, 3, 2, Side.SECOND)
        Board_put(board, 2, 1, Side.SECOND)

        val capture = rules.moveAt(board, Square(3, 1), Square(3, 1))!!
        rules.apply(board, capture)

        val koPoint = Square(2, 1)
        assertEquals(
            MoveRejection.GO_KO,
            rules.rejectionOf(board, koPoint, koPoint),
            "前提：主局这一点现在是劫",
        )

        // 搜索实例必须也认这个劫。不继承的话它会选出这一手，
        // 回主线程被 rejectionOf 拒掉，机器人白走一轮（只留一条 warning）
        val search = rules.forSearch()
        assertEquals(
            MoveRejection.GO_KO,
            search.rejectionOf(board.copy(), koPoint, koPoint),
            "搜索实例必须继承劫快照，否则会选出一手在主线程被拒的着法",
        )
    }

    @Test
    @DisplayName("搜索实例继承虚着计数：知道对方刚虚着，自己再虚就终局")
    fun `search instance inherits the pass count`() {
        val main = GoRules()
        val board = main.initialBoard()

        // 主局：黑虚着一手
        main.apply(board, main.passMove(Side.FIRST))
        assertEquals(1, main.passCount)

        val search = main.forSearch() as GoRules
        assertEquals(
            1,
            search.passCount,
            "搜索要知道对方刚虚着，否则算不出'我再虚一手就终局'",
        )
    }

    @Test
    @DisplayName("搜索实例不继承撤销栈：它有自己的一套试走序列")
    fun `search instance starts with a clean undo stack`() {
        val main = GoRules()
        val board = main.initialBoard()

        // 主局走两手，撤销栈里有两帧
        val first = main.moveAt(board, Square(3, 3), Square(3, 3))!!
        main.apply(board, first)
        val second = main.moveAt(board, Square(15, 15), Square(15, 15))!!
        main.apply(board, second)

        val search = main.forSearch() as GoRules
        val searchBoard = board.copy()

        // 搜索在自己的快照上试走再撤销，全程不该碰到主局的历史
        val probe = search.moveAt(searchBoard, Square(9, 9), Square(9, 9))!!
        search.apply(searchBoard, probe)
        search.undo(searchBoard, probe)

        // 主局局面完全没变
        assertEquals(Side.FIRST, board[Square(3, 3)]?.side, "主局第一手还在")
        assertEquals(Side.SECOND, board[Square(15, 15)]?.side, "主局第二手还在")
        assertTrue(board.isEmpty(Square(9, 9)), "搜索的试走不该出现在主局盘上")
    }

    @Test
    @DisplayName("搜索实例改劫快照，不影响主局的劫判定")
    fun `search instance ko changes do not leak back`() {
        val main = GoRules()
        val board = main.initialBoard()
        val search = main.forSearch() as GoRules
        val searchBoard = board.copy()

        // 搜索里落几手，会不断改写它自己的劫快照
        var current: Side = Side.FIRST
        listOf(Square(3, 3), Square(4, 4), Square(5, 5)).forEach { point ->
            val move = search.moveAt(searchBoard, point, point)!!
            search.apply(searchBoard, move)
            current = current.opposite
        }

        // 主局仍是开局状态，任何空点都能下
        val target = Square(9, 9)
        assertNull(
            main.rejectionOf(board, target, target),
            "主局的劫快照不该被搜索改动过",
        )
    }

    @Test
    @DisplayName("无状态棋种的搜索实例就是自己，不白建对象")
    fun `stateless rules return themselves`() {
        val xiangqi = XiangqiRules()
        val gomoku = GomokuRules()

        // 象棋与五子棋不持有可变状态，没有隔离的必要。
        // 返回自身而不是新建，是为了让这条优化显式可见：
        // 每次机器人思考都白建一个对象虽然不贵，但也没有任何理由。
        assertSame(xiangqi, xiangqi.forSearch(), "象棋无状态，应返回自身")
        assertSame(gomoku, gomoku.forSearch(), "五子棋无状态，应返回自身")
    }

    @Test
    @DisplayName("搜索实例沿用主局的规则可调项")
    fun `search instance keeps the rule options`() {
        val main = GoRules(GoRuleOptions(komi = 6.5))
        val search = main.forSearch() as GoRules

        assertEquals(
            6.5,
            search.options.komi,
            "贴子不一致会让搜索按另一套胜负标准算，选出的手在主局未必最优",
        )
    }
}
