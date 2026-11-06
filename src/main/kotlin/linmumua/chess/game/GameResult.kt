package linmumua.chess.game

import linmumua.chess.rule.core.Move
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.core.Square
import linmumua.chess.rule.xiangqi.GameOutcome
import linmumua.chess.rule.xiangqi.MoveRejection

/**
 * 对局最终结果。
 *
 * 规则层的 [GameOutcome] 只覆盖它自己能算出来的三种（将死、困毙、限着和棋），
 * 认输、超时、弃局取决于玩家行为，规则层看不到玩家。
 * 这里把两类合成一个完整结局，并各自对应一条播报文案键。
 */
sealed interface GameResult {

    /** 播报用的语言键 */
    val messageKey: String

    /** 将死 */
    data class Checkmate(val winner: Side, val loser: Side) : GameResult {
        override val messageKey: String get() = "game-over-checkmate"
    }

    /**
     * 困毙。
     *
     * 象棋里困毙判负，不是和棋，这点与国际象棋不同。
     */
    data class Stalemate(val winner: Side, val loser: Side) : GameResult {
        override val messageKey: String get() = "game-over-stalemate"
    }

    /** 自然限着和棋 */
    data class DrawByMoveLimit(val fullMoves: Int) : GameResult {
        override val messageKey: String get() = "game-over-draw-move-limit"
    }

    /** 认输 */
    data class Resign(val loser: Side) : GameResult {
        val winner: Side get() = loser.opposite
        override val messageKey: String get() = "game-over-resign"
    }

    /**
     * 双方同意的和棋。
     *
     * 与 [DrawByMoveLimit] 分开：那条是规则层数着数数出来的，这条纯由玩家行为触发，
     * 规则层压根不知道有"求和"这回事。两者的播报也该不一样 —— 限着和棋要报出多少回合无吃子，
     * 协议和棋没有这个数字，硬套同一条文案会出现"0 回合无吃子"这种废话。
     *
     * 不记发起方：和棋是双方共同的结果，播报里点出"谁先开口求和"没有意义，
     * 反而像在暗示这一方先示弱。
     */
    data object DrawByAgreement : GameResult {
        override val messageKey: String get() = "game-over-draw-agreement"
    }

    /** 超时判负 */
    data class Timeout(val loser: Side) : GameResult {
        val winner: Side get() = loser.opposite
        override val messageKey: String get() = "game-over-timeout"
    }

    /** 一方离开，这局作废 */
    data class Quit(val loser: Side) : GameResult {
        override val messageKey: String get() = "game-over-quit"
    }

    /** 五子棋连五取胜 */
    data class LineWin(val winner: Side, val loser: Side) : GameResult {
        override val messageKey: String get() = "game-over-line-win"
    }

    /** 五子棋满盘和棋 */
    data object DrawByFullBoard : GameResult {
        override val messageKey: String get() = "game-over-draw-full-board"
    }

    /**
     * 围棋数子终局。
     *
     * [winner] 为 null 表示平局。分数是含贴子的子数。
     */
    data class ScoreWin(
        val winner: Side?,
        val firstScore: Double,
        val secondScore: Double,
    ) : GameResult {
        override val messageKey: String
            get() = if (winner == null) "game-over-score-draw" else "game-over-score-win"
    }

    companion object {
        /**
         * 把规则层的终局状态翻成对局结果。
         *
         * [GameOutcome.Ongoing] 不该走到这里，调用方应先判 `isFinished`；
         * 真传进来时返回 null，让调用方自己发现逻辑错了，而不是编一个假结果。
         */
        fun fromOutcome(outcome: GameOutcome): GameResult? = when (outcome) {
            is GameOutcome.Checkmate -> Checkmate(outcome.winner, outcome.loser)
            is GameOutcome.Stalemate -> Stalemate(outcome.winner, outcome.loser)
            is GameOutcome.DrawByMoveLimit -> DrawByMoveLimit(outcome.fullMoves)
            is GameOutcome.LineWin -> LineWin(outcome.winner, outcome.loser)
            GameOutcome.DrawByFullBoard -> DrawByFullBoard
            is GameOutcome.ScoreWin -> ScoreWin(outcome.winner, outcome.firstScore, outcome.secondScore)
            GameOutcome.Ongoing -> null
        }
    }
}

/**
 * 一次落子尝试的结果。
 *
 * 交互层据此决定"发哪条提示、动不动棋子实体"。
 * 用密封接口而不是布尔加错误码，是为了让"成功时一定带着 move 与终局信息"
 * 这件事由类型系统保证。
 */
sealed interface MoveAttempt {

    /**
     * 走成了。
     *
     * @param move 实际落地的着法，含被吃子
     * @param outcome 走完之后**对手**的终局状态
     * @param opponentInCheck 走完之后对手是否被将军
     * @param captured 本手额外提掉的点位，只有围棋会非空
     */
    data class Success(
        val move: Move,
        val outcome: GameOutcome,
        val opponentInCheck: Boolean,
        /**
         * 本手提掉的全部点位。
         *
         * 象棋与五子棋恒为空：象棋一手最多吃一枚，那一枚记在 [Move.captured] 里，
         * 渲染层照终点覆盖即可。
         *
         * 围棋一手可提一整块，[Move.captured] 装不下，而**世界里那些显示实体必须有人删**
         * （项目规则禁止轮询扫实体兜底）。所以这里如实带回点位，交互层据此批量移除。
         * 漏一个点就会在世界里留一枚幽灵子：盘面上没有，看得见摸不着。
         *
         * 选它而不是让交互层去问 `rules.lastCaptures()`：后者是规则实例上的可变状态，
         * 交互层拿到时可能已被下一手覆盖；这里是不可变快照，与这一手绑死。
         */
        val captured: List<Square> = emptyList(),
    ) : MoveAttempt

    /** 被规则或轮次否掉，[rejection] 决定提示哪一条 */
    data class Rejected(val rejection: MoveRejection) : MoveAttempt

    /** 当前阶段不能走棋，比如还在等人或已经终局 */
    data class NotPlaying(val phase: GamePhase) : MoveAttempt

    /** 点棋盘的人不是这局的对手，多半是观战者 */
    data object NotAPlayer : MoveAttempt
}

/**
 * 一次虚着（围棋 pass）尝试的结果。
 *
 * 与 [MoveAttempt] 并列而不是塞进它：虚着没有起终点也不动棋子，
 * 混进 `Success(move, ...)` 会让每个消费方都得先判一次"这个 move 是不是假的"。
 */
sealed interface PassAttempt {

    /**
     * 虚着落地。
     *
     * [outcome] 为 [GameOutcome.Ongoing] 时对局继续；为 [GameOutcome.ScoreWin] 时
     * 即双方连续虚着触发的数子终局。让终局信息挂在这一支上而不是另开变体，
     * 是为了让交互层的处理与走子路径同构 —— 都是"看 outcome 是否 isFinished"。
     */
    data class Passed(val outcome: GameOutcome) : PassAttempt

    /** 当前阶段不能虚着，比如还在等人或已经终局 */
    data class NotPlaying(val phase: GamePhase) : PassAttempt

    /** 执行的人不是这局的对手 */
    data object NotAPlayer : PassAttempt

    /** 没轮到他虚着 */
    data object WrongTurn : PassAttempt

    /**
     * 这个棋种没有虚着。
     *
     * 象棋与五子棋走这一支。判定收在 [ChessGame.pass] 里而不是命令层 ——
     * "哪些棋种支持虚着"是规则性质，命令层不该自己认棋种。
     */
    data object NotSupported : PassAttempt
}
