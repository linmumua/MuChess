package linmumua.chess.rule.go

import linmumua.chess.rule.core.Board
import linmumua.chess.rule.core.BoardSpec
import linmumua.chess.rule.core.Piece
import linmumua.chess.rule.core.PieceType
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.core.Square
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 简化数子测试。
 *
 * 数子是围棋唯一的终局判定，算错就是把赢的判成输。三个易错点各自钉死：
 * 己方围空要计入、公气不能计入任何一方、贴子必须按小数加。
 */
class GoScoringTest {

    private fun board() = Board(BoardSpec.GO, Side.FIRST)

    private fun Board.put(file: Int, rank: Int, side: Side) {
        this[Square(file, rank)] = Piece(PieceType.STONE, side)
    }

    @Test
    @DisplayName("空盘：黑 0，白只有贴子；空点谁都不占")
    fun `empty board gives white only the komi`() {
        val score = GoScoring.score(board(), komi = GoRuleOptions.DEFAULT_KOMI)

        assertEquals(0.0, score.firstScore, "空盘黑方没有子也没有围空")
        assertEquals(
            GoRuleOptions.DEFAULT_KOMI,
            score.secondScore,
            "空盘白方只有贴子；361 个空点两色都不贴边，不能计给任何一方",
        )
        assertEquals(Side.SECOND, score.winner, "空盘按贴子算白胜")
    }

    @Test
    @DisplayName("子数计入：只数盘上的子，不做死活判定")
    fun `stones on board are counted`() {
        val board = board()
        board.put(0, 0, Side.FIRST)
        board.put(1, 1, Side.FIRST)
        board.put(5, 5, Side.SECOND)

        val score = GoScoring.score(board, komi = 0.0)

        // 黑 2 子。(0,0) 与 (1,1) 是斜向，各自成块，两块围不出单色空区。
        assertEquals(2.0, score.firstScore, "黑方 2 子")
        assertEquals(1.0, score.secondScore, "白方 1 子，贴子为 0")
    }

    @Test
    @DisplayName("己方围住的空点计入己方 —— 围空有价值是围棋的核心")
    fun `single colour territory counts for its owner`() {
        val board = board()
        // 黑在左下角围出一个 (0,0) 的空点：占住 (1,0) 与 (0,1)
        board.put(1, 0, Side.FIRST)
        board.put(0, 1, Side.FIRST)
        // 必须给白方一颗子放在开阔处，否则这条测试测不到想测的东西：
        // 盘上只有黑子时，**中央那一大片空区也只贴黑**，于是 359 个空点全归黑，
        // 角上那口空被淹没在总数里，断言就看不出"围空归属"到底算对没有。
        // 有了这颗白子，中央空区变成两色都贴的公气，只剩角上那口是纯黑围空。
        board.put(5, 5, Side.SECOND)

        val score = GoScoring.score(board, komi = 0.0)

        // 黑：2 子 + (0,0) 这 1 个只贴黑的空点 = 3
        assertEquals(3.0, score.firstScore, "角上那口空只贴黑，应计给黑方")
        // 白：1 子，中央空区是公气不计给它
        assertEquals(1.0, score.secondScore, "白子周围是公气，白方只有那 1 颗子")
    }

    @Test
    @DisplayName("一方无子时另一方独占全盘 —— 数子法在这种退化局面下的确定行为")
    fun `one colour alone owns the whole board`() {
        val board = board()
        board.put(1, 0, Side.FIRST)
        board.put(0, 1, Side.FIRST)

        val score = GoScoring.score(board, komi = 0.0)

        // 19×19 = 361。盘上没有白子，所以每一片空区都只贴黑，全部计给黑方。
        // 这不是缺陷：一方一颗子都没有时，让另一方独占全盘正是数子法该给的结果。
        // 单独立一条是因为上面那条测试最初就栽在这里 —— 误以为只有角上那口空算黑的。
        assertEquals(361.0, score.firstScore, "盘上只有黑子时，黑方独占全部 361 点")
        assertEquals(0.0, score.secondScore)
    }

    @Test
    @DisplayName("公气不计给任何一方 —— 两色都贴边的空区是中立的")
    fun `neutral points belong to nobody`() {
        val board = board()
        // 用一条黑线与一条白线把 19 路盘从中间切开，中间留一条空列作为公气。
        // file 8 全黑，file 10 全白，file 9 整列空 —— 这一列两侧都贴子，是公气。
        for (rank in 0 until 19) {
            board.put(8, rank, Side.FIRST)
            board.put(10, rank, Side.SECOND)
        }

        val score = GoScoring.score(board, komi = 0.0)

        // 黑 19 子 + 左侧 file 0..7 共 8×19=152 个只贴黑的空点
        assertEquals(19.0 + 152.0, score.firstScore, "左侧空区只贴黑，计给黑方")
        // 白 19 子 + 右侧 file 11..18 共 8×19=152 个只贴白的空点
        assertEquals(19.0 + 152.0, score.secondScore, "右侧空区只贴白，计给白方")
        // file 9 那 19 个点两色都贴，不计给任何一方
        assertNull(score.winner, "两边完全对称且贴子为 0，应是平局")
    }

    @Test
    @DisplayName("贴子按小数加，不能被截断成整数")
    fun `komi is applied as a fraction`() {
        val board = board()
        // 双方各 1 子，避免围空干扰
        board.put(0, 0, Side.FIRST)
        board.put(18, 18, Side.SECOND)

        val score = GoScoring.score(board, komi = GoRuleOptions.DEFAULT_KOMI)

        assertEquals(1.0, score.firstScore)
        assertEquals(
            1.0 + 3.75,
            score.secondScore,
            "贴子 3.75 必须原样加上；用整数运算会截成 3，白方凭空少 0.75 子",
        )
    }

    @Test
    @DisplayName("贴子能让白方反超 —— 这正是贴子存在的意义")
    fun `komi can flip the result`() {
        val board = board()
        // 黑 4 子、白 1 子，但都不围空
        board.put(0, 0, Side.FIRST)
        board.put(2, 0, Side.FIRST)
        board.put(4, 0, Side.FIRST)
        board.put(6, 0, Side.FIRST)
        board.put(18, 18, Side.SECOND)

        // 不贴子：黑 4 > 白 1，黑胜
        assertEquals(Side.FIRST, GoScoring.score(board, komi = 0.0).winner)

        // 贴 3.75：白 1+3.75=4.75 > 黑 4，白反超
        val withKomi = GoScoring.score(board, komi = GoRuleOptions.DEFAULT_KOMI)
        assertEquals(4.0, withKomi.firstScore)
        assertEquals(4.75, withKomi.secondScore)
        assertEquals(Side.SECOND, withKomi.winner, "贴子应能补偿黑方的先行优势")
    }

    @Test
    @DisplayName("贴子配成整数时可能平局，winner 返回 null")
    fun `integer komi can produce a draw`() {
        val board = board()
        board.put(0, 0, Side.FIRST)
        board.put(1, 0, Side.FIRST)
        board.put(18, 18, Side.SECOND)

        // 黑 2 子，白 1 子 + 贴 1 = 2，正好相等
        val score = GoScoring.score(board, komi = 1.0)
        assertEquals(score.firstScore, score.secondScore)
        assertNull(score.winner, "得分相同时不能硬判一方胜")
    }

    @Test
    @DisplayName("贴子不允许为负")
    fun `negative komi is rejected`() {
        val error = runCatching { GoRuleOptions(komi = -1.0) }.exceptionOrNull()
        assertEquals(
            IllegalArgumentException::class,
            error?.let { it::class },
            "负贴子是配置错误，必须炸出来而不是静默接受",
        )
    }
}
