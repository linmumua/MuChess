package linmumua.chess.game

/**
 * 围棋背景音乐巡检的一次判定结果。
 *
 * 分成四态而不是一个布尔"要不要播"，是因为"该播"与"该停"之间还有两种什么都不做的情形，
 * 混在一起会让巡检每秒重复发包：[WAIT] 是曲子还在放（不能重播，会叠音），
 * [IDLE] 是本来就没放（不能发停止包，白费网络）。
 */
enum class GoBgmAction {
    /** 起播，或上一遍放完了该重播 */
    PLAY,

    /** 曲子还在放，周期没到，什么都不做 */
    WAIT,

    /** 条件失效（终局、关闭开关、改配置），停掉正在放的曲子 */
    STOP,

    /** 本来没在放也不该放，什么都不做 */
    IDLE,
}

/**
 * 围棋背景音乐的判定逻辑。
 *
 * 独立成纯函数是为了能单测：这套判断的错法都是"静默错"——
 * 该停没停会让终局播报被音乐盖住，该等没等会让同一段音乐叠着放，
 * 两种都不抛异常，只能靠单测把状态机钉住。
 *
 * 本文件不允许出现 Bukkit 类型，播放与停止的实际发包在 [GoBgmController]。
 */
object GoBgmDecision {

    /**
     * 这局现在该不该有背景音乐。
     *
     * 六个条件全真才响，任一为假都当"不该响"：
     * - [globalEnabled]：`config.yml` 的总闸，默认关
     * - [sessionEnabled]：这一局自己的闸，玩家在对局菜单里点的那个
     * - [isGo]：只有围棋有开局音乐，象棋与五子棋一局几分钟，配长曲子反而吵
     * - [phase] 必须是 [GamePhase.PLAYING]：等人入座时不响，终局后也不响
     * - [sound] 非空：留空是"不播放"的正式写法（见 `GoBgmSettings.sound`）
     * - [volume] 大于 0：音量为 0 等于没声，不必发包
     */
    fun eligible(
        globalEnabled: Boolean,
        sessionEnabled: Boolean,
        isGo: Boolean,
        phase: GamePhase,
        sound: String,
        volume: Double,
    ): Boolean = globalEnabled &&
        sessionEnabled &&
        isGo &&
        phase == GamePhase.PLAYING &&
        sound.isNotBlank() &&
        volume > 0.0

    /**
     * 这一秒该做什么。
     *
     * [playing] 表示巡检记着这局有一遍曲子正在放（有下次重播时间就算在放）。
     * [nextPlayAtMillis] 只在 [playing] 为真时有意义。
     */
    fun decide(
        eligible: Boolean,
        playing: Boolean,
        nowMillis: Long,
        nextPlayAtMillis: Long,
    ): GoBgmAction = when {
        // 不该响：在放就停，没放就不动。这一条盖住终局、关开关、改配置三种情形
        !eligible -> if (playing) GoBgmAction.STOP else GoBgmAction.IDLE
        // 该响但没在放：起播
        !playing -> GoBgmAction.PLAY
        // 该响且在放：到点重播，没到点就等着
        nowMillis >= nextPlayAtMillis -> GoBgmAction.PLAY
        else -> GoBgmAction.WAIT
    }

    /**
     * 算下次该重播的时间戳。
     *
     * MC 客户端不能让一个声音事件自己循环，只能到点重播，所以周期必须略大于曲长，
     * 小了会叠音。周期的合理性由服主保证（配置注释已写明），这里只兜住 0 与负数 ——
     * 那会让巡检退化成每秒重播一次，一分钟叠六十遍。
     * 配置解析处已经夹过一次（`ChessConfigLoader.parseGoBgm`），这里是第二道，
     * 防的是绕过配置直接构造 `GoBgmSettings` 的调用方（单测、将来的命令）。
     */
    fun nextPlayAt(nowMillis: Long, periodSeconds: Int): Long =
        nowMillis + periodSeconds.coerceAtLeast(1) * 1000L
}
