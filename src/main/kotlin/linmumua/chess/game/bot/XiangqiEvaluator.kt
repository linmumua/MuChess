package linmumua.chess.game.bot

import linmumua.chess.rule.core.Board
import linmumua.chess.rule.core.Move
import linmumua.chess.rule.core.PieceType
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.core.Square
import linmumua.chess.rule.xiangqi.XiangqiRules

/**
 * 象棋估值器。
 *
 * 从原先的一层贪心吃子升级：子力价值 + 简单位置分，配合 [AlphaBetaSearch] 做前瞻。
 * 一层贪心的问题不是"不会吃子"，而是**看不到吃完会被反吃** ——
 * 它会拿車去吃一个有炮保护的兵。两层搜索能看出来。
 *
 * 位置分只做最基本的三条（过河兵加分、車在开放线加分、子力靠中心加分），
 * 不做开局库、不做残局库、不做长将判定。它是"会下"，不是引擎。
 *
 * 纯逻辑，不含 Bukkit 类型。
 *
 * @param rules 规则实例，走法生成与终局判定都走它
 * @param options 搜索参数，这里只用候选上限
 */
class XiangqiEvaluator(
    private val rules: XiangqiRules,
    private val options: BotOptions = BotOptions.DEFAULT,
) : BotEvaluator {

    override val winScore: Int get() = MATE

    /**
     * 象棋无着可走判**负**：将死与困毙都是输。
     *
     * 这与国际象棋不同（那边困毙是和棋）。写成 0 会让机器人主动走进困毙。
     */
    override val scoreWhenNoMoves: Int get() = -MATE

    /**
     * 静态估值，站在 [side] 视角。
     *
     * 只算子力与位置，不做前瞻。将不计入子力：将被吃掉的局面由终局判定收住，
     * 给将一个巨大分值会让估值被"双方都有将"这个恒定项淹没。
     */
    override fun evaluate(board: Board, side: Side): Int =
        sideScore(board, side) - sideScore(board, side.opposite)

    /**
     * 候选着法：全部合法着法，按"吃子价值 + 位置增量"降序，截到候选上限。
     *
     * 象棋一个局面平均 40 余个合法着法，不像五子棋那样需要按距离裁剪，
     * 但排序仍然关键 —— 好手排前面能让 alpha-beta 砍掉大半分支。
     *
     * 截断用的是**排序后**的列表，所以丢掉的是评分最低的那些手。
     * 必杀手不受影响：将死对手的那一手会被 [forcedMove] 提前抓出来。
     */
    override fun candidates(board: Board, side: Side): List<Move> {
        val moves = rules.legalMoves(board, side)
        if (moves.size <= options.maxCandidates) {
            return moves.sortedByDescending { orderingScore(it) }
        }
        return moves.sortedByDescending { orderingScore(it) }.take(options.maxCandidates)
    }

    /**
     * 这一手是否直接取胜 = 走完之后对手无合法着法。
     *
     * 将死与困毙都算，因为象棋两者都判负。调用时 move 已落在 board 上，
     * 所以 `board.sideToMove` 已经是对手。
     */
    override fun isWinningMove(board: Board, move: Move): Boolean =
        rules.legalMoves(board, move.piece.side.opposite).isEmpty()

    /**
     * 必走的一手：能一步将死（或困毙）对手就走。
     *
     * 象棋没有五子棋那种"对手下一手就赢"的对称情况可以一手堵掉 ——
     * 被将军时所有合法着法本来就已经过滤成"能解将的"，规则层的送将过滤替我们做了防守。
     * 所以这里只做进攻侧的必胜手。
     */
    override fun forcedMove(board: Board, side: Side): Move? {
        for (move in rules.legalMoves(board, side)) {
            rules.apply(board, move)
            val wins = rules.legalMoves(board, side.opposite).isEmpty()
            rules.undo(board, move)
            if (wins) {
                return move
            }
        }
        return null
    }

    /** 单方的子力 + 位置总分 */
    private fun sideScore(board: Board, side: Side): Int {
        var total = 0
        for (square in board.squaresOf(side)) {
            val piece = board[square] ?: continue
            total += valueOf(piece.type)
            total += positionScore(piece.type, square, side)
        }
        return total
    }

    /**
     * 着法排序分。
     *
     * 吃子价值占主导，位置增量只做同价值着法之间的区分。
     * 这个分数**只用于排序**，不参与估值 —— 真正的好坏由搜索算出来。
     */
    private fun orderingScore(move: Move): Int {
        val capture = move.captured?.let { valueOf(it.type) } ?: 0
        val positional = positionScore(move.piece.type, move.to, move.piece.side) -
            positionScore(move.piece.type, move.from, move.piece.side)
        return capture * CAPTURE_ORDER_WEIGHT + positional
    }

    /**
     * 位置分。
     *
     * 三条最基本的象棋常识：
     * - **过河兵加分**：过河的兵能左右走，威胁面大得多；沿路推进再逐格加分
     * - **車炮靠中线加分**：中线上的車炮控制面最大
     * - **馬靠中心加分**：馬在边线只有四个落点，在中心有八个（"馬在边上力量减半"）
     *
     * 将、士、相不给位置分：它们的活动范围本来就被规则锁死在九宫或半场内，
     * 给位置分只会让机器人无意义地在九宫里挪子。
     */
    private fun positionScore(type: PieceType, square: Square, side: Side): Int = when (type) {
        PieceType.PAWN -> pawnPositionScore(square, side)
        PieceType.CHARIOT, PieceType.CANNON -> centerFileBonus(square) * MAJOR_CENTER_WEIGHT
        PieceType.HORSE -> centerFileBonus(square) * HORSE_CENTER_WEIGHT +
            centerRankBonus(square) * HORSE_CENTER_WEIGHT
        else -> 0
    }

    /**
     * 兵的位置分：过河给一笔固定加分，之后每往前一格再加一点。
     *
     * 先行方（红）向 rank 递减方向前进，后行方相反 —— 这一条跟
     * `XiangqiGeometry.pawnForward` 是同一个约定，写反会让机器人把兵往自己家里赶。
     */
    private fun pawnPositionScore(square: Square, side: Side): Int {
        // 河界在 rank 4 与 5 之间：先行方半场是 5..9，所以 rank <= 4 表示已过河
        val crossed = if (side == Side.FIRST) square.rank <= 4 else square.rank >= 5
        if (!crossed) {
            return 0
        }
        // 过河后离对方底线越近越有威胁
        val advance = if (side == Side.FIRST) 4 - square.rank else square.rank - 5
        return PAWN_CROSSED_BONUS + advance * PAWN_ADVANCE_BONUS
    }

    /** 离中间纵线（file 4）越近分越高，边线为 0 */
    private fun centerFileBonus(square: Square): Int = CENTER_FILE - kotlin.math.abs(square.file - CENTER_FILE)

    /** 离中间横线越近分越高 */
    private fun centerRankBonus(square: Square): Int =
        CENTER_RANK_HALF - kotlin.math.abs(square.rank * 2 - RANK_SPAN) / 2

    /** 子力价值，将不计入（见 [evaluate]） */
    private fun valueOf(type: PieceType): Int = VALUES[type] ?: 0

    private companion object {

        /**
         * 子力价值。
         *
         * 沿用象棋常见的粗略估值（車 > 炮 ≈ 馬 > 相/士 > 兵），与旧的一层贪心选步器
         * `linmumua.chess.game.BotMoveChooser.VALUES` 取同一份数值，
         * 避免升级到搜索之后机器人的吃子偏好突然变样。
         *
         * 那个类现在已无调用方（`MoveExecutor` 已改为走 [BotBrain]），但仍留在原处 ——
         * 是否清理由项目维护者决定，本类不依赖它。
         *
         * 将不在表里：见 [evaluate] 的说明。
         */
        val VALUES = mapOf(
            PieceType.CHARIOT to 900,
            PieceType.CANNON to 450,
            PieceType.HORSE to 400,
            PieceType.ELEPHANT to 200,
            PieceType.ADVISOR to 200,
            PieceType.PAWN to 100,
        )

        /**
         * 将死分。
         *
         * 必须远大于任何可能的子力总和（满盘约 6000），否则"将死对手"会被
         * "吃掉一个車"之类的收益盖过。同时要留出 `AlphaBetaSearch.INFINITY` 的余量。
         */
        const val MATE = 500_000

        /** 排序时吃子分的放大倍数，保证吃子手一定排在纯位置手前面 */
        const val CAPTURE_ORDER_WEIGHT = 100

        /** 中间纵线序号（9 纵线的正中） */
        const val CENTER_FILE = 4

        /** 10 条横线的跨度，用于算中心度 */
        const val RANK_SPAN = 9

        /** 横线中心度的最大值 */
        const val CENTER_RANK_HALF = 4

        /** 車炮的中线权重 */
        const val MAJOR_CENTER_WEIGHT = 6

        /** 馬的中心权重，比車炮高：馬在边线落点直接少一半 */
        const val HORSE_CENTER_WEIGHT = 8

        /** 兵过河的固定加分 */
        const val PAWN_CROSSED_BONUS = 60

        /** 过河兵每前进一格的加分 */
        const val PAWN_ADVANCE_BONUS = 10
    }
}
