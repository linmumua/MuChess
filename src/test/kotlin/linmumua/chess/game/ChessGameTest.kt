package linmumua.chess.game

import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import linmumua.chess.rule.core.Piece
import linmumua.chess.rule.core.PieceType
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.core.Square
import linmumua.chess.rule.xiangqi.MoveRejection
import linmumua.chess.rule.xiangqi.XiangqiRuleOptions
import linmumua.chess.rule.xiangqi.XiangqiRules
import org.junit.jupiter.api.Test

/**
 * 对局流程测试。
 *
 * 重点是"轮次与归属不能被绕过"以及"终局判定要落到正确的一方"。
 * 这些判错了会直接毁掉一局棋，比渲染错位严重得多。
 *
 * 另一组重点是**入座与开局的分界**：棋子只在开局那一刻铺到棋盘上，
 * 所以"坐满还没开局"必须是一个稳定可辨的状态。这条判错的表现是空棋盘
 * 凭空长出棋子，或者反过来，在下的棋局重启后变成空盘。
 */
class ChessGameTest {

    private val red = UUID.randomUUID()
    private val black = UUID.randomUUID()
    private val stranger = UUID.randomUUID()

    private fun newGame() = ChessGame(XiangqiRules())

    /** 坐满两个座位 */
    private fun seatedGame(): ChessGame = newGame().apply {
        join(red, Side.FIRST)
        join(black, Side.SECOND)
    }

    /**
     * 坐满两个座位并显式开局。
     *
     * 入座与开局拆成两步之后，这个辅助方法必须补上 [ChessGame.start] ——
     * 只 join 出来的对局停在 WAITING，任何走棋都会被拒。
     */
    private fun startedGame(): ChessGame = seatedGame().apply { start(NOW) }

    /**
     * 坐满不等于开局。
     *
     * 行为变更（原先坐满即自动开局）：棋子是在开局那一刻才铺到棋盘上的，
     * 所以"最后一个人刚坐下"不能直接进 PLAYING —— 否则玩家还在调整视角，
     * 棋子已经摆好、计时也开始跑了。坐满只把结果从 WAITING 变成 SEAT_FULL，
     * 表示"可以按开始了"。
     */
    @Test
    fun `坐满后仍在等开局而不是自动开始`() {
        val game = newGame()
        assertEquals(JoinResult.WAITING, game.join(red, Side.FIRST))
        assertEquals(GamePhase.WAITING, game.phase)

        assertEquals(JoinResult.SEAT_FULL, game.join(black, Side.SECOND))
        assertEquals(GamePhase.WAITING, game.phase, "坐满不能自己进 PLAYING")
        assertTrue(!game.hasStarted(), "还没开局，棋盘上不该有棋子")
    }

    /**
     * 一个人开不了局。
     *
     * 只坐一个人就能开局的话，这个人可以自己跟自己下完整局棋。
     */
    @Test
    fun `座位没坐满时开不了局`() {
        val game = newGame()
        game.join(red, Side.FIRST)

        assertEquals(StartResult.SEAT_NOT_FULL, game.start(NOW))
        assertEquals(GamePhase.WAITING, game.phase)
    }

    /**
     * 坐满后点开始才真正进入对局。
     *
     * 同时校验 lastMoveAt 被设上：开局是超时计时的起点，漏了这一步会让
     * 第一手永远不判超时（[isTimedOut] 遇到 lastMoveAt == 0 直接返回 false）。
     */
    @Test
    fun `满座后开局把对局推进到进行中`() {
        val game = seatedGame()

        assertEquals(StartResult.STARTED, game.start(NOW))
        assertEquals(GamePhase.PLAYING, game.phase)
        assertTrue(game.hasStarted(), "开局后棋盘上该有棋子")
        assertEquals(NOW, game.lastMoveAt, "开局要起算超时，否则第一手永不超时")
    }

    /**
     * 重复点开始要被拒。
     *
     * 这条挡的是"棋局被重置"：开局成功会按初始局面铺满棋子，若下到中盘
     * 再点一次开始还能通过，盘面会被重新摆回开局，走了一半的棋直接没了。
     */
    @Test
    fun `已经在下的局不能再开一次`() {
        val game = startedGame()
        game.move(red, Square(1, 9), Square(2, 7), NOW)

        assertEquals(StartResult.ALREADY_PLAYING, game.start(NOW + 1))
        assertEquals(1, game.moveCount, "重复开局不能清掉已经走过的棋")
        assertEquals(NOW, game.lastMoveAt, "被拒的开局不该刷新超时计时")
    }

    /** 终局后不能重新开局，收棋盘再放才是新的一局 */
    @Test
    fun `终局后不能重新开局`() {
        val game = startedGame()
        game.resign(red)

        assertEquals(StartResult.GAME_FINISHED, game.start(NOW))
        assertEquals(GamePhase.FINISHED, game.phase)
    }

    /**
     * "棋盘上该不该有棋子"只由这一个判断回答。
     *
     * 放置、存档恢复、区块重载三条路径都用它。WAITING 是空盘；
     * FINISHED 必须算已开过 —— 终局后棋盘要留着让人复盘，
     * 若这里返回 false，重启或走远回来后终局盘面会整副消失。
     */
    @Test
    fun `只有开过的局才认为棋盘上有棋子`() {
        assertTrue(!newGame().hasStarted(), "空棋盘是空盘")
        assertTrue(!seatedGame().hasStarted(), "坐满但没开局仍是空盘")
        assertTrue(startedGame().hasStarted(), "在下的局有棋子")

        val finished = startedGame().apply { resign(red) }
        assertTrue(finished.hasStarted(), "终局盘面要留着复盘，不能算空盘")
    }

    /**
     * 存档恢复照抄阶段，不走开局流程。
     *
     * 恢复出来的 WAITING 棋盘必须仍是空盘：存档里记的是"这副棋盘没人开始"，
     * 重启后凭空摆出一副棋子等于替玩家开了局。PLAYING 与 FINISHED 反之，
     * 局面得铺回去，否则玩家重连后看到的是空棋盘。
     */
    @Test
    fun `恢复存档时按阶段决定棋盘上有没有棋子`() {
        val waiting = newGame().apply { restoreSeats(red, black, GamePhase.WAITING) }
        assertTrue(!waiting.hasStarted(), "存档里 WAITING 的棋盘恢复后仍是空盘")

        val playing = newGame().apply { restoreSeats(red, black, GamePhase.PLAYING) }
        assertTrue(playing.hasStarted(), "存档里在下的棋盘要把局面铺回去")

        val finished = newGame().apply { restoreSeats(red, black, GamePhase.FINISHED) }
        assertTrue(finished.hasStarted(), "终局棋盘恢复后要能复盘")
    }

    /**
     * 恢复成 WAITING 的满座棋盘仍然可以正常开局。
     *
     * [restoreSeats] 是直接写 phase 的，不经过 join，所以要确认它写出来的
     * 状态能被 [ChessGame.start] 正常接手 —— 否则重启后那副棋盘再也开不了局。
     */
    @Test
    fun `恢复成等待中的满座棋盘还能开局`() {
        val game = newGame().apply { restoreSeats(red, black, GamePhase.WAITING) }

        assertEquals(StartResult.STARTED, game.start(NOW))
        assertEquals(GamePhase.PLAYING, game.phase)
    }

    /**
     * 等开局阶段掉线要腾出座位，否则会开出一局没人能走的死棋。
     *
     * 这条守的是**死局链路**，不是"leave 之后字段变成 null"。完整链路：
     * A 入座 → A 掉线 → B 入座 → 满座 → 有人点开始 → 轮到 A 时无人能走。
     * 开局改成"入座 → 点开始"两步之后，"满座但没开局"从瞬态变成了会一直等下去的
     * 持久态，掉线者的座位有充足时间被带进一局死局；而默认不判超时、PLAYING 阶段
     * 又拒绝收棋盘，那副棋盘只剩另一方认输能解开。
     *
     * 所以断言落在"B 一个人坐着时 start 必须被拒"：这是阻断链路的那一步。
     */
    @Test
    fun `等开局时掉线腾出座位使后来者无法独自开局`() {
        val game = newGame()
        // A 入座，此时只有一个人，开不了局
        assertEquals(JoinResult.WAITING, game.join(red, Side.FIRST))

        // A 掉线：SessionListener.onQuit 在 WAITING 阶段调 leave 腾座位
        assertTrue(game.leave(red), "已入座的玩家离座要返回成功")
        assertNull(game.playerOf(Side.FIRST), "掉线者的座位必须空出来")
        assertTrue(!game.isSeatFull(), "只剩空位，不能算满座")

        // B 后来入座，坐的是哪一边不重要，重要的是场上只有一个人
        assertEquals(JoinResult.WAITING, game.join(black, Side.SECOND))
        assertTrue(!game.isSeatFull(), "A 已掉线，B 一个人不构成满座")

        // 关键断言：这一步若通过，棋子会铺满而轮到空座位一方时无人能走
        assertEquals(StartResult.SEAT_NOT_FULL, game.start(NOW))
        assertEquals(GamePhase.WAITING, game.phase)
        assertTrue(!game.hasStarted(), "开局被拒，棋盘必须仍是空盘")
    }

    /**
     * 座位腾出来后允许别人补进来接着开局。
     *
     * 腾座位不能只是"清掉字段"，那个位置得真的能被再次坐下 —— 否则棋盘换了个方式卡死：
     * 座位看着是空的，却谁也补不进去，同样开不了局。
     */
    @Test
    fun `掉线腾出的座位可以被别人补上并开局`() {
        val game = seatedGame()
        assertTrue(game.isSeatFull())

        // 红方掉线，座位空出
        game.leave(red)
        assertEquals(listOf(Side.FIRST), game.availableSides(), "空出来的必须是红座")

        // 路人补进红座，重新满座
        assertEquals(JoinResult.SEAT_FULL, game.join(stranger, Side.FIRST))
        assertEquals(StartResult.STARTED, game.start(NOW), "补满之后要能正常开局")
        assertEquals(stranger, game.playerOf(Side.FIRST))
    }

    /**
     * 等开局阶段离座不会把空盘变成"开过的局"。
     *
     * [ChessGame.leave] 里那条 PLAYING → WAITING 分支是个陷阱：调用方漏了 finishWith
     * 就会造出"phase 是 WAITING 但盘上有子"。WAITING 阶段调用不碰那条分支，
     * 这里把它钉住 —— phase 必须还是 WAITING，[hasStarted] 必须还说没开过，
     * 两者一致才不会在区块重载后让棋子凭空消失。
     */
    @Test
    fun `等开局时离座不改变对局阶段`() {
        val game = seatedGame()
        assertEquals(GamePhase.WAITING, game.phase)

        game.leave(red)

        assertEquals(GamePhase.WAITING, game.phase, "本来就在等开局，离座不该改阶段")
        assertTrue(!game.hasStarted(), "空盘离座后仍是空盘，不能被当成开过的局")
        assertEquals(0, game.moveCount)
    }

    /**
     * 观战者掉线只影响观战名单，不许动座位。
     *
     * 掉线处理要按"坐着的人 / 观战者"分流，分流的依据是 [ChessGame.sideOf] 返回不返回 null。
     * 分错的后果是双向的：把观战者当成坐着的人会凭空腾掉别人的座位，
     * 把坐着的人当成观战者则座位永远不腾（就是本次修的那个死局）。
     * 规则层这一侧能测的是前半句 —— 局外人调 leave 必须什么都不改。
     */
    @Test
    fun `局外人离座不影响双方座位`() {
        val game = seatedGame()

        // 观战者与路人在 ChessGame 眼里是同一类：sideOf 返回 null
        assertNull(game.sideOf(stranger), "观战者不占阵营")
        assertTrue(!game.leave(stranger), "局外人离座要返回失败")

        assertEquals(red, game.playerOf(Side.FIRST), "红座不许被动")
        assertEquals(black, game.playerOf(Side.SECOND), "黑座不许被动")
        assertTrue(game.isSeatFull(), "观战者掉线之后仍是满座")
        assertEquals(StartResult.STARTED, game.start(NOW), "满座应能照常开局")
    }

    /** 座位被占时不能再坐 */
    @Test
    fun `座位被占后不能重复入座`() {
        val game = newGame()
        game.join(red, Side.FIRST)
        assertEquals(JoinResult.SEAT_TAKEN, game.join(stranger, Side.FIRST))
    }

    /** 已经在局中的人不能再坐另一边 */
    @Test
    fun `已入座的人不能再坐另一边`() {
        val game = newGame()
        game.join(red, Side.FIRST)
        assertEquals(JoinResult.ALREADY_JOINED, game.join(red, Side.SECOND))
    }

    /** 随机入座第一次可以从红黑中任选，不能漏掉某一方 */
    @Test
    fun `空棋盘同时提供红黑两个座位`() {
        assertEquals(listOf(Side.FIRST, Side.SECOND), newGame().availableSides())
    }

    /** 第一人无论随机到哪边，第二人都只能补到剩下的座位 */
    @Test
    fun `只剩一个座位时只返回剩余阵营`() {
        val redOccupied = newGame().apply { join(red, Side.FIRST) }
        val blackOccupied = newGame().apply { join(black, Side.SECOND) }

        assertEquals(listOf(Side.SECOND), redOccupied.availableSides())
        assertEquals(listOf(Side.FIRST), blackOccupied.availableSides())
    }

    /** 满座后随机入口必须得到空列表，不能覆盖已有玩家 */
    @Test
    fun `坐满后没有可用阵营`() {
        assertTrue(startedGame().availableSides().isEmpty())
    }

    /**
     * 没轮到的一方不能走棋。
     *
     * 红先，所以黑方开局第一步就动子必须被拦住。
     */
    @Test
    fun `没轮到的一方走棋会被拦住`() {
        val game = startedGame()
        // 黑方的卒，黑方现在还没轮到
        val attempt = game.move(black, Square(0, 3), Square(0, 4), NOW)
        val rejected = assertIs<MoveAttempt.Rejected>(attempt)
        assertEquals(MoveRejection.WRONG_TURN, rejected.rejection)
    }

    /**
     * 不能动对方的子。
     *
     * 这条要比轮次先判：红方点黑方的子，应该说"这不是你的子"，
     * 而不是"不该你走"。
     */
    @Test
    fun `不能动对方的子`() {
        val game = startedGame()
        val attempt = game.move(red, Square(0, 3), Square(0, 4), NOW)
        val rejected = assertIs<MoveAttempt.Rejected>(attempt)
        assertEquals(MoveRejection.NOT_OWN_PIECE, rejected.rejection)
    }

    /** 不在局中的人不能走棋 */
    @Test
    fun `局外人不能走棋`() {
        val game = startedGame()
        val attempt = game.move(stranger, Square(1, 9), Square(2, 7), NOW)
        assertEquals(MoveAttempt.NotAPlayer, attempt)
    }

    /** 还没开局时不能走棋 */
    @Test
    fun `未开局时不能走棋`() {
        val game = newGame()
        game.join(red, Side.FIRST)
        val attempt = game.move(red, Square(1, 9), Square(2, 7), NOW)
        val notPlaying = assertIs<MoveAttempt.NotPlaying>(attempt)
        assertEquals(GamePhase.WAITING, notPlaying.phase)
    }

    /**
     * 走成一步后轮次要换到对方。
     *
     * 轮次不换的话一方可以连走，这是最基础的流程保证。
     */
    @Test
    fun `走子后轮次交给对方`() {
        val game = startedGame()
        assertEquals(Side.FIRST, game.sideToMove)

        val attempt = game.move(red, Square(1, 9), Square(2, 7), NOW)
        assertIs<MoveAttempt.Success>(attempt)
        assertEquals(Side.SECOND, game.sideToMove)
        assertEquals(1, game.moveCount)
    }

    /**
     * 非法着法不允许改动局面。
     *
     * 被否掉的一步如果把子挪走了，棋盘就坏了。
     */
    @Test
    fun `非法着法不改动局面`() {
        val game = startedGame()
        val before = game.board.toFen()
        // 馬跳不到的地方
        game.move(red, Square(1, 9), Square(8, 0), NOW)
        assertEquals(before, game.board.toFen())
        assertEquals(0, game.moveCount)
    }

    /**
     * 认输判负的是认输那一方。
     */
    @Test
    fun `认输后判负方是认输的人`() {
        val game = startedGame()
        assertEquals(Side.FIRST, game.resign(red))
        assertEquals(GamePhase.FINISHED, game.phase)
        val result = assertIs<GameResult.Resign>(game.result)
        assertEquals(Side.FIRST, result.loser)
        assertEquals(Side.SECOND, result.winner)
    }

    /** 终局后不能再走棋 */
    @Test
    fun `终局后不能再走棋`() {
        val game = startedGame()
        game.resign(red)
        val attempt = game.move(black, Square(0, 3), Square(0, 4), NOW)
        assertIs<MoveAttempt.NotPlaying>(attempt)
    }

    /** 未开局时认输没有意义 */
    @Test
    fun `未开局时认输无效`() {
        val game = newGame()
        game.join(red, Side.FIRST)
        assertNull(game.resign(red))
    }

    /**
     * 超时判定。
     *
     * 时间由外部传入，所以可以直接喂一个超过阈值的时刻，不需要真的等。
     */
    @Test
    fun `超过时限判超时`() {
        val game = startedGame()
        game.markStarted(NOW)
        // 限时 10 秒，喂一个 11 秒后的时刻
        assertTrue(game.isTimedOut(NOW + 11_000, timeoutSeconds = 10))
        assertTrue(!game.isTimedOut(NOW + 9_000, timeoutSeconds = 10))
    }

    /** 限时配 0 表示不限时，任何时刻都不判超时 */
    @Test
    fun `不限时时永不判超时`() {
        val game = startedGame()
        game.markStarted(NOW)
        assertTrue(!game.isTimedOut(NOW + 999_999, timeoutSeconds = 0))
    }

    /**
     * 悔棋要把局面和轮次一起退回去。
     */
    @Test
    fun `悔棋退回局面与轮次`() {
        val game = startedGame()
        val before = game.board.toFen()
        game.move(red, Square(1, 9), Square(2, 7), NOW)

        val undone = game.undoLast()
        assertTrue(undone != null)
        assertEquals(before, game.board.toFen())
        assertEquals(Side.FIRST, game.sideToMove)
        assertEquals(0, game.moveCount)
    }

    /**
     * 困毙判负，不是和棋。
     *
     * 这是象棋与国际象棋的关键差异，流程层必须把它结算成负而不是平。
     * 局面：黑將被堵死但没被将军 —— 用一个极简残局摆出来。
     */
    @Test
    fun `困毙结算成判负而不是和棋`() {
        val rules = XiangqiRules(XiangqiRuleOptions(moveLimitDrawEnabled = false))
        val board = linmumua.chess.rule.core.Board(sideToMove = Side.SECOND)
        // 局面取自规则层已验证的困毙用例（GameOutcomeTest 的 `困毙判负`）：
        // 三枚红車控住黑将的三个方向，但没有一枚叫将；红将放 file 3 避免与黑将对脸，
        // 否则黑方就变成被将军，测的就成了将死而不是困毙。
        board[Square(3, 9)] = Piece(PieceType.KING, Side.FIRST)
        board[Square(4, 0)] = Piece(PieceType.KING, Side.SECOND)
        board[Square(0, 1)] = Piece(PieceType.CHARIOT, Side.FIRST)
        board[Square(3, 8)] = Piece(PieceType.CHARIOT, Side.FIRST)
        board[Square(5, 8)] = Piece(PieceType.CHARIOT, Side.FIRST)

        // 先确认这确实是困毙而不是将死，否则下面的断言测不到想测的东西
        assertTrue(!rules.isInCheck(board, Side.SECOND), "黑将没被将军，这才是困毙")
        assertTrue(rules.legalMoves(board, Side.SECOND).isEmpty(), "黑方应无子可动")

        val outcome = rules.outcome(board, Side.SECOND)
        val stalemate = assertIs<GameResult.Stalemate>(GameResult.fromOutcome(outcome))
        // 象棋的困毙判负，不是和棋 —— 这是与国际象棋的关键差异
        assertEquals(Side.SECOND, stalemate.loser)
        assertEquals(Side.FIRST, stalemate.winner)
    }

    /** 将死结算成负方是被将死的一方 */
    @Test
    fun `将死结算成被将死方判负`() {
        val outcome = linmumua.chess.rule.xiangqi.GameOutcome.Checkmate(Side.SECOND)
        val result = assertIs<GameResult.Checkmate>(GameResult.fromOutcome(outcome))
        assertEquals(Side.SECOND, result.loser)
        assertEquals(Side.FIRST, result.winner)
    }

    /** 未终局的状态不该被翻成结果 */
    @Test
    fun `未终局不产生对局结果`() {
        assertNull(GameResult.fromOutcome(linmumua.chess.rule.xiangqi.GameOutcome.Ongoing))
    }

    private companion object {
        /** 固定时间戳，测试不依赖真实时钟 */
        const val NOW = 1_000_000L
    }
}
