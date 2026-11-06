package linmumua.chess.game.request

import java.util.UUID
import linmumua.chess.rule.core.Side

/**
 * 全部对局的待答复请求登记表。
 *
 * 按 `session.id` 索引，同一局同一类型最多一条 —— 这条约束就是"防连点刷请求"的实现：
 * 第二次发起会撞上还没过期的第一条，直接被 [GameRequestDecision.open] 判 PENDING。
 *
 * 为什么状态放这里而不放 [linmumua.chess.game.GameSession]：
 * 请求是**不落盘**的临时状态（重启后没人还记得三十秒前有人求和），而 GameSession 的字段
 * 基本都要进 `BoardRecord`。挂在会话上会让人以为它该被持久化。按 session id 索引的
 * 运行时登记表在本项目已有先例（机器人占座登记），保持同一种做法。
 *
 * 不依赖 Bukkit，可直接单测。所有方法都要求在主线程调用（与对局状态同一个线程）。
 */
object GameRequests {

    /** sessionId -> (类型 -> 请求)。同类型只留最新一条 */
    private val pending = HashMap<UUID, HashMap<GameRequestKind, GameRequest>>()

    /**
     * 发起一条请求，成功返回它，被"同类型已有待答复请求"挡住时返回 null。
     *
     * 覆盖已过期的旧请求而不是先清再存：过期请求在语义上已经不存在了，
     * 判定见 [GameRequestDecision.open]。
     */
    fun open(
        sessionId: UUID,
        kind: GameRequestKind,
        requester: Side,
        now: Long,
        ttlSeconds: Int = GameRequestDecision.DEFAULT_TTL_SECONDS,
    ): GameRequest? {
        val slots = pending[sessionId]
        if (GameRequestDecision.open(slots?.get(kind), now) != GameRequestDecision.OpenVerdict.ALLOWED) {
            return null
        }
        val request = GameRequest(kind, requester, GameRequestDecision.expiryOf(now, ttlSeconds))
        pending.getOrPut(sessionId) { HashMap() }[kind] = request
        return request
    }

    /** 只看不动，用于展示；过期的当作不存在，但不在这里清（读方法不改状态） */
    fun peek(sessionId: UUID, kind: GameRequestKind, now: Long): GameRequest? =
        pending[sessionId]?.get(kind)?.takeIf { !it.isExpired(now) }

    /**
     * 答复一条请求。
     *
     * 无论判定结果如何，只要表里那条已经**不可用**（过期）或**已被取走**（同意/拒绝），
     * 都会把它从表里摘掉：留着过期垃圾会让后续 [open] 多绕一次判定，
     * 也会让 [purgeExpired] 的工作量凭空增加。
     *
     * 注意本方法只回答"这条请求能不能被这个人答复"，**不回答"答复之后那个动作还成不成立"**。
     * 悔棋尤其要小心：请求发出后对手又走了一手，此时撤销撤掉的会是对手刚走那手而不是
     * 发起方的那手。调用方必须在拿到 ALLOWED 之后按当前局面**重新**过一遍
     * [GameRequestDecision.undoEligibility]，这与菜单点击时重读状态是同一个原则。
     */
    fun answer(
        sessionId: UUID,
        kind: GameRequestKind,
        responder: Side,
        now: Long,
    ): AnswerResult {
        val slots = pending[sessionId]
        val existing = slots?.get(kind)
        val verdict = GameRequestDecision.answer(existing, responder, now)
        if (verdict == GameRequestDecision.AnswerVerdict.ALLOWED ||
            verdict == GameRequestDecision.AnswerVerdict.EXPIRED
        ) {
            slots?.remove(kind)
            dropIfEmpty(sessionId)
        }
        return AnswerResult(verdict, existing?.takeIf { verdict == GameRequestDecision.AnswerVerdict.ALLOWED })
    }

    /**
     * 清掉一局的全部请求。
     *
     * 终局、收棋盘、开局、离座这几处都要调：局面变了之后旧请求全都对不上，
     * 而 session id 若不清会一直留在表里（棋盘都收了，那条求和还挂着）。
     */
    fun clear(sessionId: UUID) {
        pending.remove(sessionId)
    }

    /** 全部清空，关服与配置重载用 */
    fun clearAll() {
        pending.clear()
    }

    /**
     * 清掉所有已过期请求，挂在每秒巡检上。
     *
     * 判定本身已经把过期请求当作不存在，所以这个清理**不影响正确性**，
     * 只是防止表随对局数无限长下去（每局最多两条，但棋盘可以有很多副）。
     * 表空时直接返回，不空转。
     */
    fun purgeExpired(now: Long) {
        if (pending.isEmpty()) {
            return
        }
        val emptied = ArrayList<UUID>()
        pending.forEach { (sessionId, slots) ->
            slots.values.removeIf { it.isExpired(now) }
            if (slots.isEmpty()) {
                emptied += sessionId
            }
        }
        emptied.forEach(pending::remove)
    }

    /** 表里一条请求都没有；巡检据此短路 */
    fun isEmpty(): Boolean = pending.isEmpty()

    /** 某局当前挂着几条请求，单测用 */
    fun countOf(sessionId: UUID): Int = pending[sessionId]?.size ?: 0

    /** 某局的槽位空了就把整个条目摘掉，别留空壳 */
    private fun dropIfEmpty(sessionId: UUID) {
        if (pending[sessionId]?.isEmpty() == true) {
            pending.remove(sessionId)
        }
    }

    /**
     * 答复结果。
     *
     * [request] 只在 [GameRequestDecision.AnswerVerdict.ALLOWED] 时非空 ——
     * 调用方拿到它才知道是谁发起的（要给发起方回一条"对手同意了"）。
     */
    data class AnswerResult(
        val verdict: GameRequestDecision.AnswerVerdict,
        val request: GameRequest?,
    )
}
