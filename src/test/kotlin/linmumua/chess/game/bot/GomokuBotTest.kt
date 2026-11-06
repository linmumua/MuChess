package linmumua.chess.game.bot

import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import linmumua.chess.rule.core.Board
import linmumua.chess.rule.core.BoardSpec
import linmumua.chess.rule.core.Piece
import linmumua.chess.rule.core.PieceType
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.core.Square
import linmumua.chess.rule.gomoku.GomokuRules
import org.junit.jupiter.api.Test

/**
 * 五子棋机器人测试。
 *
 * 这个文件的核心是两条：**自己能连五必须下、对手能连五必须堵**。
 * 这两条是"勉强会玩"与"随便下"的分界线 —— 一个不会堵四的机器人，
 * 无论搜索写得多漂亮，玩家第一局就会发现它在乱下。
 *
 * 其余用例保护的是这两条不被实现细节破坏：候选裁剪不能把必防点裁掉，
 * 搜索深度配成 1 层也必须堵，估值符号不能反。
 */
class GomokuBotTest {

    private val rules = GomokuRules()

    /** 摆一个空盘，行棋方为先行（黑） */
    private fun board(sideToMove: Side = Side.FIRST) = Board(BoardSpec.GOMOKU, sideToMove)

    /** 在 (file, rank) 摆一子，不走规则校验，纯摆局面 */
    private fun Board.place(file: Int, rank: Int, side: Side) {
        this[Square(file, rank)] = Piece(PieceType.STONE, side)
    }

    /** 横向连续摆 [count] 子 */
    private fun Board.row(startFile: Int, rank: Int, count: Int, side: Side) {
        for (offset in 0 until count) {
            place(startFile + offset, rank, side)
        }
    }

    private fun choose(
        board: Board,
        side: Side,
        options: BotOptions = BotOptions.DEFAULT,
        seed: Int = 1,
    ) = BotBrain.chooseMove(rules, board, side, options, Random(seed))

    /**
     * 自己已有四连时必须补成五，不能去做别的。
     *
     * 这是最基本的一条：机器人放着能赢的一手不走，说明它连"赢"这件事都没在算。
     */
    @Test
    fun `己方能连五时必须落在成五点上`() {
        val board = board(Side.FIRST)
        // 黑子横向四连 (3,7)..(6,7)，成五点在 (2,7) 或 (7,7)
        board.row(startFile = 3, rank = 7, count = 4, side = Side.FIRST)
        // 白子在别处也做出四连，用来引诱机器人去堵而不是自己赢
        board.row(startFile = 3, rank = 10, count = 4, side = Side.SECOND)

        val move = assertNotNull(choose(board, Side.FIRST))
        val winning = setOf(Square(2, 7), Square(7, 7))
        assertTrue(
            move.to in winning,
            "己方能连五却没下成五点，走的是 ${move.to}（成五点是 $winning）",
        )
    }

    /**
     * 对手已有四连、自己还赢不了时，必须堵。
     *
     * 这是"会玩"的下限。不堵的机器人下一手就输，玩家会立刻看出它没在防守。
     */
    @Test
    fun `对手能连五时必须堵在成五点上`() {
        val board = board(Side.FIRST)
        // 白子横向四连 (3,7)..(6,7)，成五点 (2,7) / (7,7)
        board.row(startFile = 3, rank = 7, count = 4, side = Side.SECOND)
        // 黑子只有两子，赢不了，只能堵
        board.row(startFile = 8, rank = 2, count = 2, side = Side.FIRST)

        val move = assertNotNull(choose(board, Side.FIRST))
        val blocking = setOf(Square(2, 7), Square(7, 7))
        assertTrue(
            move.to in blocking,
            "对手能连五却没堵，走的是 ${move.to}（必堵点是 $blocking）",
        )
    }

    /**
     * 双方都能一步连五时，先赢自己的，别去堵。
     *
     * 顺序反了不会报错，只会让机器人在能赢的局面下选择防守，然后被对手赢掉。
     */
    @Test
    fun `双方都能连五时先自己赢`() {
        val board = board(Side.FIRST)
        board.row(startFile = 3, rank = 5, count = 4, side = Side.FIRST)
        board.row(startFile = 3, rank = 10, count = 4, side = Side.SECOND)

        val move = assertNotNull(choose(board, Side.FIRST))
        rules.apply(board, move)
        assertTrue(
            rules.hasLine(board, Side.FIRST),
            "双方都能连五时机器人没有先赢，走的是 ${move.to}",
        )
    }

    /**
     * 深度配成 1 层时，必胜必防依然要出得来。
     *
     * 必走手刻意放在搜索**之前**，就是为了不依赖深度。这条测试盯住那个设计：
     * 如果哪天把必走手挪进搜索里，1 层深度下就会漏堵。
     */
    @Test
    fun `深度一层也能堵住对手的四连`() {
        val board = board(Side.FIRST)
        board.row(startFile = 4, rank = 6, count = 4, side = Side.SECOND)
        board.place(0, 0, Side.FIRST)

        val shallow = BotOptions(depth = 1)
        val move = assertNotNull(choose(board, Side.FIRST, shallow))
        assertTrue(
            move.to in setOf(Square(3, 6), Square(8, 6)),
            "1 层深度下没堵住四连，走的是 ${move.to}",
        )
    }

    /**
     * 候选上限被压到 1 时，必防手依然要出得来。
     *
     * 必走手不经过 candidates()，所以不受 maxCandidates 截断影响。
     * 这条与上一条一起把"必走手独立于搜索参数"这个性质钉死。
     */
    @Test
    fun `候选被截断到一个也能堵住四连`() {
        val board = board(Side.FIRST)
        board.row(startFile = 4, rank = 6, count = 4, side = Side.SECOND)
        board.place(0, 0, Side.FIRST)

        val narrow = BotOptions(depth = 2, maxCandidates = 1)
        val move = assertNotNull(choose(board, Side.FIRST, narrow))
        assertTrue(
            move.to in setOf(Square(3, 6), Square(8, 6)),
            "候选截断到 1 后没堵住四连，走的是 ${move.to}",
        )
    }

    /**
     * 对手做出**活三**（两端都空的三连）时应该去干扰，不能在盘的另一头自己下。
     *
     * 活三不堵下一手就是活四，活四堵不住。这条比"堵四"要求高一档：
     * 堵四靠必走手，堵活三必须靠估值 —— 估值里对手威胁的权重给错就会挂。
     */
    @Test
    fun `对手活三时落子在威胁线附近`() {
        val board = board(Side.FIRST)
        // 白子活三 (6,7)(7,7)(8,7)，两端 (5,7)(9,7) 都空
        board.row(startFile = 6, rank = 7, count = 3, side = Side.SECOND)
        // 黑子一枚孤子，离战场很远
        board.place(1, 1, Side.FIRST)

        val move = assertNotNull(choose(board, Side.FIRST))
        // 落点应落在白子三连所在横线的延长范围内，或紧贴三连
        val nearThreat = kotlin.math.abs(move.to.rank - 7) <= 1 &&
            move.to.file in 4..10
        assertTrue(
            nearThreat,
            "对手活三时机器人跑去别处下，走的是 ${move.to}",
        )
    }

    /**
     * 空盘时下天元，不能随机丢在角上。
     *
     * 空盘上所有点的威胁分都是 0，不特判就会退化成随机选点，
     * 机器人第一手下在 (0,0) 看着就是在乱下。
     */
    @Test
    fun `空盘第一手下在中心`() {
        val move = assertNotNull(choose(board(Side.FIRST), Side.FIRST))
        assertEquals(Square(7, 7), move.to, "空盘第一手没下天元")
    }

    /** 满盘无处可下时返回 null，让上层按终局处理，而不是抛异常 */
    @Test
    fun `满盘时返回空`() {
        val board = board(Side.FIRST)
        for (square in BoardSpec.GOMOKU.squares()) {
            board[square] = Piece(PieceType.STONE, Side.SECOND)
        }
        assertNull(choose(board, Side.FIRST))
    }

    /**
     * 搜索结束后局面必须与传入时完全一致。
     *
     * 搜索是在**同一个 board** 上试走再撤销（不整盘拷贝，225 格拷太贵）。
     * apply/undo 配对漏一次就会把真实对局的棋盘改坏，而且是静默的 ——
     * 玩家只会看到棋盘上凭空多出或少掉一颗子。
     */
    @Test
    fun `搜索不改动传入的局面`() {
        val board = board(Side.FIRST)
        board.row(startFile = 5, rank = 7, count = 3, side = Side.FIRST)
        board.row(startFile = 5, rank = 8, count = 2, side = Side.SECOND)
        val before = board.toFen()

        choose(board, Side.FIRST)

        assertEquals(before, board.toFen(), "搜索把传入的局面改坏了")
    }

    /**
     * 机器人选出的必须是合法着法。
     *
     * 落在已有子上的着法会被 `ChessGame.move` 拒绝，机器人就卡住不动了。
     */
    @Test
    fun `选出的着法一定合法`() {
        val board = board(Side.FIRST)
        board.row(startFile = 4, rank = 4, count = 3, side = Side.FIRST)
        board.row(startFile = 4, rank = 5, count = 3, side = Side.SECOND)

        for (seed in 1..8) {
            val move = assertNotNull(choose(board, Side.FIRST, seed = seed))
            assertNull(
                rules.rejectionOf(board, move.from, move.to),
                "第 $seed 次选出了非法着法 ${move.to}",
            )
        }
    }

    /**
     * 落子式棋种的着法起点必须等于终点。
     *
     * 这是 `Move` 在落子棋里的退化约定；起点乱填会让渲染层去搬一枚不存在的子。
     */
    @Test
    fun `落子着法的起点与终点相同`() {
        val board = board(Side.FIRST)
        board.place(7, 7, Side.SECOND)
        val move = assertNotNull(choose(board, Side.FIRST))
        assertEquals(move.from, move.to)
        assertEquals(PieceType.STONE, move.piece.type)
        assertEquals(Side.FIRST, move.piece.side)
    }

    /**
     * 估值必须是**反对称**的：同一局面在双方视角下的分数互为相反数。
     *
     * negamax 的正确性完全建立在这条上（对手的得分就是自己得分的相反数）。
     * 破坏它会让搜索算出的分数在层与层之间不可比，剪枝跟着全错。
     */
    @Test
    fun `估值在双方视角下反对称`() {
        val board = board(Side.FIRST)
        board.row(startFile = 4, rank = 4, count = 3, side = Side.FIRST)
        board.row(startFile = 6, rank = 9, count = 2, side = Side.SECOND)

        val evaluator = GomokuEvaluator(rules)
        assertEquals(
            evaluator.evaluate(board, Side.FIRST),
            -evaluator.evaluate(board, Side.SECOND),
        )
    }
}
