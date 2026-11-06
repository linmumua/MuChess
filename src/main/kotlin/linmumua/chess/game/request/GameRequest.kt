package linmumua.chess.game.request

import linmumua.chess.rule.core.Side

/**
 * 需要征询对手同意的请求类型。
 *
 * [id] 是命令参数与语言键里用的短名（`/chess draw accept`、`request-draw-sent`），
 * 不要直接用 `name.lowercase()` 顶替：枚举名是代码标识，命令面与文案面一旦跟着枚举名走，
 * 以后重命名枚举就会静默改掉玩家要敲的命令。
 */
enum class GameRequestKind(val id: String) {

    /** 求和：双方同意则和棋收场 */
    DRAW("draw"),

    /** 悔棋：对手同意则撤回最近一手 */
    UNDO("undo"),
    ;

    companion object {

        /** 按命令参数取类型，认不出返回 null */
        fun ofId(id: String): GameRequestKind? {
            val lower = id.lowercase()
            return entries.firstOrNull { it.id == lower }
        }
    }
}

/**
 * 一条待对手答复的请求。
 *
 * 只存**发起方阵营**而不是发起方 UUID：请求的归属是"这一局的哪一边"，
 * 不是"哪个账号"。中途换人坐同一个座位（离座后另一人入座）这种情况下，
 * 按阵营判断才能让新入座者接得住上一手留下的请求，也让机器人接管座位时不必特殊处理。
 *
 * 目标方不单独存，它恒等于发起方的对面，见 [target]。冗余存一份的唯一后果是
 * 两个字段可能不一致，而不一致时没有任何办法判断哪个才对。
 *
 * 纯数据，不依赖 Bukkit，可直接单测。
 */
data class GameRequest(
    /** 请求类型 */
    val kind: GameRequestKind,
    /** 发起方阵营 */
    val requester: Side,
    /**
     * 过期时刻，毫秒时间戳。
     *
     * 存绝对时刻而不是"创建时刻 + 时长"：判断过期只需要与 now 比一次大小，
     * 不必每次再去读一遍配置里的时长。中途改配置也不会让已发出的请求突然变长或变短。
     */
    val expiresAt: Long,
) {

    /** 该由哪一方答复 */
    val target: Side get() = requester.opposite

    /**
     * 到 [now] 这一刻是否已过期。
     *
     * 用 `>=` 而不是 `>`：过期时刻那一毫秒算过期。差一毫秒的行为差异玩家察觉不到，
     * 但边界写成闭区间能让单测的断言点唯一 —— 测试可以直接用 expiresAt 当输入。
     */
    fun isExpired(now: Long): Boolean = now >= expiresAt
}
