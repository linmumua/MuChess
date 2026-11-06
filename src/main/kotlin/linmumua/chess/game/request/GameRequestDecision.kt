package linmumua.chess.game.request

import linmumua.chess.rule.core.Side

/**
 * 求和与悔棋请求的纯判定。
 *
 * 与 [linmumua.chess.game.TurnCountdownDecision]、[linmumua.chess.render.OrphanDecision]
 * 同一套路：判断条件全部由参数传入，不读全局状态、不碰 Bukkit，因此可以直接单测。
 * [GameRequests] 只负责存取那张表，"能不能发""能不能答"一律问这里。
 *
 * 这样拆的实际收益是**边界条件能被测到**。过期与重复发起是两个很容易写错的地方：
 * 过期用 `>` 还是 `>=`、请求已过期时该报"没有请求"还是"请求已过期"，
 * 混在带状态的登记表里只能靠肉眼核对。
 */
object GameRequestDecision {

    /**
     * 请求默认存活多少秒。
     *
     * 30 秒是"对手来得及读完提示并点一下"与"不至于让人一直挂着"之间的取舍：
     * 求和与悔棋都是插在行棋节奏里的打断，挂太久会让被征询方忘了自己还欠一个答复，
     * 而对局本身还在走超时倒计时。
     *
     * 不做成配置项：这个值不影响规则正确性，多一个配置键就多一处要写注释、要核对默认值的地方。
     */
    const val DEFAULT_TTL_SECONDS = 30

    /** 能不能发起一条新请求 */
    enum class OpenVerdict {
        /** 可以发 */
        ALLOWED,

        /** 同类型还有一条没答复的，别刷 */
        PENDING,
    }

    /** 能不能答复现有请求 */
    enum class AnswerVerdict {
        /** 可以答 */
        ALLOWED,

        /** 压根没有这类请求 */
        NONE,

        /** 有，但已经过期了 */
        EXPIRED,

        /** 这条是自己发的，等对手答 */
        OWN_REQUEST,
    }

    /** 悔棋特有的前置条件，与"有没有待处理请求"无关 */
    enum class UndoVerdict {
        /** 可以请求悔棋 */
        ALLOWED,

        /** 一手都没走，没什么可撤 */
        NO_HISTORY,

        /** 最近一手不是自己走的 */
        NOT_OWN_MOVE,
    }

    /**
     * 判断能否发起新请求。
     *
     * 已过期的旧请求**不算占位**：过期请求在表里只是还没被清掉的垃圾，
     * 让它挡住新请求等于"对手不理你，你 30 秒内连再问一次都不行"。
     * 巡检清理是兜底，判定这里必须自己再看一遍时间，不能依赖清理的及时性。
     */
    fun open(existing: GameRequest?, now: Long): OpenVerdict =
        if (existing != null && !existing.isExpired(now)) OpenVerdict.PENDING else OpenVerdict.ALLOWED

    /**
     * 判断 [responder] 能否答复现有请求。
     *
     * 过期与不存在分成两种结果，是为了让文案能说准话：玩家点了聊天里的 [同意]
     * 却慢了两秒，该告诉他"请求已过期"，而不是"没有待处理的请求"——
     * 后者会让人以为自己点错了地方，前者才会让人知道再让对手发一次就行。
     *
     * 自己发的请求自己不能答：否则求和变成单方面判和。这条必须在这里挡住，
     * 因为可点击提示是**发给整局在场的人**的（发起方自己也会看到那行字）。
     */
    fun answer(existing: GameRequest?, responder: Side, now: Long): AnswerVerdict {
        if (existing == null) {
            return AnswerVerdict.NONE
        }
        if (existing.isExpired(now)) {
            return AnswerVerdict.EXPIRED
        }
        if (existing.requester == responder) {
            return AnswerVerdict.OWN_REQUEST
        }
        return AnswerVerdict.ALLOWED
    }

    /**
     * 判断 [requester] 现在能不能请求悔棋。
     *
     * 悔棋撤的是**自己刚下的那一手**，不是对手的。走完一手后轮次切到对手，
     * 所以"最近一手的走子方"恒等于 `sideToMove.opposite`；轮到自己走时发起悔棋，
     * 撤掉的会是对手那一手，那是"要求对手重下"，不是悔棋。
     *
     * 这条挡在请求发出**之前**而不是同意之后：让对手先答应再发现撤不了，
     * 白白打断了一次对局节奏。
     *
     * @param sideToMove 当前该走棋的一方
     * @param moveCount 已走手数
     */
    fun undoEligibility(requester: Side, sideToMove: Side, moveCount: Int): UndoVerdict {
        if (moveCount <= 0) {
            return UndoVerdict.NO_HISTORY
        }
        if (sideToMove == requester) {
            return UndoVerdict.NOT_OWN_MOVE
        }
        return UndoVerdict.ALLOWED
    }

    /** 按存活时长算出过期时刻，供 [GameRequests] 造请求时用 */
    fun expiryOf(now: Long, ttlSeconds: Int = DEFAULT_TTL_SECONDS): Long =
        now + ttlSeconds * 1000L

    /**
     * 人机对局里，玩家悔棋要撤掉几手。
     *
     * 人机悔棋不走"请求—同意"：机器人没有同意的意志，把流程套上去再拒绝，
     * 等于凭空造一道墙。而练习棋恰恰最需要悔棋，走错一手只能认输重开是挡路的。
     *
     * 撤几手由局面算，不能写死 2。**硬性要求是撤完之后轮次必须落回玩家**：
     * 撤成机器人的轮次而没有任何东西去触发它走子，对局会卡死 ——
     * 玩家不能走（不是他的轮次），机器人也不会走（[linmumua.chess.interact.MoveExecutor.scheduleBotIfNeeded]
     * 只在落子后被调用，撤手不触发它）。撤手是严格 LIFO、每撤一手轮次翻转一次，于是：
     * - 轮到玩家走：末手是机器人下的，撤 2 手（机器人那手 + 玩家自己那手），翻转两次仍是玩家。
     * - 轮到机器人走：玩家刚落子、机器人还没走，末手是玩家自己的，撤 1 手即回到玩家。
     *
     * 返回 0 表示撤不了，两种情况都归到"没有你自己的手可撤"：
     * 一手未走，或机器人执先只走了一手而玩家还没落子过。对玩家来说要做的下一步
     * 是一样的（先走一手再说），不必分两条文案。
     *
     * @param playerSide 发起悔棋的玩家所在阵营
     * @param sideToMove 当前该走棋的一方
     * @param moveCount 已走手数
     */
    fun botUndoPlies(playerSide: Side, sideToMove: Side, moveCount: Int): Int {
        if (moveCount <= 0) {
            return 0
        }
        val plies = if (sideToMove == playerSide) 2 else 1
        return if (moveCount >= plies) plies else 0
    }
}
