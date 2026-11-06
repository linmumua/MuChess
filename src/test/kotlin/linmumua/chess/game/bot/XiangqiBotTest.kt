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
import linmumua.chess.rule.xiangqi.XiangqiRules
import org.junit.jupiter.api.Test

/**
 * 象棋机器人测试。
 *
 * 从一层贪心升级到 alpha-beta 之后，要保住的是三条：
 * 1. **能将死就将死** —— 放着一步杀不走，说明搜索根本没在算终局；
 * 2. **不白送子** —— 一层贪心最典型的毛病是拿車去吃有保护的兵，两层能看出来；
 * 3. **不改坏局面** —— 搜索在真实棋盘上试走再撤销，配对漏一次就静默改坏对局。
 *
 * 用摆残局而不是走开局：开局阶段任何着法都不算错，测不出东西。
 */
class XiangqiBotTest {

    private val rules = XiangqiRules()

    /** 摆一个空盘，指定行棋方 */
    private fun emptyBoard(sideToMove: Side = Side.FIRST) = Board(BoardSpec.XIANGQI, sideToMove)

    private fun Board.put(file: Int, rank: Int, type: PieceType, side: Side) {
        this[Square(file, rank)] = Piece(type, side)
    }

    private fun choose(
        board: Board,
        side: Side,
        options: BotOptions = BotOptions.DEFAULT,
        seed: Int = 1,
    ) = BotBrain.chooseMove(rules, board, side, options, Random(seed))

    /**
     * 一步杀必须走出来。
     *
     * 摆一个白脸将局面：黑将在 (4,0)，红車已在 (4,5) 同纵线上，中间无子。
     * 红車直接推到 (4,1) 之后黑将被将死 —— 这一步不走就是把赢棋放掉。
     */
    @Test
    fun `有一步杀时必须走出来`() {
        val board = emptyBoard(Side.FIRST)
        board.put(4, 0, PieceType.KING, Side.SECOND)
        board.put(4, 9, PieceType.KING, Side.FIRST)
        // 红車与黑将同纵线，黑将无士无象，也没有可逃的格
        board.put(4, 5, PieceType.CHARIOT, Side.FIRST)
        board.put(3, 2, PieceType.CHARIOT, Side.FIRST)
        board.put(5, 2, PieceType.CHARIOT, Side.FIRST)

        val move = assertNotNull(choose(board, Side.FIRST))
        rules.apply(board, move)
        val outcome = rules.outcome(board, Side.SECOND)
        rules.undo(board, move)

        assertTrue(
            outcome.isFinished,
            "有一步杀却没走出来，走的是 $move（走完后局面仍是 $outcome）",
        )
    }

    /**
     * 白给的車必须吃。
     *
     * 这条是从一层贪心继承下来的底线：升级到搜索之后仍然要吃白给的大子。
     * 摆成"红車能吃黑車，黑車无保护"，吃了之后对手拿不回来。
     */
    @Test
    fun `白给的車必须吃`() {
        val board = emptyBoard(Side.FIRST)
        // 两将必须错开纵线：同纵线且中间无子就是飞将局面，
        // 那时红方**所有**不挡住这条线的着法都是非法的（送将），
        // 吃車自然也走不了 —— 局面本身就把要测的东西挡掉了。
        board.put(3, 0, PieceType.KING, Side.SECOND)
        board.put(4, 9, PieceType.KING, Side.FIRST)
        // 黑車孤零零摆在红車正前方，没有任何子保护它
        board.put(0, 5, PieceType.CHARIOT, Side.FIRST)
        board.put(0, 3, PieceType.CHARIOT, Side.SECOND)

        val move = assertNotNull(choose(board, Side.FIRST))
        assertEquals(
            PieceType.CHARIOT,
            move.captured?.type,
            "没吃白给的車，走的是 $move",
        )
    }

    /**
     * 不能拿車去换一个有保护的兵。
     *
     * 这正是一层贪心的典型错手：它只看到"吃到一个兵 +100"，看不到下一手
     * 自己的車（900）会被抵子吃掉。两层搜索必须能看出这是亏本买卖。
     *
     * 局面设计：红車能吃黑兵，但黑兵背后有黑車保护；同时另有一步安全的吃兵。
     */
    @Test
    fun `不拿車去吃有保护的兵`() {
        val board = emptyBoard(Side.FIRST)
        // 两将错开纵线，避免飞将把红方的着法全判成送将（见"白给的車必须吃"的注释）
        board.put(3, 0, PieceType.KING, Side.SECOND)
        board.put(4, 9, PieceType.KING, Side.FIRST)
        // 红車在 (0,5)，黑兵在 (0,3)，黑車在 (0,1) 保护着那个兵
        board.put(0, 5, PieceType.CHARIOT, Side.FIRST)
        board.put(0, 3, PieceType.PAWN, Side.SECOND)
        board.put(0, 1, PieceType.CHARIOT, Side.SECOND)

        val move = assertNotNull(choose(board, Side.FIRST))

        val tookProtectedPawn = move.from == Square(0, 5) && move.to == Square(0, 3)
        assertTrue(
            !tookProtectedPawn,
            "拿車去吃有黑車保护的兵，等于送掉車换兵：$move",
        )
    }

    /**
     * 被将军时选出的着法必须真的解将。
     *
     * 规则层的送将过滤已经保证 legalMoves 里都是解将的手，这条测试盯的是
     * 机器人**没有绕过规则层**去自己造着法 —— 一旦绕过，它会走出非法手，
     * 然后被 `ChessGame.move` 拒绝，轮次永久卡在机器人这边。
     */
    @Test
    fun `被将军时走出的着法能解将`() {
        val board = emptyBoard(Side.FIRST)
        board.put(4, 9, PieceType.KING, Side.FIRST)
        board.put(4, 0, PieceType.KING, Side.SECOND)
        // 黑車在 (4,4) 直接照着红将，红方必须应将
        board.put(4, 4, PieceType.CHARIOT, Side.SECOND)
        board.put(8, 9, PieceType.CHARIOT, Side.FIRST)

        val move = assertNotNull(choose(board, Side.FIRST))
        assertNull(
            rules.rejectionOf(board, move.from, move.to, Side.FIRST),
            "被将军时走出了非法着法：$move",
        )
        rules.apply(board, move)
        val stillInCheck = rules.needsWarning(board, Side.FIRST)
        rules.undo(board, move)
        assertTrue(!stillInCheck, "走完之后红方仍在被将军：$move")
    }

    /**
     * 搜索结束后局面必须与传入时完全一致。
     *
     * 搜索在真实棋盘上试走再撤销（不整盘拷贝）。apply/undo 配对漏一次就会
     * 静默改坏正在进行的对局，玩家只看到棋子凭空移动。
     */
    @Test
    fun `搜索不改动传入的局面`() {
        val board = rules.initialBoard()
        val before = board.toFen()

        choose(board, Side.FIRST)

        assertEquals(before, board.toFen(), "搜索把传入的局面改坏了")
    }

    /** 开局局面必须能选出一步合法着法，不能返回 null 把轮次卡住 */
    @Test
    fun `开局能选出合法着法`() {
        val board = rules.initialBoard()
        for (seed in 1..5) {
            val move = assertNotNull(choose(board, Side.FIRST, seed = seed))
            assertNull(
                rules.rejectionOf(board, move.from, move.to, Side.FIRST),
                "第 $seed 次开局选出了非法着法：$move",
            )
        }
    }

    /**
     * 已经被将死时返回 null。
     *
     * 上层靠 null 判断"无步可走"，返回一个假着法会让终局判定错乱。
     */
    @Test
    fun `无合法着法时返回空`() {
        val board = emptyBoard(Side.SECOND)
        board.put(4, 0, PieceType.KING, Side.SECOND)
        board.put(4, 9, PieceType.KING, Side.FIRST)
        // 黑将被红車困死在九宫顶格，且两侧都被控制
        board.put(4, 1, PieceType.CHARIOT, Side.FIRST)
        board.put(3, 1, PieceType.CHARIOT, Side.FIRST)
        board.put(5, 1, PieceType.CHARIOT, Side.FIRST)

        // 先确认局面真的无着可走，否则这条测试测的不是它想测的东西
        assertTrue(
            rules.legalMoves(board, Side.SECOND).isEmpty(),
            "测试局面本身还有着法可走，用例前提不成立",
        )
        assertNull(choose(board, Side.SECOND))
    }

    /**
     * 估值必须反对称：同一局面在双方视角下互为相反数。
     *
     * negamax 把对手的得分直接取负当自己的得分用，这条不成立整套搜索就是错的。
     */
    @Test
    fun `估值在双方视角下反对称`() {
        val board = rules.initialBoard()
        board.put(0, 3, PieceType.CHARIOT, Side.FIRST)
        val evaluator = XiangqiEvaluator(rules)
        assertEquals(
            evaluator.evaluate(board, Side.FIRST),
            -evaluator.evaluate(board, Side.SECOND),
        )
    }

    /**
     * 开局摆好的局面双方完全对称，估值必须是 0。
     *
     * 不为 0 说明位置分算错了方向 —— 最典型的是把"过河"的判断写反，
     * 结果一方的兵在自己家里反而得分。这种错误在实战里表现为机器人
     * 开局就把兵往后拱，但不会抛任何异常。
     */
    @Test
    fun `开局局面估值为零`() {
        val evaluator = XiangqiEvaluator(rules)
        assertEquals(0, evaluator.evaluate(rules.initialBoard(), Side.FIRST))
    }

    /**
     * 过河兵必须比未过河的兵值钱。
     *
     * 过河兵能左右走，威胁面大得多。这条是位置分里唯一真正影响下法的一项，
     * 方向写反会让机器人不敢过河。
     */
    @Test
    fun `过河兵的估值高于未过河的兵`() {
        val evaluator = XiangqiEvaluator(rules)

        val home = emptyBoard(Side.FIRST)
        home.put(4, 9, PieceType.KING, Side.FIRST)
        home.put(4, 0, PieceType.KING, Side.SECOND)
        home.put(0, 6, PieceType.PAWN, Side.FIRST)

        val crossed = emptyBoard(Side.FIRST)
        crossed.put(4, 9, PieceType.KING, Side.FIRST)
        crossed.put(4, 0, PieceType.KING, Side.SECOND)
        crossed.put(0, 3, PieceType.PAWN, Side.FIRST)

        assertTrue(
            evaluator.evaluate(crossed, Side.FIRST) > evaluator.evaluate(home, Side.FIRST),
            "过河兵的估值没有高于未过河的兵，位置分方向可能写反了",
        )
    }
}
