package linmumua.chess.rule.xiangqi

import linmumua.chess.rule.core.Side

/**
 * 终局状态。
 *
 * 只覆盖规则层能自己算出来的结果：将死、困毙、自然限着和棋。
 * 认输、超时、双方同意和棋取决于玩家行为，规则层看不到玩家，
 * 那几种结局由对局流程层自己表达，不塞进这个类型。
 */
sealed interface GameOutcome {

    /** 棋局未结束 */
    data object Ongoing : GameOutcome

    /**
     * 将死：被将军且无合法着法，[loser] 判负。
     */
    data class Checkmate(val loser: Side) : GameOutcome {
        val winner: Side get() = loser.opposite
    }

    /**
     * 困毙：未被将军但无合法着法，[loser] 同样判负。
     *
     * 这点与国际象棋不同 —— 国际象棋的 stalemate 是和棋，象棋的困毙是负。
     * 依据：亚洲象棋规例第四章，以及中文维基/英文维基对困毙的一致表述。
     */
    data class Stalemate(val loser: Side) : GameOutcome {
        val winner: Side get() = loser.opposite
    }

    /**
     * 自然限着和棋：连续若干回合无吃子。
     *
     * [fullMoves] 是达成判和时的回合数，方便播报。
     */
    data class DrawByMoveLimit(val fullMoves: Int) : GameOutcome

    /**
     * 连成一线取胜：五子棋专用，[winner] 连满五子。
     *
     * 与 [Checkmate] 分开而不是复用，是因为播报文案不一样 ——
     * 五子棋赢在"连五"，说"将死"是错的。
     */
    data class LineWin(val winner: Side) : GameOutcome {
        val loser: Side get() = winner.opposite
    }

    /** 满盘无处可下的和棋：五子棋专用 */
    data object DrawByFullBoard : GameOutcome

    /**
     * 数子终局：围棋专用。
     *
     * [firstScore]/[secondScore] 是双方各自的子数（含贴子），
     * 平局时 [winner] 为 null。
     */
    data class ScoreWin(
        val winner: Side?,
        val firstScore: Double,
        val secondScore: Double,
    ) : GameOutcome

    /** 是否已终局 */
    val isFinished: Boolean get() = this !is Ongoing
}

/**
 * 规则可调项。
 *
 * 自然限着的回合数按中国象棋竞赛规则默认 60 个回合（双方各走一步算一个回合）无吃子判和，
 * 允许服主改或关掉。长将、长捉这类循环判定属于裁判级逻辑，
 * 按项目约定是独立的可配置模块，**不在本规则层实现**。
 */
data class XiangqiRuleOptions(
    /** 是否启用自然限着判和 */
    val moveLimitDrawEnabled: Boolean = true,
    /** 无吃子回合上限，单位是回合而不是半回合 */
    val moveLimitFullMoves: Int = 60,
) {
    init {
        require(moveLimitFullMoves > 0) { "自然限着回合数必须为正数" }
    }

    /** 折算成半回合，棋盘内部计的是半回合 */
    val moveLimitHalfMoves: Int get() = moveLimitFullMoves * 2

    companion object {
        /** 默认规则：启用 60 回合自然限着 */
        val DEFAULT = XiangqiRuleOptions()
    }
}
