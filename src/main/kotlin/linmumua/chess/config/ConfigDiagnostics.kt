package linmumua.chess.config

/**
 * 找出"服主写了，但没按写的生效"的配置项。
 *
 * 为什么需要它：解析器逐项容错 —— 类型写错就退默认、超出范围就钳到边界。
 * 这样插件不会因为一个拼写错误起不来，但代价是**服主完全看不出哪一项被忽略了**：
 * 文件里明明写着 `move-ticks: 1`，实际跑的是 2，而控制台一声不响。
 *
 * 这里不改任何解析行为，只做事后比对：拿磁盘上的原始值和最终生效值对一遍，
 * 不一致的逐条报出来。配置仍然完全以磁盘文件为准，插件只负责说清"这一项我没能照做"。
 *
 * 纯函数，不碰文件也不碰 Bukkit，可单测。
 */
internal object ConfigDiagnostics {

    /**
     * 一条"没照写生效"的记录。
     *
     * @param key 完整点分路径
     * @param written 服主在文件里写的原始文本
     * @param effective 实际生效的值
     * @param reason 为什么不一致
     */
    data class Ignored(
        val key: String,
        val written: String,
        val effective: String,
        val reason: String,
    )

    /** 不一致的原因 */
    enum class Reason(val text: String) {
        /** 类型不对，读不成目标类型 */
        UNREADABLE("值读不成对应类型，已退回默认值"),

        /** 超出允许范围，被钳到边界 */
        CLAMPED("超出允许范围，已钳到边界值"),
    }

    /**
     * 比对原始配置与生效配置。
     *
     * @param root 磁盘配置解析出的原始嵌套 Map
     * @param effective 最终生效的配置
     * @return 没照写生效的项；全部照写生效时返回空表
     */
    fun inspect(root: Map<String, Any?>, effective: ChessSettings): List<Ignored> {
        val out = ArrayList<Ignored>()

        // 只检查有钳制或有类型要求的数值项。布尔与字符串项的容错不改变语义，
        // 写错时退默认已经足够明显（音效不响、开关没生效），不必逐条刷日志。
        check(out, root, "piece", "move-ticks", effective.piece.moveTicks)
        check(out, root, "piece", "animation-ticks", effective.piece.animationTicks)
        check(out, root, "piece", "base-scale", effective.piece.baseScale)
        check(out, root, "piece", "selected-scale", effective.piece.selectedScale)
        check(out, root, "piece", "hover-height", effective.piece.hoverHeight)
        check(out, root, "piece", "move-lift-height", effective.piece.moveLiftHeight)
        check(out, root, "highlight", "marker-scale", effective.highlight.markerScale)
        check(out, root, "highlight", "marker-y-offset", effective.highlight.markerYOffset)
        // 悬浮字这五项被钳的后果都是"按钮看不见或点不到"，症状是点了没反应，
        // 服主最难自己联想到是配置写越界了，所以更要报出来
        check(out, root, "board-label", "y-offset", effective.boardLabel.yOffset)
        check(out, root, "board-label", "scale", effective.boardLabel.scale)
        check(out, root, "board-label", "view-range", effective.boardLabel.viewRange)
        check(out, root, "board-label", "hitbox-width", effective.boardLabel.hitboxWidth)
        check(out, root, "board-label", "hitbox-height", effective.boardLabel.hitboxHeight)
        check(out, root, "board", "max-per-world", effective.board.maxPerWorld)
        check(out, root, "board", "surface-offset", effective.board.surfaceOffset)
        check(out, root, "game", "draw-move-limit", effective.game.drawMoveLimit)
        check(out, root, "game", "turn-timeout-seconds", effective.game.turnTimeoutSeconds)
        check(out, root, "capture-explosion", "volume", effective.captureExplosion.volume)
        check(out, root, "capture-explosion", "radius", effective.captureExplosion.radius)
        check(out, root, "storage", "save-interval-seconds", effective.storage.saveIntervalSeconds)
        check(out, root, "bot", "think-ticks", effective.bot.thinkTicks)

        return out
    }

    /**
     * 检查一项数值配置。
     *
     * 磁盘上没写这一项就跳过 —— 那是"没配置"，用默认值是应有行为，不算被忽略。
     */
    private fun check(
        out: MutableList<Ignored>,
        root: Map<String, Any?>,
        section: String,
        key: String,
        effective: Number,
    ) {
        val raw = root.section(section)[key] ?: return
        val written = numberOf(raw)
        if (written == null) {
            // 写了但读不成数字：解析器已经退回默认值
            out.add(
                Ignored(
                    key = "$section.$key",
                    written = raw.toString(),
                    effective = effective.toString(),
                    reason = Reason.UNREADABLE.text,
                ),
            )
            return
        }
        // 数值能读出来但和生效值不同，只可能是被钳过
        if (Math.abs(written - effective.toDouble()) > TOLERANCE) {
            out.add(
                Ignored(
                    key = "$section.$key",
                    written = raw.toString(),
                    effective = effective.toString(),
                    reason = Reason.CLAMPED.text,
                ),
            )
        }
    }

    /** 取数值，允许服主写成字符串数字；读不出来返回 null */
    private fun numberOf(value: Any?): Double? = when (value) {
        is Number -> value.toDouble()
        is String -> value.trim().toDoubleOrNull()
        else -> null
    }

    /** 浮点比对容差，够小到能抓住任何有意义的钳制，又不会被表示误差误报 */
    private const val TOLERANCE = 1.0e-9
}
