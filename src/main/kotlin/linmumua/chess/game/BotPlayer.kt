package linmumua.chess.game

import java.util.UUID
import linmumua.chess.rule.core.Side

/**
 * 测试机器人的座位登记。
 *
 * 只管"哪个会话的哪一方是机器人"，不碰走法（走法在 [linmumua.chess.game.bot.BotBrain]）、
 * 也不碰渲染与落子（落子在 MoveExecutor）。
 *
 * 为什么用合成 UUID 占座位：座位在 [ChessGame] 里就是 `UUID?`，
 * 塞一个稳定的假 UUID 进去就能复用全部现成逻辑 —— 轮次、走子校验、认输、离座。
 * 为了一个测试功能去改规则/对局层的座位模型不值得。
 *
 * 这个假 UUID 不对应任何在线玩家，`Bukkit.getPlayer(它)` 返回 null，
 * 而 [GameSession.audience] 用的是 `mapNotNull`，所以播报会自然跳过它，不会 NPE。
 */
object BotPlayer {

    /** UUID 的生成前缀，保证同一方每次算出同一个 UUID，重启后也一致 */
    private const val SEED_PREFIX = "muchess:bot:"

    /**
     * 每方一个固定 UUID。
     *
     * 两方各自独立，是为了支持"机器人对机器人"这种自测场景 ——
     * 如果两方共用一个 UUID，`sideOf` 就分不清了。
     */
    private val ids: Map<Side, UUID> = Side.entries.associateWith { side ->
        // 种子固定用 red/black 而不是 Side 的枚举名：这个 UUID 会作为座位占用者
        // 落到 boards.yml 里，跟着枚举名走会让已存档的机器人座位对不上人。
        val slug = if (side == Side.FIRST) "red" else "black"
        UUID.nameUUIDFromBytes((SEED_PREFIX + slug).toByteArray())
    }

    /** 已登记机器人的会话：会话 id → 该会话里由机器人执的那些方 */
    private val seats = HashMap<UUID, MutableSet<Side>>()

    /** 取某一方的机器人 UUID */
    fun uuidOf(side: Side): UUID = ids.getValue(side)

    /** 该 UUID 是否机器人（任意一方） */
    fun isBot(uuid: UUID): Boolean = ids.containsValue(uuid)

    /** 某会话的某一方当前是不是机器人 */
    fun isBotSeat(sessionId: UUID, side: Side): Boolean =
        seats[sessionId]?.contains(side) == true

    /** 登记：某会话的某一方交给机器人 */
    fun register(sessionId: UUID, side: Side) {
        seats.getOrPut(sessionId) { HashSet() } += side
    }

    /**
     * 注销某会话的某一方。
     *
     * @return 之前是否登记过，用于命令层区分"撤下了"和"本来就没有"
     */
    fun unregister(sessionId: UUID, side: Side): Boolean {
        val sides = seats[sessionId] ?: return false
        val removed = sides.remove(side)
        if (sides.isEmpty()) {
            seats.remove(sessionId)
        }
        return removed
    }

    /** 某会话里由机器人执的所有方，会话被收走时用来批量清理 */
    fun sidesOf(sessionId: UUID): Set<Side> = seats[sessionId]?.toSet() ?: emptySet()

    /** 清掉某会话的全部登记，棋盘被收走或对局关闭时调用 */
    fun clearSession(sessionId: UUID) {
        seats.remove(sessionId)
    }

    /**
     * 清空全部登记。
     *
     * 关服时调用。机器人是纯运行时状态，不落盘也不恢复 ——
     * 重启后座位上留着一个不存在的"玩家"比没有机器人更糟。
     */
    fun clearAll() {
        seats.clear()
    }
}
