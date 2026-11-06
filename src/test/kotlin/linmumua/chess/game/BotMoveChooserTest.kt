package linmumua.chess.game

import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import linmumua.chess.rule.core.Move
import linmumua.chess.rule.core.Piece
import linmumua.chess.rule.core.PieceType
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.core.Square
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 测试机器人选步测试。
 *
 * 这些用例保的不是"机器人下得好"，而是"机器人不会把对局卡死、也不会明显乱走"：
 * - 返回 null 只允许发生在真的无步可走时，否则轮次会永久停在机器人这边；
 * - 白给的子必须吃，否则测试对局时看不出它在响应局面；
 * - 随机打散不能越界选出候选之外的着法。
 */
class BotMoveChooserTest {

    /** 走子方固定用红車，选步逻辑只看被吃子，不看走的是谁 */
    private val mover = Piece(PieceType.CHARIOT, Side.FIRST)

    /** 造一步吃子：吃掉指定类型的黑子 */
    private fun capture(type: PieceType): Move = Move(
        from = Square(0, 9),
        to = Square(0, 0),
        piece = mover,
        captured = Piece(type, Side.SECOND),
    )

    /** 造一步不吃子的普通着法 */
    private fun quiet(file: Int): Move = Move(
        from = Square(file, 9),
        to = Square(file, 8),
        piece = mover,
    )

    @Test
    @DisplayName("有白给的車时必须吃車，不能去走无关的闲步")
    fun `prefers the most valuable capture`() {
        val moves = listOf(
            quiet(1),
            capture(PieceType.PAWN),
            capture(PieceType.CHARIOT),
            capture(PieceType.HORSE),
        )
        val chosen = BotMoveChooser.choose(moves, Random(1))
        assertNotNull(chosen)
        assertEquals(PieceType.CHARIOT, chosen.captured?.type, "没有挑价值最高的吃子步")
    }

    @Test
    @DisplayName("没有吃子步时仍要返回一步合法着法，不能返回 null 把轮次卡住")
    fun `returns a quiet move when nothing can be captured`() {
        val moves = listOf(quiet(1), quiet(2), quiet(3))
        val chosen = BotMoveChooser.choose(moves, Random(7))
        assertNotNull(chosen, "有步可走却返回了 null，对局会永久停在机器人这边")
        assertTrue(chosen in moves)
    }

    @Test
    @DisplayName("真的无步可走时返回 null，交给上层判将死或困毙")
    fun `returns null only when there is no move`() {
        assertNull(BotMoveChooser.choose(emptyList()))
    }

    @Test
    @DisplayName("只有一步可走时必须返回那一步")
    fun `single move is always chosen`() {
        val only = quiet(4)
        assertEquals(only, BotMoveChooser.choose(listOf(only), Random(3)))
    }

    @Test
    @DisplayName("同分多步随机打散，但结果必须落在同分候选里，不能越界")
    fun `random pick stays inside the tied candidates`() {
        // 三步都吃馬，分数相同；换不同随机种子结果可以不同，但都必须是吃馬那三步之一
        val tied = listOf(
            Move(Square(0, 9), Square(0, 0), mover, Piece(PieceType.HORSE, Side.SECOND)),
            Move(Square(1, 9), Square(1, 0), mover, Piece(PieceType.HORSE, Side.SECOND)),
            Move(Square(2, 9), Square(2, 0), mover, Piece(PieceType.HORSE, Side.SECOND)),
        )
        val moves = tied + quiet(5)
        for (seed in 0..20) {
            val chosen = BotMoveChooser.choose(moves, Random(seed))
            assertNotNull(chosen)
            assertTrue(chosen in tied, "种子 $seed 选出了同分候选之外的着法")
        }
    }

    @Test
    @DisplayName("能吃将的一步压过任何其他吃子步")
    fun `capturing the king outranks everything`() {
        val moves = listOf(capture(PieceType.CHARIOT), capture(PieceType.KING))
        val chosen = BotMoveChooser.choose(moves, Random(0))
        assertEquals(PieceType.KING, chosen?.captured?.type)
    }

    @Test
    @DisplayName("不吃子的着法得分为 0，吃子的得分为正")
    fun `score reflects capture value`() {
        assertEquals(0, BotMoveChooser.scoreOf(quiet(0)))
        assertTrue(BotMoveChooser.scoreOf(capture(PieceType.PAWN)) > 0)
        assertTrue(
            BotMoveChooser.scoreOf(capture(PieceType.CHARIOT)) >
                BotMoveChooser.scoreOf(capture(PieceType.CANNON)),
            "車应当比炮值钱",
        )
    }
}
