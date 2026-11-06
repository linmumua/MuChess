package linmumua.chess.game

import java.util.UUID
import linmumua.chess.rule.core.Piece
import linmumua.chess.rule.core.PieceType
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.core.Square
import linmumua.chess.rule.go.GoRules
import linmumua.chess.rule.gomoku.GomokuRules
import linmumua.chess.rule.xiangqi.GameOutcome
import linmumua.chess.rule.xiangqi.XiangqiRules
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 围棋接入对局流程的测试。
 *
 * 规则层已由 `rule/go` 那几个测试覆盖，这里只管"规则层算出来的东西有没有正确
 * 交到上层"—— 尤其是提子点位：它是渲染层删显示实体的唯一依据，断在这一层
 * 就会在世界里留下幽灵子，而规则层的测试完全看不到这个问题。
 */
class GoGameFlowTest {

    private val black = UUID.randomUUID()
    private val white = UUID.randomUUID()

    /** 建一局已开始的围棋 */
    private fun startedGo(): ChessGame {
        val game = ChessGame(GoRules())
        game.join(black, Side.FIRST)
        game.join(white, Side.SECOND)
        game.start(0L)
        return game
    }

    private fun ChessGame.put(file: Int, rank: Int, side: Side) {
        board[Square(file, rank)] = Piece(PieceType.STONE, side)
    }

    @Test
    @DisplayName("提子点位如实交到 MoveAttempt.Success.captured —— 渲染层靠它删实体")
    fun `captured squares reach the move attempt`() {
        val game = startedGo()

        // 白两子竖排，黑围住除 (4,5) 外的所有点
        game.put(5, 5, Side.SECOND)
        game.put(5, 6, Side.SECOND)
        game.put(5, 4, Side.FIRST)
        game.put(5, 7, Side.FIRST)
        game.put(6, 5, Side.FIRST)
        game.put(6, 6, Side.FIRST)
        game.put(4, 6, Side.FIRST)

        val target = Square(4, 5)
        val attempt = game.move(black, target, target, 1L)

        assertTrue(attempt is MoveAttempt.Success, "这手应当合法，实际 $attempt")
        assertEquals(
            setOf(Square(5, 5), Square(5, 6)),
            attempt.captured.toSet(),
            "提掉的两枚都必须出现在 captured 里，漏一枚就在世界里留一枚幽灵子",
        )
    }

    @Test
    @DisplayName("象棋吃子不走 captured —— 默认值没有波及既有棋种")
    fun `xiangqi never populates captured`() {
        val game = ChessGame(XiangqiRules())
        game.join(black, Side.FIRST)
        game.join(white, Side.SECOND)
        game.start(0L)

        // 红炮二平五那类走法太绕，直接摆一个能吃的局面：
        // 红車横扫吃掉黑卒。開局黑卒在 rank 3，红車在 (0,9)。
        // 简单点：把黑卒挪到红車正前方
        game.board[Square(0, 6)] = Piece(PieceType.PAWN, Side.SECOND)
        val attempt = game.move(black, Square(0, 9), Square(0, 6), 1L)

        assertTrue(attempt is MoveAttempt.Success, "红車直吃应当合法，实际 $attempt")
        assertTrue(attempt.move.isCapture, "这一手确实吃了子")
        assertTrue(
            attempt.captured.isEmpty(),
            "象棋被吃的那枚记在 Move.captured 里，不该同时出现在提子列表",
        )
    }

    @Test
    @DisplayName("五子棋落子同样不产出 captured")
    fun `gomoku never populates captured`() {
        val game = ChessGame(GomokuRules())
        game.join(black, Side.FIRST)
        game.join(white, Side.SECOND)
        game.start(0L)

        val target = Square(7, 7)
        val attempt = game.move(black, target, target, 1L)

        assertTrue(attempt is MoveAttempt.Success)
        assertTrue(attempt.captured.isEmpty(), "五子棋不吃子，提子列表必须为空")
    }

    @Test
    @DisplayName("双方连续虚着让对局进入终局并记下数子结果")
    fun `two passes finish the game with a score result`() {
        val game = startedGo()

        val first = game.pass(black, 1L)
        assertTrue(first is PassAttempt.Passed, "黑虚着应成功，实际 $first")
        assertEquals(GameOutcome.Ongoing, first.outcome, "只有一方虚着时对局继续")
        assertEquals(GamePhase.PLAYING, game.phase)

        val second = game.pass(white, 2L)
        assertTrue(second is PassAttempt.Passed)
        assertTrue(second.outcome.isFinished, "双方连续虚着应终局")
        assertEquals(GamePhase.FINISHED, game.phase)

        val result = game.result
        assertTrue(result is GameResult.ScoreWin, "终局结果应是数子，实际 $result")
        // 空盘 + 贴子 3.75：黑 0，白 3.75，白胜
        assertEquals(Side.SECOND, result.winner, "空盘按贴子算白胜")
    }

    @Test
    @DisplayName("虚着后对手仍能正常落子，且落子把虚着数清零")
    fun `pass then move keeps the game running`() {
        val game = startedGo()

        game.pass(black, 1L)
        assertEquals(Side.SECOND, game.sideToMove, "虚着之后该白走")

        val target = Square(3, 3)
        val attempt = game.move(white, target, target, 2L)
        assertTrue(attempt is MoveAttempt.Success, "虚着之后对手应能正常落子")

        // 白落了子，黑再虚着只算本轮第一次
        val third = game.pass(black, 3L)
        assertTrue(third is PassAttempt.Passed)
        assertEquals(
            GameOutcome.Ongoing,
            third.outcome,
            "虚着→落子→虚着不是连续两手虚着，不该终局",
        )
        assertEquals(GamePhase.PLAYING, game.phase)
    }

    @Test
    @DisplayName("象棋与五子棋虚着返回 NotSupported，不会误终局")
    fun `non go games reject a pass`() {
        listOf(ChessGame(XiangqiRules()), ChessGame(GomokuRules())).forEach { game ->
            game.join(black, Side.FIRST)
            game.join(white, Side.SECOND)
            game.start(0L)

            assertEquals(
                PassAttempt.NotSupported,
                game.pass(black, 1L),
                "${game.rules::class.simpleName} 没有虚着，必须明确拒绝",
            )
            assertEquals(GamePhase.PLAYING, game.phase, "拒绝虚着不能顺带把对局结束掉")
        }
    }

    @Test
    @DisplayName("没轮到时虚着被拒，轮次不变")
    fun `pass out of turn is rejected`() {
        val game = startedGo()

        assertEquals(PassAttempt.WrongTurn, game.pass(white, 1L), "开局是黑先，白虚着应被拒")
        assertEquals(Side.FIRST, game.sideToMove, "被拒的虚着不能推进轮次")
    }

    @Test
    @DisplayName("未开局时不能虚着")
    fun `pass before start is rejected`() {
        val game = ChessGame(GoRules())
        game.join(black, Side.FIRST)

        val attempt = game.pass(black, 1L)
        assertTrue(attempt is PassAttempt.NotPlaying, "等人阶段不能虚着，实际 $attempt")
        assertEquals(GamePhase.WAITING, attempt.phase)
    }

    @Test
    @DisplayName("局外人不能虚着")
    fun `outsider cannot pass`() {
        val game = startedGo()
        assertEquals(PassAttempt.NotAPlayer, game.pass(UUID.randomUUID(), 1L))
    }

    @Test
    @DisplayName("虚着进入历史，可以撤销")
    fun `pass is recorded in history and can be undone`() {
        val game = startedGo()

        game.pass(black, 1L)
        assertEquals(1, game.moveCount, "虚着要进历史，否则撤不掉")
        assertTrue(game.lastMove?.pass == true, "历史里最后一手应标记为虚着")

        val undone = game.undoLast()
        assertTrue(undone?.pass == true, "撤销应还回那手虚着")
        assertEquals(0, game.moveCount)
        assertEquals(Side.FIRST, game.sideToMove, "撤销虚着要把轮次退回黑方")
    }
}
