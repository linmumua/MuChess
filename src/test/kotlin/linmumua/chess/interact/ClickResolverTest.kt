package linmumua.chess.interact

import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import linmumua.chess.rule.core.Board
import linmumua.chess.rule.core.Piece
import linmumua.chess.rule.core.PieceType
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.core.Square
import linmumua.chess.rule.xiangqi.XiangqiRules
import org.junit.jupiter.api.Test

/**
 * 右键交互状态机测试。
 *
 * 覆盖需求里的完整交互闭环：选中 → 高亮 → 落子/取消/换选中/非法反馈。
 * 这些分支如果判错，玩家的每一次点击都会得到错误反应，
 * 所以每一条都独立立一个用例，而不是只测"函数不抛异常"。
 */
class ClickResolverTest {

    private val rules = XiangqiRules()

    /** 开局局面，红先 */
    private fun initial() = rules.initialBoard()

    /** 开局时红方左边那只馬所在点 */
    private val redHorse = Square(1, 9)

    /** 开局时红方的車 */
    private val redChariot = Square(0, 9)

    /** 开局时黑方的卒 */
    private val blackPawn = Square(0, 3)

    /**
     * 第一次右键自己的子 = 选中。
     *
     * 这是交互的起点，选不中后面什么都做不了。
     */
    @Test
    fun `没选中时点自己的子会选中`() {
        val action = ClickResolver.resolve(initial(), redHorse, selection = null, mover = Side.FIRST)
        val select = assertIs<ClickAction.Select>(action)
        assertEquals(redHorse, select.square)
    }

    /**
     * 再点同一枚 = 取消选中，棋子落回去。
     *
     * 玩家点错子的时候要能退出来，不能被卡在选中态里。
     */
    @Test
    fun `再点同一枚子会取消选中`() {
        val board = initial()
        val state = SelectionState(redHorse, rules.legalMovesFrom(board, redHorse))

        val action = ClickResolver.resolve(board, redHorse, state, Side.FIRST)
        val deselect = assertIs<ClickAction.Deselect>(action)
        assertEquals(redHorse, deselect.square)
    }

    /**
     * 选中一枚后点自己另一枚 = 换选中，不是报错。
     *
     * 如果这里判成"不能吃自己的子"，玩家换子就得先取消一次，很难用。
     */
    @Test
    fun `选中后点自己另一枚子会换选中`() {
        val board = initial()
        val state = SelectionState(redHorse, rules.legalMovesFrom(board, redHorse))

        val action = ClickResolver.resolve(board, redChariot, state, Side.FIRST)
        val switch = assertIs<ClickAction.Switch>(action)
        assertEquals(redHorse, switch.from)
        assertEquals(redChariot, switch.to)
    }

    /**
     * 点高亮过的合法落点 = 走子。
     *
     * 开局红馬可以跳到 (0,7) 或 (2,7)，取生成结果里的第一个落点。
     */
    @Test
    fun `点合法落点会走子`() {
        val board = initial()
        val moves = rules.legalMovesFrom(board, redHorse)
        val state = SelectionState(redHorse, moves)
        val target = moves.first().to

        val action = ClickResolver.resolve(board, target, state, Side.FIRST)
        val move = assertIs<ClickAction.Move>(action)
        assertEquals(redHorse, move.from)
        assertEquals(target, move.to)
        // 开局馬跳到空点，不是吃子
        assertEquals(false, move.isCapture)
    }

    /**
     * 点没高亮的点 = 要给具体原因，不能静默忽略。
     *
     * 这是需求强调的一条：玩家点了走不了的地方，必须知道为什么。
     */
    @Test
    fun `点非法落点会走到非法反馈分支`() {
        val board = initial()
        val state = SelectionState(redHorse, rules.legalMovesFrom(board, redHorse))
        // 馬绝对跳不到的地方
        val illegal = Square(8, 0)

        val action = ClickResolver.resolve(board, illegal, state, Side.FIRST)
        val illegalAction = assertIs<ClickAction.IllegalTarget>(action)
        assertEquals(redHorse, illegalAction.from)
        assertEquals(illegal, illegalAction.to)
    }

    /**
     * 没选中时点对方的子 = 提示这不是你的子。
     */
    @Test
    fun `没选中时点对方的子会提示不是你的子`() {
        val action = ClickResolver.resolve(initial(), blackPawn, selection = null, mover = Side.FIRST)
        assertIs<ClickAction.NotOwnPiece>(action)
    }

    /**
     * 没选中时点空处 = 什么都不做。
     *
     * 不发提示是有意的：棋盘上大片是空点，每次点空都刷一条消息会把聊天框冲掉。
     */
    @Test
    fun `没选中时点空处不做任何事`() {
        val empty = Square(4, 5)
        val action = ClickResolver.resolve(initial(), empty, selection = null, mover = Side.FIRST)
        assertEquals(ClickAction.Ignore, action)
    }

    /**
     * 观战者点击不产生动作。
     *
     * 观战者的 mover 是 null，这是"不在局中"的唯一判据。
     *
     * 这里点的是行棋方（红）的子，与下面那条"观战者不会收到轮次提示"配对：
     * 那条点的是等待方（黑）的子，用来钉住判定顺序 —— null 跟任何 sideToMove 都不相等，
     * 若轮次判定排在观战者判定之前，观战者会收到"还没轮到你"，而他根本不在局中。
     */
    @Test
    fun `观战者点击不产生动作`() {
        val action = ClickResolver.resolve(initial(), redHorse, selection = null, mover = null)
        assertEquals(ClickAction.Spectate, action)
    }

    /**
     * 选中态缓存的落点要能区分吃子与空点。
     *
     * 空点要生成白圈，可吃子要给目标棋子红色描边；分错会让世界提示与实际着法不一致。
     */
    @Test
    fun `选中态能区分白圈空点与红框吃子点`() {
        val board = Board(sideToMove = Side.FIRST)
        board[Square(4, 9)] = Piece(PieceType.KING, Side.FIRST)
        board[Square(3, 0)] = Piece(PieceType.KING, Side.SECOND)
        val chariot = Square(0, 5)
        val capture = Square(0, 2)
        board[chariot] = Piece(PieceType.CHARIOT, Side.FIRST)
        board[capture] = Piece(PieceType.PAWN, Side.SECOND)
        val before = board.toFen()

        val state = SelectionState(chariot, rules.legalMovesFrom(board, chariot))

        assertTrue(state.emptyDestinations.isNotEmpty(), "車沿途的空交叉点应生成白圈")
        assertEquals(listOf(capture), state.captureDestinations, "能吃到的黑卒应成为唯一红框目标")
        assertTrue(capture !in state.emptyDestinations, "吃子点不能再叠一个白圈")
        // 分类合法着法不该改动原局面
        assertEquals(before, board.toFen())
    }

    /**
     * 等待方点自己的子不能进选中态。
     *
     * 这条为什么重要：轮次校验如果只在走子那一步做，等待方右键自己的子会真的选中 ——
     * 棋子浮起放大、合法落点白圈全亮出来，等于让对手在别人回合里预演走法，
     * 还会跟行棋方的选中态抢同一副棋盘的高亮（一副棋盘同时只保留一组可视选中态）。
     * 所以门必须在选中之前。
     */
    @Test
    fun `不是自己回合时点自己的子不能选中`() {
        // 开局红先，此时黑方点自己的卒
        val action = ClickResolver.resolve(initial(), blackPawn, selection = null, mover = Side.SECOND)
        assertIs<ClickAction.NotYourTurn>(action)
    }

    /**
     * 等待方点对方的子同样得到轮次拒绝，而不是"不是你的子"。
     *
     * 两条提示的语义不同：NotOwnPiece 不出声（棋子颜色自明），NotYourTurn 要给动作栏提示。
     * 轮次这一层在前，先告诉玩家"还没到你"，比"这不是你的子"更贴近真实原因。
     */
    @Test
    fun `不是自己回合时点对方的子给轮次提示`() {
        val action = ClickResolver.resolve(initial(), redHorse, selection = null, mover = Side.SECOND)
        assertIs<ClickAction.NotYourTurn>(action)
    }

    /**
     * 轮次不符时点空处也不再是静默忽略。
     *
     * 有意为之：等待方点哪里都该得到同一个反馈，否则"点子有提示、点空没提示"会让人
     * 以为是棋子的问题而不是回合的问题。动作栏提示本身不刷屏，代价可以接受。
     */
    @Test
    fun `不是自己回合时点空处给轮次提示`() {
        val empty = Square(4, 5)
        val action = ClickResolver.resolve(initial(), empty, selection = null, mover = Side.SECOND)
        assertIs<ClickAction.NotYourTurn>(action)
    }

    /**
     * 轮次切换后残留的选中态必须能点掉。
     *
     * 场景：红方选中一枚子后没走，改用命令认输/悔棋导致轮次变了，或对手先走了一步。
     * 这时若一律返回 NotYourTurn，那枚浮起的子就卡在选中态里退不出来。
     * 所以"点自己已选中的那枚子"这一个动作要放行为 Deselect。
     */
    @Test
    fun `轮次不符时仍能取消已有的选中态`() {
        val board = initial()
        val state = SelectionState(redHorse, rules.legalMovesFrom(board, redHorse))

        // 局面是红先，但点击者按黑方身份来点 —— 模拟轮次已切走而选中态还在
        val action = ClickResolver.resolve(board, redHorse, state, Side.SECOND)

        val deselect = assertIs<ClickAction.Deselect>(action)
        assertEquals(redHorse, deselect.square)
    }

    /**
     * 观战者判定要排在轮次判定之前。
     *
     * 观战者的 mover 是 null，null 跟任何 sideToMove 都不相等。若两个判定顺序颠倒，
     * 观战者会收到"还没轮到你"的提示 —— 而他根本不在局中，这话是错的。
     */
    @Test
    fun `观战者不会收到轮次提示`() {
        val action = ClickResolver.resolve(initial(), blackPawn, selection = null, mover = null)
        assertEquals(ClickAction.Spectate, action)
    }
}

/** 选中缓存清理必须按棋盘隔离，否则拆一副棋盘会清掉另一桌正在看的落点。 */
class SelectionRegistryTest {

    @Test
    fun `清一副棋盘只移除该棋盘的全部选中态`() {
        val registry = SelectionRegistry()
        val boardA = UUID.randomUUID()
        val boardB = UUID.randomUUID()
        val playerA = UUID.randomUUID()
        val playerB = UUID.randomUUID()
        val playerC = UUID.randomUUID()
        val stateA = SelectionState(Square(0, 0), emptyList())
        val stateB = SelectionState(Square(1, 1), emptyList())
        val stateC = SelectionState(Square(2, 2), emptyList())

        registry.select(playerA, boardA, stateA)
        registry.select(playerB, boardA, stateB)
        registry.select(playerC, boardB, stateC)

        val cleared = registry.clearBoard(boardA)

        assertEquals(setOf(playerA, playerB), cleared.keys)
        assertEquals(null, registry.get(playerA, boardA))
        assertEquals(null, registry.get(playerB, boardA))
        assertEquals(stateC, registry.get(playerC, boardB), "另一副棋盘的白圈缓存不能被误删")
        assertEquals(1, registry.activeCount())
    }
}
