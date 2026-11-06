package linmumua.chess.game

import linmumua.chess.config.ChessConfig
import linmumua.chess.config.Msg
import taboolib.common.platform.function.warning
import taboolib.platform.util.asLangTextOrNull

/**
 * 限时对局的读秒动作栏。
 *
 * 每秒把"还剩几秒"刷到整桌人的动作栏上。挂在 `ChessPlugin.tickSessions`（每秒一次），
 * 排在超时判定之后 —— 判负那一刻对局已进入 FINISHED，[ChessGame.remainingSeconds]
 * 随即返回 null，所以不会出现"终局播报旁边还挂着 0 秒"的画面。
 *
 * 为什么走动作栏而不是聊天栏或 BossBar：
 * - 聊天栏每秒一行会把终局播报和命令回执直接冲掉。
 * - BossBar 要额外维护"谁看得见、什么时候撤"的生命周期，而动作栏发完就自动淡出，
 *   插件重载或玩家走开都不会留下残留 UI，与本项目"关服不留痕"的要求一致。
 *
 * 不限时的对局一个包都不发：[ChessGame.remainingSeconds] 返回 null 时直接跳过，
 * 动作栏保持空白，不会占着那一行显示"不限时"。
 */
object TurnCountdown {

    /**
     * 缺键日志的一次性开关。
     *
     * 读秒每秒都要取文案，一旦服主的语言文件缺了某条键，走 [Msg.actionBar] 会
     * 每秒往控制台写一条警告，一局下来上千行。所以这里自己先探一次键：
     * 缺键就静默跳过这一档并只在首次记一条日志，与围棋 BGM 按钮"缺键则该功能留空"
     * 的处理方式一致 —— 少一行读秒不该拖垮整局，更不该拖垮控制台。
     */
    private var missingKeyReported = false

    /**
     * 每秒刷一次读秒。
     *
     * 不空转的保证：没有棋盘时直接返回；有棋盘但都不限时或都没在下时，
     * 每条会话只做一次 [ChessGame.remainingSeconds] 计算就跳过，不取观众列表。
     */
    fun tick() {
        if (GameManager.isEmpty()) {
            return
        }
        val globalTimeout = ChessConfig.settings.game.turnTimeoutSeconds
        val now = System.currentTimeMillis()
        for (session in GameManager.allSessions()) {
            val game = session.game
            val timeout = game.effectiveTimeoutSeconds(globalTimeout)
            // null 覆盖三种"不该读秒"：不限时、没开局、开局后还没走第一步
            val remaining = game.remainingSeconds(now, timeout) ?: continue
            val sideToMove = game.sideToMove
            for (player in session.audience()) {
                val key = TurnCountdownDecision.key(
                    viewerSide = session.sideOf(player.uniqueId),
                    sideToMove = sideToMove,
                    remainingSeconds = remaining,
                )
                if (player.asLangTextOrNull(key) == null) {
                    reportMissingKey(key)
                    continue
                }
                Msg.actionBar(
                    player,
                    key,
                    "seconds" to remaining,
                    // 观战者那条要报是谁在走；双方那两条不带这个占位符，多传的会被忽略
                    "side" to Msg.sideName(player, sideToMove, session.gameType),
                )
            }
        }
    }

    /** 缺键只报一次，避免每秒一条把控制台刷满 */
    private fun reportMissingKey(key: String) {
        if (missingKeyReported) {
            return
        }
        missingKeyReported = true
        warning("语言文件缺少读秒键 $key，本次启动内不再提示，读秒动作栏已跳过")
    }
}
