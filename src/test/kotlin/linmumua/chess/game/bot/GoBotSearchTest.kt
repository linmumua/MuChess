package linmumua.chess.game.bot

import kotlin.random.Random
import linmumua.chess.rule.core.Board
import linmumua.chess.rule.core.Move
import linmumua.chess.rule.core.Piece
import linmumua.chess.rule.core.PieceType
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.core.Square
import linmumua.chess.rule.go.GoRules
import linmumua.chess.rule.xiangqi.MoveRejection
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 围棋机器人经 [BotBrain] 的端到端行为。
 *
 * 上面那些单元测试管"各个零件对不对"，这里管"装起来会不会下棋"。
 * 三条硬要求：选出的手必须合法、必须在时间预算内返回、收官后必须停手。
 *
 * 第三条是"会终局"这个承诺的唯一证据。机器人不虚着的话，围棋永远不会结束
 * （outcome 只认双方连续虚着），玩家会被迫一直下到无处可下。
 */
class GoBotSearchTest {

    private fun Board.put(file: Int, rank: Int, side: Side) {
        this[Square(file, rank)] = Piece(PieceType.STONE, side)
    }

    /**
     * 默认预算的选步。
     *
     * 会先把棋盘轮次设成 [side]：测试用 [put] 直接摆子不会推进轮次，而
     * `rejectionOf` 查到 `mover != board.sideToMove` 会把所有候选拒成 `WRONG_TURN`，
     * 于是搜索只剩虚着可选，测试静默空过。
     *
     * 生产代码不需要这一步 —— `MoveExecutor.moveAsBot` 在 `board.copy()` 之前
     * 就校验过 `game.sideToMove != side`。
     */
    private fun choose(rules: GoRules, board: Board, side: Side, seed: Int = 1): Move? {
        board.sideToMove = side
        return BotBrain.chooseMove(rules, board, side, BotOptions.DEFAULT, Random(seed))
    }

    @Test
    @DisplayName("空盘能选出合法的一手")
    fun `opens with a legal move`() {
        val rules = GoRules()
        val board = rules.initialBoard()

        val move = choose(rules, board, Side.FIRST)

        assertNotNull(move, "空盘必须能选出一手")
        assertFalse(move.pass, "开局不该虚着")
        assertNull(
            rules.rejectionOf(board, move.from, move.to, Side.FIRST),
            "选出的手必须经得起规则层复核",
        )
    }

    @Test
    @DisplayName("中盘选出的手一律合法，且永不自杀")
    fun `never picks an illegal or suicidal move`() {
        val rules = GoRules()
        val board = rules.initialBoard()
        // 铺一个有攻防的中盘
        var n = 0
        for (rank in 4..9) {
            for (file in 4..11) {
                board.put(file, rank, if (n % 3 == 0) Side.SECOND else Side.FIRST)
                n++
            }
        }

        // 多个随机种子各选一次，避免只测到一条分支
        var placements = 0
        repeat(5) { seed ->
            val move = choose(rules, board, Side.SECOND, seed)
            assertNotNull(move, "中盘必须能选出一手（种子 $seed）")
            if (move.pass) {
                return@repeat
            }
            placements++
            val rejection = rules.rejectionOf(board, move.from, move.to, Side.SECOND)
            assertNull(
                rejection,
                "选出的手被规则层拒了：${move.to}，原因 $rejection（种子 $seed）",
            )
            assertTrue(
                rejection != MoveRejection.GO_SUICIDE,
                "机器人永远不该选自杀手",
            )
        }
        // 这个中盘远没到收官，五次至少应当有一次是落子而不是虚着。
        // 全是虚着说明候选被拒空了（轮次没设对之类），上面的断言等于没跑
        assertTrue(placements > 0, "中盘局面下机器人应当落子，不该五次全虚着")
    }

    @Test
    @DisplayName("官收完就虚着，不再往死地里插子 —— 会终局的唯一证据")
    fun `passes once everything is settled`() {
        val rules = GoRules()
        val board = rules.initialBoard()

        // 造一个"官已收完"的局面：两道墙**贴在一起**（file 8 黑、file 9 白），
        // 中间没有公气。于是 files 0-7 只贴黑、files 10-18 只贴白，
        // 全盘每个空点都在单色围死的区域里，没有任何一手有价值。
        for (rank in 0 until 19) {
            board.put(8, rank, Side.FIRST)
            board.put(9, rank, Side.SECOND)
        }

        val move = choose(rules, board, Side.FIRST)

        assertNotNull(move, "必须给出一手（虚着也算）")
        assertTrue(
            move.pass,
            "官收完、双方领地都已围死时机器人必须虚着，实际选了 ${move.to}。" +
                "不虚着的后果是对局永不终局 —— 围棋只认双方连续虚着",
        )
    }

    @Test
    @DisplayName("还有公气时先收官再虚着，不是一到收官就停手")
    fun `fills dame before passing`() {
        val rules = GoRules()
        val board = rules.initialBoard()

        // 两道墙之间留 file 9 一条公气：19 个点，每个在数子法下值 +1
        for (rank in 0 until 19) {
            board.put(8, rank, Side.FIRST)
            board.put(10, rank, Side.SECOND)
        }

        val move = choose(rules, board, Side.FIRST)

        assertNotNull(move)
        assertFalse(
            move.pass,
            "公气还没收完就虚着是白扔分：数子法下每个公气点值 1 子",
        )
        assertEquals(
            9,
            move.to.file,
            "该下的是公气那一路（file 9），实际下在 ${move.to}",
        )
    }

    @Test
    @DisplayName("有便宜可占时不虚着")
    fun `does not pass when there is something to gain`() {
        val rules = GoRules()
        val board = rules.initialBoard()

        // 白单子在角上只剩一口气，黑补一手就能提
        board.put(0, 0, Side.SECOND)
        board.put(1, 0, Side.FIRST)
        // 双方各有些子，避免退化成"一方独占全盘"
        board.put(9, 9, Side.SECOND)
        board.put(10, 10, Side.FIRST)
        board.put(9, 11, Side.SECOND)

        val move = choose(rules, board, Side.FIRST)

        assertNotNull(move)
        assertFalse(
            move.pass,
            "能提子时虚着是明显的亏，实际虚着了说明估值把提子算成了负收益",
        )
    }

    @Test
    @DisplayName("能提一大块时直接提（必走手不经过搜索）")
    fun `takes a big capture immediately`() {
        val rules = GoRules()
        val board = rules.initialBoard()

        // 白三子，黑围住除 (4,6) 外所有点
        board.put(5, 5, Side.SECOND)
        board.put(5, 6, Side.SECOND)
        board.put(5, 7, Side.SECOND)
        board.put(5, 4, Side.FIRST)
        board.put(5, 8, Side.FIRST)
        board.put(6, 5, Side.FIRST)
        board.put(6, 6, Side.FIRST)
        board.put(6, 7, Side.FIRST)
        board.put(4, 5, Side.FIRST)
        board.put(4, 7, Side.FIRST)

        val move = choose(rules, board, Side.FIRST)

        assertNotNull(move)
        assertEquals(
            Square(4, 6),
            move.to,
            "能一手提 3 子必须提，这一手不该被搜索或候选裁剪盖掉",
        )
    }

    @Test
    @DisplayName("默认 200ms 预算内返回，不会因为候选没裁而卡死")
    fun `returns within the default time budget`() {
        val rules = GoRules()
        val board = rules.initialBoard()
        // 铺 60 子的中盘，这是实测过 legalMoves 约 1ms、合法着法 301 个的规模
        var n = 0
        for (rank in 3..8) {
            for (file in 3..12) {
                board.put(file, rank, if (n % 2 == 0) Side.FIRST else Side.SECOND)
                n++
            }
        }

        // 预热一次，避免把 JIT 编译时间算进来（实测首次 26ms、稳定后 1ms）
        choose(rules, board, Side.FIRST)

        val start = System.currentTimeMillis()
        val move = choose(rules, board, Side.FIRST)
        val elapsed = System.currentTimeMillis() - start

        assertNotNull(move)
        // 阈值给 3 倍余量：搜索自己有 200ms 的软超时，但那只在层与层之间检查，
        // 单层内的候选要全部算完。留余量是为了不让测试在慢机器上假失败。
        assertTrue(
            elapsed < 600,
            "选一手耗时 ${elapsed}ms，超出预期。19 路不裁剪候选要几十秒，" +
                "这条测试就是防候选裁剪被改坏",
        )
    }

    @Test
    @DisplayName("搜索不改动传入的棋盘")
    fun `search leaves the board untouched`() {
        val rules = GoRules()
        val board = rules.initialBoard()
        board.put(3, 3, Side.FIRST)
        board.put(15, 15, Side.SECOND)
        board.put(9, 9, Side.FIRST)

        // 轮次要在取快照之前设好：toFen 把轮次也编进去了（末尾 r/b），
        // 而 choose 会设轮次，顺序错了会拿"轮次不同"当成"局面被改"
        board.sideToMove = Side.SECOND
        val before = board.toFen()
        val move = choose(rules, board, Side.SECOND)

        assertNotNull(move)
        assertFalse(move.pass, "这个局面还有便宜可占，不该虚着 —— 否则等于没搜")
        assertEquals(
            before,
            board.toFen(),
            "搜索用试走再撤销，结束时局面必须与传入时完全一致",
        )
        assertEquals(
            Side.SECOND,
            board.sideToMove,
            "轮次也要复原：apply 会翻转它，undo 必须翻回来",
        )
    }

    @Test
    @DisplayName("虚着计数也要复原：搜索展开虚着分支后计数不能留在实例上")
    fun `search restores the pass counter`() {
        val rules = GoRules()
        val board = rules.initialBoard()
        // 官已收完的局面，搜索一定会展开虚着分支
        for (rank in 0 until 19) {
            board.put(8, rank, Side.FIRST)
            board.put(9, rank, Side.SECOND)
        }

        val before = rules.passCount
        choose(rules, board, Side.FIRST)

        assertEquals(
            before,
            rules.passCount,
            "搜索试走虚着后必须撤销干净。留在实例上会让下一次 outcome() " +
                "误判终局 —— 这正是 forSearch 之外还必须 apply/undo 配对的原因",
        )
    }

    @Test
    @DisplayName("机器人的虚着单看坐标与角上落子无法区分 —— 必须按 pass 标志分流")
    fun `a bot pass looks exactly like a corner placement`() {
        val rules = GoRules()
        val board = rules.initialBoard()
        for (rank in 0 until 19) {
            board.put(8, rank, Side.FIRST)
            board.put(9, rank, Side.SECOND)
        }

        val chosen = choose(rules, board, Side.FIRST)
        assertNotNull(chosen)
        assertTrue(chosen.pass, "前提：这个局面机器人应当虚着")

        // 虚着的起终点都是 (0,0)，和"在左下角落一子"的坐标一模一样
        assertEquals(Square(0, 0), chosen.from)
        assertEquals(Square(0, 0), chosen.to)

        // 后果：把它按坐标交给落子入口，重建出来的是一手**真落子**。
        // 这就是 MoveExecutor.applyBotMove 必须先看 pass 标志再分流的原因 ——
        // 少了那条分流，机器人本想虚着，实际会在左下角放一颗子，
        // 而围棋只认双方连续虚着终局，于是对局永远结束不了。
        val misrouted = rules.moveAt(board, chosen.from, chosen.to)
        assertNotNull(misrouted, "坐标合法，落子入口会照单接受")
        assertFalse(
            misrouted.pass,
            "按坐标重建出来的是真落子而不是虚着，所以分流只能靠 pass 标志",
        )
    }

    @Test
    @DisplayName("双方机器人能把一局下完并终局")
    fun `two bots can finish a game`() {
        val rules = GoRules()
        val board = rules.initialBoard()
        // 从一个官已收完的局面起手，否则跑完整局太慢。
        // 两道墙贴在一起，没有公气可收，双方应当立刻连续虚着
        for (rank in 0 until 19) {
            board.put(8, rank, Side.FIRST)
            board.put(9, rank, Side.SECOND)
        }

        var side = Side.FIRST
        var finished = false
        // 最多走 10 手：正常应该两手之内双方都虚着
        repeat(10) {
            val move = choose(rules, board, side) ?: return@repeat
            rules.apply(board, move)
            if (rules.outcome(board, side.opposite).isFinished) {
                finished = true
                return@repeat
            }
            side = side.opposite
        }

        assertTrue(
            finished,
            "两个机器人在收官局面上必须能连续虚着终局。走不完说明它们在自填己空，" +
                "对局会一直拖到无处可下",
        )
    }
}
