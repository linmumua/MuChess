package linmumua.chess.game.bot

import linmumua.chess.rule.core.Board
import linmumua.chess.rule.core.Piece
import linmumua.chess.rule.core.PieceType
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.core.Square
import linmumua.chess.rule.go.GoRules
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 围棋估值器测试。
 *
 * 重点不在"分数是多少"，而在几条会让机器人行为明显错掉的性质：
 * - 自填己空必须是负收益，否则机器人永不虚着、对局永不终局
 * - 提子必须仍是净正，否则机器人不敢下有价值的手
 * - 被叫吃必须体现为负分，否则它对"下一手丢 10 子"完全无感
 */
class GoEvaluatorTest {

    private fun rules() = GoRules()

    private fun evaluator(rules: GoRules, maxCandidates: Int = 12) =
        GoEvaluator(rules, BotOptions.DEFAULT, maxCandidates)

    private fun Board.put(file: Int, rank: Int, side: Side) {
        this[Square(file, rank)] = Piece(PieceType.STONE, side)
    }

    /**
     * 把轮次设成 [side]。
     *
     * 直接 [put] 摆子不会推进轮次，而 `rejectionOf` 第一件事就是查
     * `mover != board.sideToMove` 并返回 `WRONG_TURN`。不设的话查非先行方的候选
     * 会全被拒成空列表，断言循环遍历空列表 —— 测试静默空过。
     *
     * 生产代码不存在这个问题：`MoveExecutor.moveAsBot` 在 `board.copy()` 之前
     * 就校验过 `game.sideToMove != side`，快照的轮次必然与搜索方一致。
     */
    private fun Board.turnOf(side: Side): Board {
        sideToMove = side
        return this
    }

    // ---- 估值公式的三条性质 ----

    @Test
    @DisplayName("子数与围空等权：把子填进己方空里，数子分不变")
    fun `stones and territory weigh the same`() {
        val rules = rules()
        val board = rules.initialBoard()
        // 黑在角上围出 (0,0) 一口空
        board.put(1, 0, Side.FIRST)
        board.put(0, 1, Side.FIRST)
        // 给白一颗子，让中央大空区成为公气而不是全归黑
        board.put(9, 9, Side.SECOND)

        val before = GoInfluence.scoreDifference(board, Side.FIRST)
        // 把那口空填上
        board.put(0, 0, Side.FIRST)
        val after = GoInfluence.scoreDifference(board, Side.FIRST)

        assertEquals(
            before,
            after,
            "数子法下自填己空是子 +1、围空 −1，净变化必须为 0；" +
                "不相等说明两项权重不一致，机器人会一路自填、永不虚着",
        )
    }

    @Test
    @DisplayName("自填己空的估值为负 —— 这是机器人会停手的唯一依据")
    fun `filling own territory scores negative`() {
        val rules = rules()
        val board = rules.initialBoard()
        board.put(1, 0, Side.FIRST)
        board.put(0, 1, Side.FIRST)
        board.put(9, 9, Side.SECOND)

        val eval = evaluator(rules)
        val before = eval.evaluate(board, Side.FIRST)

        val move = GoRules.placementAt(Square(0, 0), Side.FIRST)
        rules.apply(board, move)
        val after = eval.evaluate(board, Side.FIRST)
        rules.undo(board, move)

        assertTrue(
            after < before,
            "自填己空必须让估值下降（净 0 再减去每颗子的固定成本），" +
                "实际 before=$before after=$after",
        )
        assertEquals(
            GoEvaluator.STONE_COST,
            before - after,
            "降幅应正好是一颗子的成本",
        )
    }

    @Test
    @DisplayName("提子仍是净正，落子成本没有压制有价值的手")
    fun `capturing stays clearly positive`() {
        val rules = rules()
        val board = rules.initialBoard()
        // 白单子在角上，黑已占 (1,0)，补 (0,1) 即可提
        board.put(0, 0, Side.SECOND)
        board.put(1, 0, Side.FIRST)
        // 让盘面不至于全归一方
        board.put(9, 9, Side.SECOND)
        board.put(10, 10, Side.FIRST)

        val eval = evaluator(rules)
        val before = eval.evaluate(board, Side.FIRST)

        val move = GoRules.placementAt(Square(0, 1), Side.FIRST)
        rules.apply(board, move)
        val after = eval.evaluate(board, Side.FIRST)
        rules.undo(board, move)

        assertTrue(
            after > before,
            "提子必须是正收益，实际 before=$before after=$after",
        )
    }

    @Test
    @DisplayName("己方被叫吃时气安全分为负，补活后回升")
    fun `atari shows up as a penalty`() {
        val rules = rules()
        val board = rules.initialBoard()
        // 黑两子，被白围到只剩一口气
        board.put(5, 5, Side.FIRST)
        board.put(5, 6, Side.FIRST)
        board.put(5, 4, Side.SECOND)
        board.put(5, 7, Side.SECOND)
        board.put(6, 5, Side.SECOND)
        board.put(6, 6, Side.SECOND)
        board.put(4, 6, Side.SECOND)
        // 只剩 (4,5) 一口气

        val atariPenalty = GoInfluence.libertySafety(board, Side.FIRST)
        assertTrue(
            atariPenalty < 0,
            "己方有块被叫吃时气安全分必须为负，实际 $atariPenalty",
        )

        // 补上那口气（这一手会让黑这块获得更多气）
        board.put(4, 5, Side.FIRST)
        val afterRescue = GoInfluence.libertySafety(board, Side.FIRST)
        assertTrue(
            afterRescue > atariPenalty,
            "补活之后罚分应减轻，实际 $atariPenalty → $afterRescue",
        )
    }

    @Test
    @DisplayName("罚分按块大小放大：大块被叫吃比小块严重")
    fun `penalty scales with group size`() {
        // 一块 1 子被叫吃
        val small = GoRules().initialBoard()
        small.put(5, 5, Side.FIRST)
        small.put(4, 5, Side.SECOND)
        small.put(6, 5, Side.SECOND)
        small.put(5, 4, Side.SECOND)

        // 一块 3 子被叫吃
        val big = GoRules().initialBoard()
        big.put(5, 5, Side.FIRST)
        big.put(5, 6, Side.FIRST)
        big.put(5, 7, Side.FIRST)
        big.put(4, 5, Side.SECOND)
        big.put(4, 6, Side.SECOND)
        big.put(4, 7, Side.SECOND)
        big.put(6, 5, Side.SECOND)
        big.put(6, 6, Side.SECOND)
        big.put(6, 7, Side.SECOND)
        big.put(5, 4, Side.SECOND)

        val smallPenalty = GoInfluence.libertySafety(small, Side.FIRST)
        val bigPenalty = GoInfluence.libertySafety(big, Side.FIRST)

        assertTrue(
            bigPenalty < smallPenalty,
            "大块被叫吃必须罚得更重，否则机器人会拿大块换小块；" +
                "实际 小块=$smallPenalty 大块=$bigPenalty",
        )
    }

    @Test
    @DisplayName("一块只回调一次：大块的罚分不该被子数放大成平方")
    fun `each group is counted once`() {
        val board = GoRules().initialBoard()
        // 横排三子，气很足，不该有任何罚分
        board.put(5, 5, Side.FIRST)
        board.put(6, 5, Side.FIRST)
        board.put(7, 5, Side.FIRST)

        assertEquals(
            0,
            GoInfluence.libertySafety(board, Side.FIRST),
            "气充足的块罚分为 0；非 0 说明遍历把一块按每颗子重复算了",
        )
    }

    // ---- 候选 ----

    @Test
    @DisplayName("空盘返回星位而不是随机点")
    fun `empty board opens on a star point`() {
        val rules = rules()
        val board = rules.initialBoard()
        val moves = evaluator(rules).candidates(board, Side.FIRST)

        // 首个是星位，末位是虚着
        val star = Square(GoEvaluator.STAR_POINT, GoEvaluator.STAR_POINT)
        assertEquals(star, moves.first().to, "空盘上所有点估值相同，必须特判星位")
        assertTrue(moves.last().pass, "虚着应排在最后")
    }

    @Test
    @DisplayName("候选里最后一个是虚着，且只有一个虚着")
    fun `pass is the last candidate`() {
        val rules = rules()
        val board = rules.initialBoard()
        board.put(9, 9, Side.SECOND)

        val moves = evaluator(rules).candidates(board, Side.FIRST)

        assertTrue(moves.last().pass, "虚着必须排最后，这样同分时优先落子")
        assertEquals(1, moves.count { it.pass }, "虚着只该有一个")
    }

    @Test
    @DisplayName("候选数不超过配置上限（19 路可行性的前提）")
    fun `candidates respect the cap`() {
        val rules = rules()
        val board = rules.initialBoard()
        // 铺一片子，让附近空点远超上限
        var n = 0
        for (rank in 5..10) {
            for (file in 5..12) {
                board.put(file, rank, if (n % 2 == 0) Side.FIRST else Side.SECOND)
                n++
            }
        }

        val cap = 12
        val moves = evaluator(rules, maxCandidates = cap).candidates(board, Side.FIRST)

        // 上限只约束落子候选，虚着是额外加的
        assertTrue(
            moves.size <= cap + 1,
            "候选数必须受上限约束，否则 19 路两层搜索跑不完，实际 ${moves.size}",
        )
        // 必须真的撞到上限，否则这条测试证明不了裁剪起了作用
        assertEquals(
            cap + 1,
            moves.size,
            "这个局面的可选点远多于上限，候选应当正好被截到 $cap 个加虚着",
        )
    }

    @Test
    @DisplayName("候选包含救己方危块的气位，即使它在候选半径之外")
    fun `candidates include rescue points beyond the radius`() {
        val rules = rules()
        val board = rules.initialBoard()

        // 在角上做一块黑棋只剩一口气，救命点是 (0,2)
        board.put(0, 0, Side.FIRST)
        board.put(0, 1, Side.FIRST)
        board.put(1, 0, Side.SECOND)
        board.put(1, 1, Side.SECOND)
        // (0,2) 是这块唯一的气

        // 在很远处铺一片子，让"已有子附近"的候选被那片占满
        var n = 0
        for (rank in 12..17) {
            for (file in 12..17) {
                board.put(file, rank, if (n % 2 == 0) Side.FIRST else Side.SECOND)
                n++
            }
        }

        val critical = GoInfluence.criticalPoints(board, Side.FIRST)
        assertTrue(
            Square(0, 2) in critical,
            "前提：(0,2) 应被识别为己方危块的气位",
        )

        val moves = evaluator(rules).candidates(board, Side.FIRST)
        assertTrue(
            moves.any { it.to == Square(0, 2) },
            "救命点必须进候选，即使它离主战场很远 —— " +
                "漏了就等于机器人不会救被叫吃的子",
        )
    }

    // ---- 单色围死区域的摘除 ----

    @Test
    @DisplayName("打入对方围死的空区会让估值虚高 —— 这是要摘点的原因")
    fun `invading a sealed region inflates the score`() {
        val rules = rules()
        val board = rules.initialBoard()
        // 两道贴在一起的墙，把盘分成黑白各一片围死的空区
        for (rank in 0 until 19) {
            board.put(8, rank, Side.FIRST)
            board.put(9, rank, Side.SECOND)
        }

        val eval = evaluator(rules)
        val before = eval.evaluate(board, Side.FIRST)

        // 往白方那片里插一子
        val invade = GoRules.placementAt(Square(12, 5), Side.FIRST)
        rules.apply(board, invade)
        val after = eval.evaluate(board, Side.FIRST)
        rules.undo(board, invade)

        // 这不是要修的行为，是要绕开的事实：数子口径下一片空区只在
        // 只贴一色时才算围空，插一颗子就让白方那 152 点全部作废
        assertTrue(
            after - before > 1000,
            "前提：打入会让估值暴涨（实测约 +1500），实际 ${after - before}。" +
                "正因为估值没法表达'那颗子是死的'，才必须从候选里摘掉这些点",
        )
    }

    @Test
    @DisplayName("对方围死的空区整片进 settled，不进候选")
    fun `sealed enemy region is excluded`() {
        val rules = rules()
        val board = rules.initialBoard()
        for (rank in 0 until 19) {
            board.put(8, rank, Side.FIRST)
            board.put(9, rank, Side.SECOND)
        }

        val settled = GoInfluence.settledEmptyPoints(board)
        assertTrue(Square(12, 5) in settled, "白方围死区里的点应被摘除")
        assertTrue(Square(4, 5) in settled, "黑方自己围死区里的点也应被摘除")

        val moves = evaluator(rules).candidates(board, Side.FIRST)
        assertEquals(
            1,
            moves.size,
            "全盘每个空点都在单色围死区里，候选应只剩虚着，实际 " +
                moves.map { if (it.pass) "PASS" else it.to.toString() },
        )
        assertTrue(moves.single().pass)
    }

    @Test
    @DisplayName("公气不算围死，仍要留在候选里")
    fun `dame stays in the candidates`() {
        val rules = rules()
        val board = rules.initialBoard()
        // 两墙之间留一条公气
        for (rank in 0 until 19) {
            board.put(8, rank, Side.FIRST)
            board.put(10, rank, Side.SECOND)
        }

        val settled = GoInfluence.settledEmptyPoints(board)
        assertFalse(
            Square(9, 5) in settled,
            "公气两色都贴，不属于任何一方，摘掉它等于放着 1 子的便宜不拿",
        )

        val moves = evaluator(rules).candidates(board, Side.FIRST)
        assertTrue(
            moves.any { !it.pass && it.to.file == 9 },
            "公气必须留在候选里",
        )
    }

    @Test
    @DisplayName("开局一方无子时不摘任何点 —— 否则白方开局就无处可下")
    fun `never excludes anything while a side has no stones`() {
        val rules = rules()
        val board = rules.initialBoard()
        // 黑下了一手，白还没下。此时全盘那片大空区只贴黑一色，
        // 不加护栏就会被整片摘掉
        board.put(3, 3, Side.FIRST)

        assertTrue(
            GoInfluence.settledEmptyPoints(board).isEmpty(),
            "白方一颗子都没有时不能摘点，否则它的候选只剩虚着，" +
                "一局棋会在第二手就结束",
        )

        val moves = evaluator(rules).candidates(board.turnOf(Side.SECOND), Side.SECOND)
        assertTrue(
            moves.any { !it.pass },
            "白方开局必须有落子候选",
        )
    }

    @Test
    @DisplayName("双方都有子之后，中央大空区不会被误摘")
    fun `the open centre is not mistaken for territory`() {
        val rules = rules()
        val board = rules.initialBoard()
        board.put(3, 3, Side.FIRST)
        board.put(15, 15, Side.SECOND)

        val settled = GoInfluence.settledEmptyPoints(board)
        assertTrue(
            settled.isEmpty(),
            "开局两子的盘面上大空区同时贴到两色，一个点都不该摘，实际摘了 ${settled.size} 个",
        )
    }

    @Test
    @DisplayName("提子点即使落在对方围死的眼里也不摘 —— 危块气位豁免的真实用处")
    fun `capture points inside a sealed eye survive the exclusion`() {
        val rules = rules()
        val board = rules.initialBoard()

        // 白四子围出一个眼 (2,1)，外面被黑全部围死。
        // 那个眼只贴白一色，所以它会进 settled；但黑下进去正好提掉白这四子。
        board.put(2, 0, Side.SECOND)
        board.put(1, 1, Side.SECOND)
        board.put(3, 1, Side.SECOND)
        board.put(2, 2, Side.SECOND)
        // 黑把白这一圈从外面围住
        board.put(1, 0, Side.FIRST)
        board.put(3, 0, Side.FIRST)
        board.put(0, 1, Side.FIRST)
        board.put(4, 1, Side.FIRST)
        board.put(1, 2, Side.FIRST)
        board.put(3, 2, Side.FIRST)
        board.put(2, 3, Side.FIRST)

        board.turnOf(Side.FIRST)

        val eye = Square(2, 1)
        // 前提一：那个眼确实被判成"白方围死"，会进 settled
        assertTrue(
            eye in GoInfluence.settledEmptyPoints(board),
            "前提没成立：$eye 应当被判成白方围死的空点",
        )
        // 前提二：黑下这一手是合法的（提子，不是自杀）
        assertNull(
            rules.rejectionOf(board, eye, eye, Side.FIRST),
            "前提没成立：黑下 $eye 应当合法（能提子）",
        )

        val moves = evaluator(rules).candidates(board, Side.FIRST)
        assertTrue(
            moves.any { it.to == eye && !it.pass },
            "提子点 $eye 被 settled 摘掉了。这就是危块气位必须豁免过滤的原因：" +
                "对方最后一口气恰在它自己围死的眼里时，那一手是提子而不是打入",
        )
    }

    // ---- 接口成员的围棋语义 ----

    @Test
    @DisplayName("isWinningMove 恒 false，虚着也是")
    fun `no move is ever a winning move`() {
        val rules = rules()
        val board = rules.initialBoard()
        val eval = evaluator(rules)

        val placement = GoRules.placementAt(Square(3, 3), Side.FIRST)
        rules.apply(board, placement)
        assertFalse(eval.isWinningMove(board, placement), "落子不可能直接赢")

        val pass = rules.passMove(Side.SECOND)
        rules.apply(board, pass)
        assertFalse(
            eval.isWinningMove(board, pass),
            "虚着也不能返回 true：搜索会把它当必胜分并停止递归，" +
                "于是机器人在微弱领先时抢着虚着送掉主动权",
        )
    }

    @Test
    @DisplayName("scoreWhenNoMoves 是 0，不是象棋那套负分")
    fun `no moves scores neutral`() {
        assertEquals(
            0,
            evaluator(rules()).scoreWhenNoMoves,
            "围棋无处可下时还能虚着，不判负；用 -winScore 会让机器人乱送子",
        )
    }

    // ---- 必走手 ----

    @Test
    @DisplayName("能一手提够门槛的子就必提，不进搜索")
    fun `capturing a big group is forced`() {
        val rules = rules()
        val board = rules.initialBoard()

        // 白三子竖排，黑围住除 (4,6) 外所有点
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
        // (4,6) 是白这块最后一口气

        val forced = evaluator(rules).forcedMove(board, Side.FIRST)
        assertNotNull(forced, "能提 3 子应当是必走手")
        assertEquals(
            Square(4, 6),
            forced.to,
            "必走手应当是那个提子点",
        )
    }

    @Test
    @DisplayName("自己被叫吃且补上能活时必救")
    fun `rescuing an atari group is forced`() {
        val rules = rules()
        val board = rules.initialBoard()

        // 黑两子被围到一口气，补上之后还有气
        board.put(5, 5, Side.FIRST)
        board.put(5, 6, Side.FIRST)
        board.put(5, 4, Side.SECOND)
        board.put(5, 7, Side.SECOND)
        board.put(6, 5, Side.SECOND)
        board.put(6, 6, Side.SECOND)
        // (4,5) 与 (4,6) 都空着，黑这块有两口气 —— 先堵掉一个
        board.put(4, 6, Side.SECOND)
        // 现在只剩 (4,5)

        val forced = evaluator(rules).forcedMove(board, Side.FIRST)
        assertNotNull(forced, "被叫吃且能救时应当是必走手")
        assertEquals(Square(4, 5), forced.to, "必走手应当是那口救命气")
    }

    @Test
    @DisplayName("平静局面没有必走手，交给搜索")
    fun `quiet position has no forced move`() {
        val rules = rules()
        val board = rules.initialBoard()
        // 两颗互不相干的子，谁都不危险
        board.put(3, 3, Side.FIRST)
        board.put(15, 15, Side.SECOND)

        assertNull(
            evaluator(rules).forcedMove(board, Side.FIRST),
            "没有提子机会也没有危块时不该有必走手，否则会盖掉搜索的判断",
        )
    }

    @Test
    @DisplayName("补成一气不算救活，不该当必走手")
    fun `refusing to rescue into another atari`() {
        val rules = rules()
        val board = rules.initialBoard()

        // 黑一子在角上只剩 (0,1) 一口气，而 (0,1) 补上之后这块仍只有 1 气
        board.put(0, 0, Side.FIRST)
        board.put(1, 0, Side.SECOND)
        board.put(1, 1, Side.SECOND)
        board.put(0, 2, Side.SECOND)
        // 补 (0,1) → 黑这块变成两子，气只有…检查实现是否拒绝

        val forced = evaluator(rules).forcedMove(board, Side.FIRST)
        // 补上之后黑 (0,0)+(0,1) 这块的气只剩 0（被白全包），属于自杀，
        // rejectionOf 会先拒掉；即使不自杀也不该因为"只有 1 气"就救
        if (forced != null) {
            val liberties = linmumua.chess.rule.go.GoBoardOps
                .groupAt(board, forced.to)
                .liberties
                .size
            assertTrue(
                liberties >= GoEvaluator.MIN_RESCUE_LIBERTIES || forced.to != Square(0, 1),
                "补成 1 气等于把整块一起送掉，比不补更糟，不该作为必走手",
            )
        }
    }
}
