package linmumua.chess.game

import linmumua.chess.interact.ClickAction
import linmumua.chess.interact.ClickResolver
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.core.Square
import linmumua.chess.rule.gomoku.GomokuRules
import linmumua.chess.rule.xiangqi.MoveRejection
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 五子棋接入对局流程与交互层的测试。
 *
 * 规则本身在 GomokuRulesTest 里测过了，这里盯的是**接线**是否对：
 * 落子式棋种走的是"单击直接落子"这条路，而象棋走的是"选中再移动"。
 * 两条路径混了不会编译报错，只会让五子棋点一下没反应、或者要点两下才落子。
 */
class GomokuGameTest {

    private val first = UUID.randomUUID()
    private val second = UUID.randomUUID()

    /**
     * 开一局两边都坐满并已开局的五子棋。
     *
     * 入座与开局拆成两步之后必须补 [ChessGame.start]：只 join 的对局停在 WAITING，
     * 下面所有落子都会被 NotPlaying 拒掉。五子棋与象棋走同一套对局框架，
     * 这条也一样 —— 放下的五子棋盘同样是空盘，点开始才铺。
     */
    private fun startedGame(): ChessGame {
        val game = ChessGame(GomokuRules())
        game.join(first, Side.FIRST)
        game.join(second, Side.SECOND)
        game.start(0L)
        return game
    }

    @Test
    @DisplayName("五子棋点空点要直接落子，不能走象棋那套先选中再移动")
    fun `click on empty point places a stone directly`() {
        val game = startedGame()
        val action = ClickResolver.resolve(
            board = game.board,
            clicked = Square(7, 7),
            selection = null,
            mover = Side.FIRST,
            isPlacement = game.rules.isPlacement,
        )
        // 象棋在这里会得到 Ignore（空点没东西可选），五子棋必须得到 Place
        assertEquals(ClickAction.Place(Square(7, 7)), action)
    }

    @Test
    @DisplayName("象棋点空点仍然什么都不做，落子式分支不能污染象棋的交互")
    fun `xiangqi still ignores clicks on empty points`() {
        val game = ChessGame(linmumua.chess.rule.xiangqi.XiangqiRules())
        game.join(first, Side.FIRST)
        game.join(second, Side.SECOND)
        game.start(0L)
        val action = ClickResolver.resolve(
            board = game.board,
            clicked = Square(4, 4),
            selection = null,
            mover = Side.FIRST,
            isPlacement = game.rules.isPlacement,
        )
        assertEquals(ClickAction.Ignore, action)
    }

    @Test
    @DisplayName("五子棋点已有子的点当点空，不进选中态也不报错")
    fun `click on occupied point is ignored for placement rules`() {
        val game = startedGame()
        game.move(first, Square(7, 7), Square(7, 7), 0L)

        val action = ClickResolver.resolve(
            board = game.board,
            clicked = Square(7, 7),
            selection = null,
            mover = Side.SECOND,
            isPlacement = game.rules.isPlacement,
        )
        assertEquals(ClickAction.Ignore, action)
    }

    @Test
    @DisplayName("落子经过 ChessGame.move 这唯一入口，轮次照样推进")
    fun `placement goes through the single move entry`() {
        val game = startedGame()
        val point = Square(7, 7)

        val attempt = game.move(first, point, point, 0L)
        assertIs<MoveAttempt.Success>(attempt)
        assertEquals(point, attempt.move.from, "落子式的起点应与终点相同")
        assertEquals(point, attempt.move.to)
        assertEquals(Side.FIRST, game.board[point]?.side)
        assertEquals(Side.SECOND, game.board.sideToMove, "落完应轮到对手")
        assertEquals(1, game.moveCount)
    }

    @Test
    @DisplayName("五子棋不给机器人开后门：抢下、下到已有子的点都要被规则挡住")
    fun `placement respects turn and occupancy through the game entry`() {
        val game = startedGame()
        val point = Square(7, 7)

        // 后行方抢下
        assertEquals(
            MoveRejection.WRONG_TURN,
            assertIs<MoveAttempt.Rejected>(game.move(second, point, point, 0L)).rejection,
        )

        game.move(first, point, point, 0L)
        // 同一个点再下
        assertEquals(
            MoveRejection.SQUARE_OCCUPIED,
            assertIs<MoveAttempt.Rejected>(game.move(second, point, point, 0L)).rejection,
        )
    }

    @Test
    @DisplayName("连五要让这局立刻结束并给出连五结局，不能报成将死")
    fun `five in a row finishes the game with a line win`() {
        val game = startedGame()
        // 先行方在 rank 7 连四，后行方在别处应付
        val firstPoints = listOf(3, 4, 5, 6)
        for ((index, file) in firstPoints.withIndex()) {
            game.move(first, Square(file, 7), Square(file, 7), 0L)
            game.move(second, Square(index, 0), Square(index, 0), 0L)
        }
        assertEquals(GamePhase.PLAYING, game.phase, "四连时还不该结束")

        // 第五子成连五
        val attempt = game.move(first, Square(7, 7), Square(7, 7), 0L)
        assertIs<MoveAttempt.Success>(attempt)
        assertTrue(attempt.outcome.isFinished, "连五后应判终局")
        assertEquals(GamePhase.FINISHED, game.phase)
        assertEquals(GameResult.LineWin(Side.FIRST, Side.SECOND), game.result)
    }

    @Test
    @DisplayName("五子棋不该出现将军提示，那是象棋才有的东西")
    fun `gomoku never reports a check`() {
        val game = startedGame()
        val attempt = assertIs<MoveAttempt.Success>(
            game.move(first, Square(7, 7), Square(7, 7), 0L),
        )
        assertTrue(!attempt.opponentInCheck, "五子棋不该报将军")
    }
}
