package linmumua.chess.game

import linmumua.chess.rule.core.Side

/**
 * 读秒动作栏该显示哪一条文案的判定。
 *
 * 与 [GoBgmDecision] 同一个分工约定：判定放纯对象里可单测，发包的副作用放
 * [TurnCountdown]。读秒每秒发一次给整桌人，键选错的后果是"轮到我了却提示对方行棋"，
 * 这种错在服务器上很难复现（要凑限时对局 + 特定剩余秒数），必须靠单测钉住。
 */
object TurnCountdownDecision {

    /**
     * 进入告警配色的剩余秒数阈值，含端点。
     *
     * 取 5 秒的理由：一步棋从想清楚到点两下（选中 + 落点）大约要两三秒，
     * 5 秒是"还来得及抢救"的最后窗口。阈值再大（比如 10 秒）会让快棋局
     * 整局都是告警色，颜色就不再传递信息了。
     */
    const val URGENT_SECONDS = 3

    /**
     * 选出该发给某个观看者的语言键。
     *
     * 三条分流按"这个人能做什么"划分，而不是按阵营：
     * - 轮到自己 → 催的是他，用 `-ours`
     * - 轮到对手 → 他只能等，用 `-theirs`
     * - 观战者（[viewerSide] 为 null）→ 两边都不是他，必须报出是谁在走，用 `-spectator`
     *
     * 观战者那条与对局双方分开是必要的：观战者看到"对方行棋"会不知道"对方"指谁。
     *
     * @param viewerSide 观看者在本局的阵营，观战者传 null
     * @param sideToMove 当前该走棋的一方
     * @param remainingSeconds 剩余秒数，由 [ChessGame.remainingSeconds] 算出
     */
    fun key(viewerSide: Side?, sideToMove: Side, remainingSeconds: Int): String {
        val base = when (viewerSide) {
            null -> "turn-countdown-spectator"
            sideToMove -> "turn-countdown-ours"
            else -> "turn-countdown-theirs"
        }
        // 紧急态只换配色不换语义，所以是同一条键加后缀而不是另起一套键名，
        // 服主改文案时能一眼看出这两条必须说同一件事
        return if (remainingSeconds <= URGENT_SECONDS) "$base-urgent" else base
    }
}
