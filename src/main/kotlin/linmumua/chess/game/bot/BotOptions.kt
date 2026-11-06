package linmumua.chess.game.bot

/**
 * 机器人搜索参数。
 *
 * 与 `config.BotSettings` 分开是有意的：这一层是纯逻辑，要能脱离配置系统跑单测。
 * 配置层负责读 YAML 并做范围钳制，再映射成这个对象传进来。
 *
 * @param depth 搜索层数（半回合）。2 表示"我走一步、对手回一步"，
 *   这是"会堵会抓"的最低要求：1 层只能看到自己这一手的收益，
 *   看不到对手的反击，也就判不出"这手会被对方连五"。
 * @param candidateRadius 落子式棋种的候选范围：只考虑已有子周围这么多格内的空点。
 *   15×15 空盘有 225 个点，全搜两层是 5 万个局面，且远离战场的点毫无意义。
 * @param maxCandidates 每个节点最多展开多少个候选着法。
 *   候选已按"进攻分 + 防守分"排序，截断只会丢掉明显无关的点；
 *   必胜必防手由 [BotEvaluator.forcedMoves] 单独兜住，不受截断影响。
 * @param timeLimitMillis 每手思考时间上限。
 *   搜索用逐层加深，超时就交出**上一层已完整搜完**的结果，
 *   不会交出半层的结果 —— 半层结果里有的分支算过有的没算，选出来的着法可能比不搜还差。
 *   给 0 表示不限时。
 */
data class BotOptions(
    val depth: Int = 2,
    val candidateRadius: Int = 2,
    val maxCandidates: Int = 24,
    val timeLimitMillis: Long = 200L,
) {
    init {
        require(depth >= 1) { "搜索层数至少 1，实际 $depth" }
        require(candidateRadius >= 1) { "候选半径至少 1，实际 $candidateRadius" }
        require(maxCandidates >= 1) { "候选上限至少 1，实际 $maxCandidates" }
        require(timeLimitMillis >= 0) { "思考时限不能为负，实际 $timeLimitMillis" }
    }

    companion object {
        /** 默认两层搜索 */
        val DEFAULT = BotOptions()
    }
}
