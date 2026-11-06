package linmumua.chess.game.bot

import linmumua.chess.rule.core.Board
import linmumua.chess.rule.core.Move
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.core.Square
import linmumua.chess.rule.go.GoBoardOps
import linmumua.chess.rule.go.GoRules

/**
 * 围棋启发式估值器。
 *
 * ## 棋力上限（先说清楚）
 *
 * 这不是一个有棋力的机器人，约等于刚学会规则的新手。它会：提明显能提的子、
 * 救自己被叫吃的块、不自杀、收官后停手。它不会：定式、做活做死、征子、判断死活。
 *
 * 还有一条是**主动放弃**的：它永不往已被单色围死的空区里打入，哪怕那片"围死"
 * 其实薄得能破。原因见 [GoInfluence.settledEmptyPoints] —— 数子口径下一颗死子
 * 就能让对方整片围空作废，估值虚高上千，而判断那颗子死活需要读棋。
 * 取舍是明确的：不打入只会让它少赚，乱打入会让它把子送光并且永不终局。
 *
 * 做不到不是实现偷懒，是方法的天花板：围棋一手的好坏取决于死活与先后手，
 * **没有可靠的静态函数能表达**。五子棋能用"连五"这种固定模式分级威胁，
 * 象棋有公认的子力价值表，围棋两者都没有。现代围棋 AI 全部靠蒙特卡洛树搜索
 * 加深度网络，不是几百行启发式能替代的。
 *
 * 还有一条必须知道的限制：**它不会为胜负挣一下**。[isWinningMove] 恒 false 且
 * 搜索从不调用 `outcome()`，所以它无法判断"现在虚着是赢还是输"。只要所有落子
 * 都算成负分它就虚着，哪怕数子结果是自己输。让它停手的判据本身不含胜负信息。
 *
 * 纯逻辑，不含 Bukkit 类型。
 *
 * @param rules 规则实例，用来判合法性与生成着法
 * @param options 通用搜索参数，这里用候选半径
 * @param maxCandidates 候选上限。围棋单独一档（`go.bot-max-candidates`，默认 12），
 *   不用 [BotOptions.maxCandidates] 那个三棋种共用值 —— 19 路要更狠的裁剪，
 *   而调小共用值会连带削弱五子棋的防守
 */
class GoEvaluator(
    private val rules: GoRules,
    private val options: BotOptions = BotOptions.DEFAULT,
    private val maxCandidates: Int = DEFAULT_MAX_CANDIDATES,
) : BotEvaluator {

    /**
     * 必胜分。
     *
     * 围棋里没有"这一手直接赢"（见 [isWinningMove]），所以这个值实际不会被搜索用到。
     * 仍然要给一个远大于正常估值的数：`AlphaBetaSearch` 拿它与 alpha-beta 边界比较，
     * 给小了会让边界夹掉正常分数。
     */
    override val winScore: Int get() = 1_000_000

    /**
     * 无着可走时的得分：0。
     *
     * 象棋是 `-winScore`（将死困毙判负），五子棋是 0（满盘和棋），围棋两者都不能沿用：
     * 围棋无处可下时**还能虚着**，不判负。照抄象棋会让机器人以为"快没地方下了"
     * 等于要输，从而乱送子。
     *
     * 实际这个分支走不到 —— [candidates] 里无条件含虚着，列表永不为空。
     * 仍然返回 0 而不是抛异常：将来若有人改了裁剪逻辑导致列表为空，
     * 给个中性分比崩掉整局安全。
     */
    override val scoreWhenNoMoves: Int get() = 0

    /**
     * 静态估值，[side] 视角。
     *
     * ```
     * evaluate = SCORE_WEIGHT × 数子分差
     *          + 气安全差
     *          − STONE_COST × 己方子数
     * ```
     *
     * ## 为什么第三项必须存在，且必须是"位置性"的
     *
     * 中国数子法下 `score = 子数 + 单色围空`。往自己的空里填一子，子数 +1、围空 −1，
     * **净变化恰好为 0**。也就是说单靠数子分差，自填己空是不亏的，机器人会随手填，
     * 于是永远算不出"下哪都是亏"、永远不虚着，而围棋只认双方连续虚着终局 ——
     * 结果是每局都下到无处可下为止。
     *
     * 修法是给"多下一子"一个固定成本。但注意 [evaluate] 的签名只有 `(board, side)`，
     * **拿不到着法**，而虚着不改变盘面，所以这个成本不能写成"对某一手的惩罚"，
     * 只能写成随盘上己方子数变化的位置性项。
     *
     * 于是：自填己空 → 数子分不变、子数 +1 → 估值 −1；虚着 → 盘面不变 → 相对为 0。
     * 虚着自然胜出。
     *
     * ## 提子仍是净正的
     *
     * 提掉对方 1 子：对方数子分 −1，差值 +1，乘权重得 +10；己方那颗落子扣 1，
     * 净 **+9**。所以惩罚不会让机器人不敢下有价值的手。
     *
     * ## 10% 不对称是有意的
     *
     * 加了 [STONE_COST] 之后己方每颗子的实际净权重是 9，围空仍是 10，
     * 机器人略微偏好围空而非落子。这个方向是安全的：偏向少下子只会让它早点停手；
     * 反过来偏向多下子就是上面那个"永不终局"的 bug。
     */
    override fun evaluate(board: Board, side: Side): Int =
        SCORE_WEIGHT * GoInfluence.scoreDifference(board, side) +
            GoInfluence.libertySafety(board, side) -
            STONE_COST * GoInfluence.stoneCount(board, side)

    /**
     * 候选着法，按好坏降序，末位是虚着。
     *
     * 三类来源合并去重：
     * 1. 己方危块（气 ≤ 2）的气位 —— 救自己的子
     * 2. 对方危块的气位 —— 去提对方
     * 3. 已有子附近的空点，**扣掉已被单色围死的** —— 战斗都在这里
     *
     * 第 1、2 类必须显式收集，不能指望第 3 类覆盖：一块的救命点可能正好落在
     * 候选半径之外，漏了就等于机器人不会救子。
     *
     * 第 3 类要扣掉 [GoInfluence.settledEmptyPoints]，否则机器人会往对方已围死的
     * 空区里插死子（那样能让对方围空归零，估值虚高 +1510，详见该方法注释）。
     *
     * 前两类**不扣**，理由是提子：对方一块的最后一口气可能正好是它自己围出的眼，
     * 而那个眼只贴对方一色，会被判成"对方围死的空区"。下进去其实是提掉整块，
     * 不是打入。扣掉的话机器人会放着能提的一块不提。
     * （己方危块那一类同样不扣，纯为对称；自己围死区里的气位补进去多半仍是自送，
     * 会被估值否掉，留着不影响结果。）
     *
     * 虚着**无条件**排在最后，不做"只在劣势时才加"的特判：有了位置性落子成本之后，
     * 虚着的胜出条件已经是纯算术的（所有落子都为负时它的 0 最大）。特判反而要引入
     * "什么算劣势"这个新判断，而那正是算不准的东西。排最后是为了同分时优先落子。
     */
    override fun candidates(board: Board, side: Side): List<Move> {
        val pass = rules.passMove(side)

        // 空盘特判：所有点估值相同，不特判会退化成随机选点。
        // 围棋开局下星位是常识，19 路星位是 3/9/15 线（与资源包画的星位同一套）。
        if (board.pieceCount() == 0) {
            val star = Square(STAR_POINT, STAR_POINT)
            return listOf(GoRules.placementAt(star, side), pass)
        }

        val points = LinkedHashSet<Int>()
        val collected = ArrayList<Square>()
        fun add(square: Square) {
            if (board.isEmpty(square) && points.add(square.packed)) {
                collected += square
            }
        }
        // 先加危块气位：它们最重要，且数量少，放前面能保证不被后面的量淹掉。
        // 这两类不过 settled 过滤 —— 见方法注释里"前两类不扣"那段
        GoInfluence.criticalPoints(board, side).forEach(::add)
        GoInfluence.criticalPoints(board, side.opposite).forEach(::add)

        val settled = GoInfluence.settledEmptyPoints(board)
        GoInfluence.nearbyEmptySquares(board, options.candidateRadius)
            .filter { it !in settled }
            .forEach(::add)

        val legal = collected
            .filter { rules.rejectionOf(board, it, it, side) == null }
            .sortedByDescending { orderingScore(board, it, side) }
            .take(maxCandidates)
            .map { GoRules.placementAt(it, side) }

        // 一个合法落点都没有（极罕见：全盘只剩自杀点）时也要给出虚着，
        // 否则搜索会当成"无着可走"走到 scoreWhenNoMoves 那条死路
        return legal + pass
    }

    /**
     * 这一手是否直接终局取胜：**恒 false**。
     *
     * 围棋没有"落子即赢"。虚着可能终局，但终局不等于赢 —— 数子之后可能是输。
     *
     * 关键是不能让虚着在领先时返回 true：搜索对 true 的处理是
     * `return winScore - depth` 并**停止递归**，于是机器人会把"我虚着 → 对手也虚着
     * → 我赢"当成必胜分。可对手完全可以不虚着继续下，那个分支根本不成立。
     * 结果是机器人在微弱领先时抢着虚着，把主动权送掉。
     *
     * 领先与否已经由 [evaluate] 的数子分差如实反映，不必在这里再表达一次。
     */
    override fun isWinningMove(board: Board, move: Move): Boolean = false

    /**
     * 必走手：能提一大块就提，自己被叫吃且能救就救。
     *
     * 五子棋的必走手是"能连五就连五、对手能连五就堵"，围棋没有这么干脆的对应物，
     * 但这两条足够硬 —— 都是确定的、不依赖前瞻的收益。
     *
     * 顺序是**先提再救**：提掉对方那块通常同时解决了自己的危险（对方的子没了，
     * 自己的气就回来了），反过来先救则可能错过提子机会。
     *
     * 不走搜索，所以深度配成 1 层或候选被截断时这两手也一定出得来。
     */
    override fun forcedMove(board: Board, side: Side): Move? {
        captureMove(board, side)?.let { return it }
        return rescueMove(board, side)
    }

    /**
     * 能一手提掉 [CAPTURE_THRESHOLD] 子以上的着法。
     *
     * 只看对方危块的气位：提子只可能发生在那些点上，不必扫全盘空点。
     */
    private fun captureMove(board: Board, side: Side): Move? {
        var best: Square? = null
        var bestCount = 0
        for (point in GoInfluence.criticalPoints(board, side.opposite)) {
            if (rules.rejectionOf(board, point, point, side) != null) {
                continue
            }
            val count = capturedCountAt(board, point, side)
            if (count > bestCount) {
                bestCount = count
                best = point
            }
        }
        return if (bestCount >= CAPTURE_THRESHOLD && best != null) {
            GoRules.placementAt(best, side)
        } else {
            null
        }
    }

    /**
     * 自己有块只剩 1 气且补上之后能活的着法。
     *
     * "能活"的判据是补完之后该块气数 ≥ 2：补成 1 气等于把两颗子一起送掉，
     * 比不补更糟。这一步必须真的试走去数，不能只看气位数量 ——
     * 一块 1 气的棋补上那口气之后可能仍然只有 1 气（对方已经围死）。
     */
    private fun rescueMove(board: Board, side: Side): Move? {
        for (point in GoInfluence.criticalPoints(board, side)) {
            if (rules.rejectionOf(board, point, point, side) != null) {
                continue
            }
            if (libertiesAfterPlacing(board, point, side) >= MIN_RESCUE_LIBERTIES) {
                return GoRules.placementAt(point, side)
            }
        }
        return null
    }

    /**
     * 候选排序分：试走一手之后的估值。
     *
     * 直接用 [evaluate] 而不是另造一套排序启发式：排序与估值用同一把尺子，
     * 不会出现"排在最前的手搜出来分最低"这种自相矛盾。代价是每个候选要试走一次，
     * 但候选已经裁到十几个，可以接受。
     */
    private fun orderingScore(board: Board, square: Square, side: Side): Int {
        val move = GoRules.placementAt(square, side)
        rules.apply(board, move)
        val score = evaluate(board, side)
        rules.undo(board, move)
        return score
    }

    /** 在某点落子会提掉多少对方子，靠试走后读规则层的提子记录 */
    private fun capturedCountAt(board: Board, square: Square, side: Side): Int {
        val move = GoRules.placementAt(square, side)
        rules.apply(board, move)
        val count = rules.lastCaptures().size
        rules.undo(board, move)
        return count
    }

    /** 在某点落子后，该点所在块的气数 */
    private fun libertiesAfterPlacing(board: Board, square: Square, side: Side): Int {
        val move = GoRules.placementAt(square, side)
        rules.apply(board, move)
        // 这颗子可能已经被提（自杀手），那时 groupAt 返回空块、气数 0
        val liberties = GoBoardOps.groupAt(board, square).liberties.size
        rules.undo(board, move)
        return liberties
    }

    companion object {
        /** 数子分差的权重 */
        const val SCORE_WEIGHT = 10

        /**
         * 每颗己方子的固定成本，即"多下一子"的代价。
         *
         * 取 1：必须远小于任何真实收益（最小的真实收益是提一子的 +10），
         * 否则会压制正常着法，机器人连该下的手都不下。
         */
        const val STONE_COST = 1

        /** 必走手的提子门槛：一手能提这么多子就直接提，不进搜索 */
        const val CAPTURE_THRESHOLD = 3

        /** 救子后至少要有这么多气才算救活，补成 1 气等于白送 */
        const val MIN_RESCUE_LIBERTIES = 2

        /**
         * 开局星位的线号（0 起算）。
         *
         * 19 路星位是 3/9/15 线，取 3 即左下角星位。与资源包贴图画的星位同一套
         * （见构建期 `TextureGen.writeGridBoard`）。
         */
        const val STAR_POINT = 3

        /** 候选上限的默认值，与 `GoSettings.botMaxCandidates` 保持一致 */
        const val DEFAULT_MAX_CANDIDATES = 12
    }
}
