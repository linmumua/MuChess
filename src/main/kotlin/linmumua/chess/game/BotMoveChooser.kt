package linmumua.chess.game

import kotlin.random.Random
import linmumua.chess.rule.core.Move
import linmumua.chess.rule.core.PieceType

/**
 * 一层贪心选步逻辑。
 *
 * 纯逻辑，不依赖 Bukkit，也不依赖对局运行时状态，可单测。
 *
 * 只做一层贪心（能吃就吃价值最高的），没有搜索、没有前瞻、不判对手的反击。
 *
 * ## 现状：已无调用方
 *
 * 机器人的选步在 1.10.0 已改为 alpha-beta 搜索，入口是
 * [linmumua.chess.game.bot.BotBrain]，`MoveExecutor` 调的是那一边。
 * 本类保留在原处未删除，是否清理由项目维护者决定；
 * 它的子力价值表与 `bot.XiangqiEvaluator` 取同一份数值。
 */
object BotMoveChooser {

    /**
     * 子力价值表，仅用于给吃子步排序。
     *
     * 数值沿用象棋常见的粗略估值（車 > 炮 ≈ 馬 > 相/士 > 兵）。
     * 将给一个极大值：能吃将的局面本来就该终局，不该被别的吃子步盖过。
     */
    private val VALUES = mapOf(
        PieceType.KING to 10000,
        PieceType.CHARIOT to 900,
        PieceType.CANNON to 450,
        PieceType.HORSE to 400,
        PieceType.ELEPHANT to 200,
        PieceType.ADVISOR to 200,
        PieceType.PAWN to 100,
    )

    /**
     * 一步棋的得分：吃到的子值多少就得多少，不吃子记 0。
     *
     * 表里查不到的类型按 0 处理而不是抛异常 —— 以后加棋种时
     * 不该因为漏配一个价值就让机器人整个卡死。
     */
    fun scoreOf(move: Move): Int {
        val captured = move.captured ?: return 0
        return VALUES[captured.type] ?: 0
    }

    /**
     * 从候选着法里挑一步。
     *
     * 取分数最高的那一组，组内随机选一个 —— 同分不打散的话，
     * 机器人每局开头都会走同一步，看着很假。
     *
     * @param moves 合法着法，由规则层给出；调用方负责保证都是合法的
     * @param random 注入随机源，便于单测复现
     * @return 选中的着法；[moves] 为空时返回 null，代表无步可走（将死或困毙）
     */
    fun choose(moves: List<Move>, random: Random = Random.Default): Move? {
        if (moves.isEmpty()) {
            return null
        }
        val best = moves.maxOf { scoreOf(it) }
        val candidates = moves.filter { scoreOf(it) == best }
        return candidates[random.nextInt(candidates.size)]
    }
}
