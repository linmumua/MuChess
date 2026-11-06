package linmumua.chess.game

import java.util.UUID
import linmumua.chess.config.ChessConfig
import linmumua.chess.config.GoBgmSettings
import org.bukkit.SoundCategory

/**
 * 围棋开局背景音乐的播放侧。
 *
 * 判断全在 [GoBgmDecision]，这里只负责三件带副作用的事：发播放包、发停止包、记住"这局放到哪了"。
 *
 * 为什么要巡检而不是开局时播一次：MC 客户端不支持让一个声音事件自己循环，
 * 播一次就只有一遍（自带曲子 65.55 秒），一局围棋半小时以上，必须到点重播。
 * 巡检挂在 `ChessPlugin.tickSessions`（每秒），没有围棋在下时 [tick] 立刻短路返回。
 *
 * 播放范围是**棋盘周围**而不是"发给对局双方"：用 `World#playSound(Location, …)` 定位播放，
 * 客户端按距离衰减，路过的人也能听到一点，走远就没了。这比给玩家本人耳边播更像
 * "这张棋盘在放音乐"，也省掉了"观战者在两千格外还听得到"的怪事。
 */
object GoBgmController {

    /**
     * 发停止包的搜索半径，格。
     *
     * 音量 ≤1 时 MC 的定位音效可听范围是 16 格，取 64 是留足余量：
     * 玩家可能在曲子开始后走开又走回来，宁可多发几个没必要的停止包，
     * 也不要让某个人的客户端把音乐一直放到终局播报上面。
     */
    private const val STOP_RADIUS = 64.0

    /**
     * 棋盘 id → 下次该重播的时间戳（毫秒）。
     *
     * 键在表里就代表"这局有一遍曲子正在放"，[GoBgmDecision.decide] 的 `playing` 就是它。
     * 纯运行时状态，不落盘：重启后客户端的音乐早断了，存下来只会让人以为还在放。
     */
    private val nextPlayAt = HashMap<UUID, Long>()

    /**
     * 每秒巡检一次，该起播的起播，该重播的重播，该停的停。
     *
     * 不空转的保证：总闸关着且没有任何一局在放时直接返回，不遍历会话。
     * 总闸关着但表里还有记录的情况是服主刚 reload 把开关关掉 —— 那要走完循环把音乐停掉。
     */
    fun tick() {
        val bgm = ChessConfig.settings.go.openingBgm
        if (!bgm.enabled && nextPlayAt.isEmpty()) {
            return
        }
        val now = System.currentTimeMillis()
        val sessions = GameManager.allSessions()
        for (session in sessions) {
            val eligible = GoBgmDecision.eligible(
                globalEnabled = bgm.enabled,
                sessionEnabled = session.bgmEnabled,
                isGo = session.gameType == GameType.GO,
                phase = session.game.phase,
                sound = bgm.sound,
                volume = bgm.volume,
            )
            val action = GoBgmDecision.decide(
                eligible = eligible,
                playing = nextPlayAt.containsKey(session.id),
                nowMillis = now,
                nextPlayAtMillis = nextPlayAt[session.id] ?: 0L,
            )
            when (action) {
                GoBgmAction.PLAY -> {
                    play(session, bgm)
                    nextPlayAt[session.id] = GoBgmDecision.nextPlayAt(now, bgm.loopPeriodSeconds)
                }
                GoBgmAction.STOP -> stop(session)
                // 在放且周期没到，或本来就不该放：这两种都不能发包，否则会叠音或白刷网络
                GoBgmAction.WAIT, GoBgmAction.IDLE -> Unit
            }
        }
        // 棋盘被收掉走 GameSession.close，那条路已经清了记录；这里兜的是会话被整批清空
        // （切换存档、reload 重建）的情况，不清会让表里长期攥着一批不存在的 id
        if (nextPlayAt.isNotEmpty() && nextPlayAt.size > sessions.size) {
            val alive = sessions.mapTo(HashSet(sessions.size)) { it.id }
            nextPlayAt.keys.retainAll(alive)
        }
    }

    /**
     * 停掉这局的背景音乐。
     *
     * 两个调用点：
     * - `MoveExecutor.announceResult` 开头 —— 终局那一刻要先掐音乐再播 `game-over` 音效，
     *   两声叠着谁也听不清。挂在播报入口是因为六条终局路径都汇到那里。
     * - [GameSession.close] —— 收棋盘与关服的兜底，关服路径经 `GameManager.shutdown` 走到这。
     *
     * 没有播放记录就直接返回：没在放还发停止包纯属浪费，也会误停服主自己用别的插件放的音乐。
     */
    fun stop(session: GameSession) {
        if (nextPlayAt.remove(session.id) == null) {
            return
        }
        val sound = ChessConfig.settings.go.openingBgm.sound
        if (sound.isBlank()) {
            return
        }
        // 停的是"配置里现在这首"。服主在一局中途改了 id 的话会停不掉旧曲，那就等它自己放完 ——
        // 为这种情况把 id 也存一份不值得，配置改动本来就要求 reload 后生效
        val center = session.anchor.centerLocation()
        val radiusSquared = STOP_RADIUS * STOP_RADIUS
        for (player in session.anchor.world.players) {
            if (player.location.distanceSquared(center) <= radiusSquared) {
                player.stopSound(sound, SoundCategory.MUSIC)
            }
        }
    }

    /** 在棋盘中心播一遍曲子。走 MUSIC 分类，玩家能在客户端单独把它调小而不影响落子声 */
    private fun play(session: GameSession, bgm: GoBgmSettings) {
        val center = session.anchor.centerLocation()
        // 声音 id 用字符串：客户端没有这个事件（CE 缺失、资源包没下发）时静默无声，不报错
        session.anchor.world.playSound(
            center,
            bgm.sound,
            SoundCategory.MUSIC,
            bgm.volume.toFloat(),
            1f,
        )
    }
}
