package linmumua.chess.config

/**
 * config.yml → [ChessSettings] 的解析器。
 *
 * 输入是 [Yaml.loadMap] 产出的原始嵌套 Map，不碰文件系统也不碰 Bukkit，
 * 因此可以直接单测"服主写了半截配置"这类情况。
 *
 * 解析策略是**逐项容错**：某一项类型写错或缺失就退回默认值，
 * 不因为一个拼写错误让整份配置作废。真正需要炸出来的是持久化数据（boards.yml），
 * 那部分在各自的读取处处理。
 */
object ChessConfigLoader {

    /** 走子总时长下限。少于 2 帧就没有起落，弧线退化成一次瞬移 */
    private const val MIN_MOVE_TICKS = 2

    /** 走子总时长上限，3 秒一步已经远超真人节奏，再长只会让人以为卡住了 */
    private const val MAX_MOVE_TICKS = 60

    /** 弧线顶点高度上限，超过一格棋子看上去就飞离棋盘了 */
    private const val MAX_MOVE_LIFT_HEIGHT = 1.0

    /**
     * 悬浮字各项尺寸的统一下界。
     *
     * 缩放、可见倍率、判定框宽高填 0 的后果都一样：这行字看不见或点不到，
     * 也就是入座与开局的入口直接失效。共用一个下界而不是各写一遍，
     * 是因为它们的理由完全相同 —— 都不能为 0。
     */
    private const val MIN_LABEL_SCALE = 0.1

    /** 悬浮字文字缩放上限，原版 1.0 已能盖住整个 2x2 棋盘，3 倍是留给远距离展示用的余量 */
    private const val MAX_LABEL_SCALE = 3.0

    /**
     * 悬浮字离棋盘面的高度下限。
     *
     * 与 [MIN_LABEL_SCALE] 数值相同但**语义不同**，所以单独给一个常量：那个是缩放倍数，
     * 这个是方块高度。共用会让人以为改一处就能同时调两者。
     *
     * 不能沿用 0.0：棋盘家具本身有厚度，字贴在棋盘面上就埋进模型里了，
     * 既看不见也点不到 —— 与填负数的后果完全一样。0.1 格约在棋盘面上方一点，
     * 足够让字露出来。
     */
    private const val MIN_LABEL_Y_OFFSET = 0.1

    /** 悬浮字离棋盘面的高度上限，再高就飘到玩家头顶之上，认不出属于哪副棋盘 */
    private const val MAX_LABEL_Y_OFFSET = 5.0

    /** 可见距离倍率上限。这是倍率不是格数，4 已对应约 256 格，超过多数服的实体渲染距离 */
    private const val MAX_LABEL_VIEW_RANGE = 4.0

    /** 判定框边长上限，再大会挡住棋盘边上的棋子，让人选不中边角的子 */
    private const val MAX_LABEL_HITBOX = 4.0

    /** 按原始映射解析出配置，缺项走 [ChessSettings] 的默认值 */
    fun parse(root: Map<String, Any?>): ChessSettings {
        val defaults = ChessSettings.DEFAULT
        return ChessSettings(
            debug = root.bool("debug", defaults.debug),
            craftEngine = parseCraftEngine(root.section("craftengine"), defaults.craftEngine),
            checkmateTotem = parseCheckmateTotem(root.section("checkmate-totem"), defaults.checkmateTotem),
            board = parseBoard(root.section("board"), defaults.board),
            piece = parsePiece(root.section("piece"), defaults.piece),
            highlight = parseHighlight(root.section("highlight"), defaults.highlight),
            boardLabel = parseBoardLabel(root.section("board-label"), defaults.boardLabel),
            game = parseGame(root.section("game"), defaults.game),
            sound = parseSound(root.section("sound"), defaults.sound),
            captureExplosion = parseCaptureExplosion(
                root.section("capture-explosion"),
                defaults.captureExplosion,
            ),
            storage = parseStorage(root.section("storage"), defaults.storage),
            bot = parseBot(root.section("bot"), defaults.bot),
            gomoku = parseGomoku(root.section("gomoku"), defaults.gomoku),
            go = parseGo(root.section("go"), defaults.go),
        )
    }

    private fun parseCraftEngine(node: Map<String, Any?>, defaults: CraftEngineSettings) =
        CraftEngineSettings(
            autoGeneratePack = node.bool("auto-generate-pack", defaults.autoGeneratePack),
            autoReload = node.bool("auto-reload", defaults.autoReload),
            skipIfUnchanged = node.bool("skip-if-unchanged", defaults.skipIfUnchanged),
        )

    private fun parseCheckmateTotem(node: Map<String, Any?>, defaults: CheckmateTotemSettings) =
        CheckmateTotemSettings(
            red = node.string("red", defaults.red),
            black = node.string("black", defaults.black),
        )

    private fun parseBoard(node: Map<String, Any?>, defaults: BoardSettings) =
        BoardSettings(
            // 上限至少为 1，填 0 或负数等于禁止放置，那是配置错误而不是有意义的设置
            maxPerWorld = node.int("max-per-world", defaults.maxPerWorld).coerceAtLeast(1),
            requireFlatArea = node.bool("require-flat-area", defaults.requireFlatArea),
            surfaceOffset = node.double("surface-offset", defaults.surfaceOffset),
            refundOnBreak = node.bool("refund-on-break", defaults.refundOnBreak),
        )

    private fun parsePiece(node: Map<String, Any?>, defaults: PieceSettings): PieceSettings {
        val hoverHeight = node.double("hover-height", defaults.hoverHeight)
        return PieceSettings(
            // 缩放必须为正，否则显示实体会不可见或翻面
            baseScale = node.double("base-scale", defaults.baseScale).coerceAtLeast(0.01),
            selectedScale = node.double("selected-scale", defaults.selectedScale).coerceAtLeast(0.01),
            hoverHeight = hoverHeight,
            animationTicks = node.int("animation-ticks", defaults.animationTicks).coerceAtLeast(0),
            // 弧线至少要 2 帧才有起落，上限 60 是怕服主配出一个几秒的输入锁定
            moveTicks = node.int("move-ticks", defaults.moveTicks)
                .coerceIn(MIN_MOVE_TICKS, MAX_MOVE_TICKS),
            // 弧线顶点低于浮起高度会让选中的棋子走子时先往下沉，所以取两者较大值
            moveLiftHeight = node.double("move-lift-height", defaults.moveLiftHeight)
                .coerceIn(hoverHeight, MAX_MOVE_LIFT_HEIGHT),
        )
    }

    private fun parseHighlight(node: Map<String, Any?>, defaults: HighlightSettings) =
        HighlightSettings(
            // 缩放必须为正，否则合法空点的白圈会完全不可见
            markerScale = node.double("marker-scale", defaults.markerScale).coerceAtLeast(0.01),
            // 只允许向上增加间隙，负数会让白圈沉进棋盘模型
            markerYOffset = node.double("marker-y-offset", defaults.markerYOffset).coerceAtLeast(0.0),
            markCheckedKing = node.bool("mark-checked-king", defaults.markCheckedKing),
        )

    /**
     * 棋盘上方可点击悬浮字的参数。
     *
     * 这一段的钳制都是为了"配错了也不能让按钮消失或点不到"：
     * 悬浮字是入座与开局最显眼的入口，它失效虽然还有箱子菜单兜着，但症状是
     * "点了没反应"，服主很难联想到是自己填的某个数字。所以宁可忽略越界值。
     */
    private fun parseBoardLabel(node: Map<String, Any?>, defaults: BoardLabelSettings) =
        BoardLabelSettings(
            enabled = node.bool("enabled", defaults.enabled),
            // 0 与负数会让字沉进棋盘模型，既看不见也点不到，所以下界不是 0 而是
            // MIN_LABEL_Y_OFFSET；上界 5 格，再高玩家就不会把这行字和脚下这副棋盘联系起来了
            yOffset = node.double("y-offset", defaults.yOffset)
                .coerceIn(MIN_LABEL_Y_OFFSET, MAX_LABEL_Y_OFFSET),
            // 缩放必须为正，给 0 文字直接不可见；上界 3 倍，原版 1.0 已经能盖住整个 2x2 棋盘
            scale = node.double("scale", defaults.scale)
                .coerceIn(MIN_LABEL_SCALE, MAX_LABEL_SCALE),
            // 这是倍率不是格数（实际距离约为 倍率 × 64 格）。给 0 等于永远不渲染，
            // 所以下界同样不能放过；上界 4 对应约 256 格，已经超过多数服的实体渲染距离
            viewRange = node.double("view-range", defaults.viewRange)
                .coerceIn(MIN_LABEL_SCALE, MAX_LABEL_VIEW_RANGE),
            // 判定框任一边为 0 就点不到，按钮等于失效；上界 4 格是怕判定框大到挡住
            // 棋盘边上的棋子，让玩家没法选边角的子
            hitboxWidth = node.double("hitbox-width", defaults.hitboxWidth)
                .coerceIn(MIN_LABEL_SCALE, MAX_LABEL_HITBOX),
            hitboxHeight = node.double("hitbox-height", defaults.hitboxHeight)
                .coerceIn(MIN_LABEL_SCALE, MAX_LABEL_HITBOX),
        )

    private fun parseGame(node: Map<String, Any?>, defaults: GameSettings) =
        GameSettings(
            drawMoveLimit = node.int("draw-move-limit", defaults.drawMoveLimit).coerceAtLeast(0),
            turnTimeoutSeconds = node.int("turn-timeout-seconds", defaults.turnTimeoutSeconds).coerceAtLeast(0),
            allowUndo = node.bool("allow-undo", defaults.allowUndo),
            spectatorCanClick = node.bool("spectator-can-click", defaults.spectatorCanClick),
        )

    private fun parseSound(node: Map<String, Any?>, defaults: SoundSettings) =
        SoundSettings(
            move = node.string("move", defaults.move),
            capture = node.string("capture", defaults.capture),
            check = node.string("check", defaults.check),
            gameOver = node.string("game-over", defaults.gameOver),
            select = node.string("select", defaults.select),
        )

    private fun parseCaptureExplosion(
        node: Map<String, Any?>,
        defaults: CaptureExplosionSettings,
    ) = CaptureExplosionSettings(
        enabled = node.bool("enabled", defaults.enabled),
        sound = node.string("sound", defaults.sound),
        // 音量夹在 0..1：负数没有意义，超过 1 会让一枚棋子的爆炸传到很远，盖掉落子声
        volume = node.double("volume", defaults.volume).coerceIn(0.0, 1.0),
        // 半径必须为正，否则粒子全叠在一个点上看不出是爆炸；上限一格，再大就不像棋子尺寸了
        radius = node.double("radius", defaults.radius).coerceIn(0.01, 1.0),
    )

    private fun parseStorage(node: Map<String, Any?>, defaults: StorageSettings) =
        StorageSettings(
            // 写盘间隔至少 5 秒，避免服主填 0 导致每 tick 落盘
            saveIntervalSeconds = node.int("save-interval-seconds", defaults.saveIntervalSeconds)
                .coerceAtLeast(5),
        )

    private fun parseBot(node: Map<String, Any?>, defaults: BotSettings) =
        BotSettings(
            // 允许 0（立刻落子，调试时想快速跑完一局会用到），但不允许负数
            thinkTicks = node.int("think-ticks", defaults.thinkTicks).coerceAtLeast(0),
            // 上界 6：象棋分支约 40，每加一层乘 40，再深就算不完了
            depth = node.int("depth", defaults.depth).coerceIn(1, 6),
            // 上界 4：半径再大候选点数量爆炸，而五子棋的价值是局部的
            candidateRadius = node.int("candidate-radius", defaults.candidateRadius).coerceIn(1, 4),
            // 下界 4：截得太狠会把必要的防守点挤掉（必胜必防手另有兜底，但活三级别的防守靠候选）
            maxCandidates = node.int("max-candidates", defaults.maxCandidates).coerceIn(4, 120),
            // 允许 0（不限时，离线测试用），上界 5 秒 —— 再长玩家会以为插件卡了
            thinkTimeLimitMillis = node.long("think-time-limit-ms", defaults.thinkTimeLimitMillis)
                .coerceIn(0L, 5000L),
        )

    private fun parseGomoku(node: Map<String, Any?>, defaults: GomokuSettings) =
        GomokuSettings(
            // 上界 0.14：再大相邻棋子就会压边（格点间距 0.126，圆盘占贴图 92%）
            baseScale = node.double("base-scale", defaults.baseScale).coerceIn(0.01, 0.14),
            forbiddenMovesEnabled = node.bool("forbidden-moves", defaults.forbiddenMovesEnabled),
        )

    private fun parseGo(node: Map<String, Any?>, defaults: GoSettings) =
        GoSettings(
            // 上界 0.16：围棋格点间距 ≈0.153，再大相邻子压边
            baseScale = node.double("base-scale", defaults.baseScale).coerceIn(0.01, 0.16),
            // 贴子下界 0（不贴），上界给 20 子：够覆盖任何让子局的约定，
            // 再大就不是贴子而是配置填错了，夹住比让终局分数离谱更好排查
            komi = node.double("komi", defaults.komi).coerceIn(0.0, 20.0),
            // 上界 60：19 路上再多必然超时，逐层加深会退化成只有第 1 层完整，等于白配
            botMaxCandidates = node.int("bot-max-candidates", defaults.botMaxCandidates)
                .coerceIn(4, 60),
            openingBgm = parseGoBgm(node.section("opening-bgm"), defaults.openingBgm),
        )

    private fun parseGoBgm(node: Map<String, Any?>, defaults: GoBgmSettings) =
        GoBgmSettings(
            enabled = node.bool("enabled", defaults.enabled),
            // 留空是有意义的（等同关闭），所以不把空串当缺失，交给播放侧判断
            sound = node.string("sound", defaults.sound),
            // 上界 1.0：MC 的 playSound 对 volume>1 的处理是放大**可听距离**而不是音量，
            // 服主填 5 只会让隔一片地的人被迫听棋，不会让棋盘边更响
            volume = node.double("volume", defaults.volume).coerceIn(0.0, 1.0),
            // 只保证下界 1 秒：这里防的是 0 或负数把每秒巡检变成"每秒重播一次"。
            // 不按自带曲长（66 秒）设下界，否则服主换成 20 秒的短曲就永远播不满。
            // 周期与曲长匹配是服主的责任，配置注释已写明改小会叠播。
            loopPeriodSeconds = node.int("loop-period-seconds", defaults.loopPeriodSeconds)
                .coerceAtLeast(1),
        )
}

/** 取子映射，缺失或类型不对时给空表，让下游统一走默认值 */
@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.section(key: String): Map<String, Any?> =
    this[key] as? Map<String, Any?> ?: emptyMap()

/** 取布尔值，允许服主写成字符串 `true` */
internal fun Map<String, Any?>.bool(key: String, fallback: Boolean): Boolean =
    when (val value = this[key]) {
        is Boolean -> value
        is String -> value.toBooleanStrictOrNull() ?: fallback
        else -> fallback
    }

/** 取整数，允许服主写成字符串数字 */
internal fun Map<String, Any?>.int(key: String, fallback: Int): Int =
    when (val value = this[key]) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull() ?: fallback
        else -> fallback
    }

/** 取长整数，允许服主写成字符串。毫秒级时限用它，避免 Int 上限带来的意外 */
internal fun Map<String, Any?>.long(key: String, fallback: Long): Long =
    when (val value = this[key]) {
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull() ?: fallback
        else -> fallback
    }

/** 取浮点数，允许服主写成字符串数字 */
internal fun Map<String, Any?>.double(key: String, fallback: Double): Double =
    when (val value = this[key]) {
        is Number -> value.toDouble()
        is String -> value.trim().toDoubleOrNull() ?: fallback
        else -> fallback
    }

/**
 * 取字符串。
 *
 * 空字符串是**有意义的值**（音效项留空表示不播放），所以不把空串当缺失处理。
 */
internal fun Map<String, Any?>.string(key: String, fallback: String): String =
    when (val value = this[key]) {
        is String -> value
        null -> fallback
        else -> value.toString()
    }
