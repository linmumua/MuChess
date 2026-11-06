package linmumua.chess.game.bot

import linmumua.chess.rule.core.Board
import linmumua.chess.rule.core.Move
import linmumua.chess.rule.core.Side

/**
 * 棋种相关的估值与候选生成。
 *
 * [AlphaBetaSearch] 本身不懂任何棋种：它只会"取候选、试走、递归、撤销"。
 * 一个棋种懂什么（子力价值、威胁模式、哪些点值得考虑）全部收在这个接口的实现里。
 *
 * 全部实现必须是纯逻辑，不含 Bukkit 类型，可脱离服务端单测。
 */
interface BotEvaluator {

    /**
     * 必胜分。
     *
     * 搜索用它表达"这一分支直接赢了"。取值要**远大于**任何正常局面的估值，
     * 否则一个子力占优的局面会被误认为等于取胜，机器人就会放着连五不下去吃子。
     */
    val winScore: Int

    /**
     * 无着可走时，站在**该走棋一方**视角的得分。
     *
     * 象棋是 `-winScore`：将死与困毙都判负。
     * 五子棋是 0：无处可下就是满盘和棋。
     * 这两者反过来会让机器人做出完全错误的选择 —— 象棋会主动走进困毙，
     * 五子棋会为了避免"和棋"去下明显更差的手。
     */
    val scoreWhenNoMoves: Int

    /**
     * 静态估值，站在 [side] 视角：正数表示 [side] 占优。
     *
     * 只看当前局面，不做任何前瞻 —— 前瞻是搜索的事。
     */
    fun evaluate(board: Board, side: Side): Int

    /**
     * 该走棋方值得考虑的着法，**按好坏降序**。
     *
     * 排序质量直接决定 alpha-beta 的剪枝效率：好手排在前面能砍掉大量分支。
     * 实现可以裁掉明显无关的着法（五子棋 225 个空点里绝大多数离战场很远），
     * 但必须保证返回的都是合法着法。
     */
    fun candidates(board: Board, side: Side): List<Move>

    /**
     * 这一手走完是否直接终局取胜。
     *
     * 调用时 [move] **已经落在** [board] 上。搜索靠它在递归前收住分支 ——
     * 五子棋连五之后局面还有空点，不收住的话搜索会继续往下走，
     * 把已经赢定的分支算成后续的乱局。
     */
    fun isWinningMove(board: Board, move: Move): Boolean

    /**
     * 必走的一手：能一步取胜就取胜，对手能一步取胜就先堵。
     *
     * 这是"会玩"与"随便下"的分界线，所以单独拎出来放在搜索之前，
     * 不依赖搜索深度也不受候选裁剪影响 —— 深度配成 1 层、候选被截断，
     * 这一手也必须出得来。
     *
     * @return 必走的着法；没有则返回 null，交给搜索决定
     */
    fun forcedMove(board: Board, side: Side): Move?
}
