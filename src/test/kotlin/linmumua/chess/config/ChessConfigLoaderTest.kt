package linmumua.chess.config

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import linmumua.chess.interact.checkmateTotemItemId
import linmumua.chess.rule.core.Side
import org.junit.jupiter.api.Test

/**
 * 配置解析测试。
 *
 * 重点是"服主写错了不能让插件崩，也不能悄悄用一个危险的值"。
 * 比如白圈缩放被填成 0，如果不兜住就会让全部合法空点提示消失。
 */
class ChessConfigLoaderTest {

    /** 空配置要能解析出全默认值，首次启动或文件被清空时走这条路 */
    @Test
    fun `空配置退回全默认值`() {
        val settings = ChessConfigLoader.parse(emptyMap())
        assertEquals(ChessSettings.DEFAULT, settings)
    }

    /** 发布模板必须默认关闭调试，避免生产服每次点击棋盘都刷控制台 */
    @Test
    fun `默认配置关闭调试日志`() {
        val stream = requireNotNull(javaClass.classLoader.getResourceAsStream("config.yml"))
        val template = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        assertTrue(Regex("(?m)^debug: false$").containsMatchIn(template))
    }

    /** 正常配置要被完整读出来 */
    @Test
    fun `完整配置被正确读出`() {
        val root = mapOf(
            "debug" to true,
            "board" to mapOf(
                "max-per-world" to 8,
                "require-flat-area" to false,
                "surface-offset" to 0.05,
            ),
            "piece" to mapOf(
                "base-scale" to 0.4,
                "selected-scale" to 0.6,
                "hover-height" to 0.3,
                "animation-ticks" to 6,
                "move-ticks" to 20,
                "move-lift-height" to 0.4,
            ),
            "highlight" to mapOf(
                "marker-scale" to 0.09,
                "marker-y-offset" to 0.04,
                "mark-checked-king" to false,
            ),
            "game" to mapOf(
                "draw-move-limit" to 30,
                "turn-timeout-seconds" to 60,
                "allow-undo" to true,
                "spectator-can-click" to true,
            ),
        )
        val settings = ChessConfigLoader.parse(root)

        assertTrue(settings.debug)
        assertEquals(8, settings.board.maxPerWorld)
        assertTrue(!settings.board.requireFlatArea)
        assertEquals(0.05, settings.board.surfaceOffset)
        assertEquals(0.4, settings.piece.baseScale)
        assertEquals(6, settings.piece.animationTicks)
        assertEquals(20, settings.piece.moveTicks)
        assertEquals(0.4, settings.piece.moveLiftHeight)
        assertEquals(0.09, settings.highlight.markerScale)
        assertEquals(0.04, settings.highlight.markerYOffset)
        assertTrue(!settings.highlight.markCheckedKing)
        assertEquals(30, settings.game.drawMoveLimit)
        assertEquals(60, settings.game.turnTimeoutSeconds)
        assertTrue(settings.game.allowUndo)
        assertTrue(settings.game.spectatorCanClick)
    }

    /**
     * 白圈缩放必须为正。
     *
     * 填 0 或负数会让合法空点提示完全不可见，所以必须兜住而不是照抄配置值。
     */
    @Test
    fun `白圈缩放必须为正`() {
        val settings = ChessConfigLoader.parse(
            mapOf("highlight" to mapOf("marker-scale" to -1.0)),
        )
        assertTrue(settings.highlight.markerScale > 0.0, "白圈缩放必须为正")
    }

    /**
     * 白圈必须明显小于棋子。
     *
     * 白圈只是"这里能走"的指示。做得跟棋子一样大的话，一次选中撒出七八个圈会把
     * 棋盘线和相邻棋子盖住，反而看不清局面。这里钉住尺寸关系而不是具体数值，
     * 服主调 base-scale 之后这条约束仍然成立。
     */
    @Test
    fun `白圈默认明显小于棋子`() {
        val settings = ChessConfigLoader.parse(emptyMap())
        val marker = settings.highlight.markerScale
        val piece = settings.piece.baseScale

        assertTrue(marker < piece, "白圈缩放 $marker 必须小于棋子 $piece")
        assertTrue(
            marker <= piece * 0.8,
            "白圈要明显小一圈才不会盖住棋盘线，当前 $marker 相对棋子 $piece 差得太少",
        )
        // 下限从 0.4 放到 0.25：0.4 是我按推算定的，两次实机反馈都说白圈偏大，
        // 说明那个下限本身估高了。0.25 对应可见外径约 0.036 格，仍是能看清的一个小圈。
        assertTrue(marker >= piece * 0.25, "白圈也不能小到看不见，当前 $marker")
    }

    /**
     * 白圈默认值就是 0.06。
     *
     * 之前是 0.12 与 0.09，两次都被反馈实机偏大。钉住具体值，
     * 避免有人以"跟棋子尺寸统一"之类的理由再调回去。
     */
    @Test
    fun `白圈默认缩放是零点零六`() {
        assertEquals(0.06, ChessConfigLoader.parse(emptyMap()).highlight.markerScale)
    }

    /** 默认不额外悬高，白圈中心才能在斜视角下尽量贴合棋盘交点 */
    @Test
    fun `白圈默认不增加垂直视差`() {
        val settings = ChessConfigLoader.parse(emptyMap())
        assertEquals(0.0, settings.highlight.markerYOffset)
    }

    /** 负抬升会让白圈沉进棋盘，必须收敛为零；合法正值仍允许服主微调 */
    @Test
    fun `白圈垂直偏移不能为负`() {
        val negative = ChessConfigLoader.parse(
            mapOf("highlight" to mapOf("marker-y-offset" to -0.1)),
        )
        val positive = ChessConfigLoader.parse(
            mapOf("highlight" to mapOf("marker-y-offset" to 0.03)),
        )
        assertEquals(0.0, negative.highlight.markerYOffset)
        assertEquals(0.03, positive.highlight.markerYOffset)
    }

    /**
     * 棋盘数量上限至少 1。
     *
     * 填 0 等于谁都放不了棋盘，那是配置错误而不是有意义的设置。
     */
    @Test
    fun `棋盘上限不允许为零`() {
        val settings = ChessConfigLoader.parse(
            mapOf("board" to mapOf("max-per-world" to 0)),
        )
        assertTrue(settings.board.maxPerWorld >= 1)
    }

    /**
     * 棋子缩放必须为正。
     *
     * 给 0 或负数会让显示实体不可见或翻面，玩家看不见棋子就没法下棋。
     */
    @Test
    fun `棋子缩放必须为正`() {
        val settings = ChessConfigLoader.parse(
            mapOf("piece" to mapOf("base-scale" to 0.0, "selected-scale" to -1.0)),
        )
        assertTrue(settings.piece.baseScale > 0.0)
        assertTrue(settings.piece.selectedScale > 0.0)
    }

    /**
     * 默认走子时长必须是 2 tick，也就是 0.1 秒。
     *
     * 这是"抬起 → 飞过去 → 落下"的整段时长，同时也是输入锁定时长，
     * 默认值变长会直接影响连续落子的手感。
     */
    @Test
    fun `默认走子时长是两个 tick`() {
        assertEquals(2, ChessConfigLoader.parse(emptyMap()).piece.moveTicks)
    }

    /**
     * 默认弧线高度与浮起高度相同，都是 0.12。
     *
     * 动画只有 2 tick，弧线抬得比浮起高会让棋子在两帧之间蹦一下；
     * 等高则选中的棋子走子时不会先沉再抬。
     */
    @Test
    fun `默认弧线高度与浮起高度相同`() {
        val piece = ChessConfigLoader.parse(emptyMap()).piece
        assertEquals(0.12, piece.moveLiftHeight)
        assertEquals(piece.hoverHeight, piece.moveLiftHeight)
    }

    /**
     * 走子总时长要有上下限。
     *
     * 少于 2 帧弧线就没有起落，退化成一次瞬移；太长会让玩家以为卡住了，
     * 而且这段时间棋盘不接受点击，不能让服主配出一个几十秒的输入锁定。
     */
    @Test
    fun `走子总时长有上下限`() {
        val tooShort = ChessConfigLoader.parse(mapOf("piece" to mapOf("move-ticks" to 1)))
        val tooLong = ChessConfigLoader.parse(mapOf("piece" to mapOf("move-ticks" to 9999)))

        assertTrue(tooShort.piece.moveTicks >= 2, "总时长不能短于 2 tick")
        assertTrue(tooLong.piece.moveTicks <= 60, "总时长不能超过 60 tick")
    }

    /**
     * 弧线顶点不能低于浮起高度。
     *
     * 玩家选中的棋子已经浮在 hover-height 上，抬起高度更低就意味着走子时先往下沉，
     * 那是明显的反直觉动作。
     */
    @Test
    fun `抬起高度不低于浮起高度`() {
        val settings = ChessConfigLoader.parse(
            mapOf("piece" to mapOf("hover-height" to 0.2, "move-lift-height" to 0.05)),
        )

        assertEquals(0.2, settings.piece.moveLiftHeight)
    }

    /**
     * 写盘间隔至少 5 秒。
     *
     * 填 0 会导致每秒都落盘，白白磨硬盘。
     */
    @Test
    fun `写盘间隔有下限`() {
        val settings = ChessConfigLoader.parse(
            mapOf("storage" to mapOf("save-interval-seconds" to 0)),
        )
        assertTrue(settings.storage.saveIntervalSeconds >= 5)
    }

    /**
     * 类型写错的项退回默认值，不让整份配置作废。
     *
     * 服主把布尔写成字符串、把数字写成单词都很常见。
     */
    @Test
    fun `类型写错的项退回默认值`() {
        val settings = ChessConfigLoader.parse(
            mapOf(
                "debug" to "不是布尔",
                "board" to mapOf("max-per-world" to "很多"),
            ),
        )
        assertEquals(ChessSettings.DEFAULT.debug, settings.debug)
        assertEquals(ChessSettings.DEFAULT.board.maxPerWorld, settings.board.maxPerWorld)
    }

    /** 数字写成字符串仍然能读出来，这是常见的手写习惯 */
    @Test
    fun `数字写成字符串仍可读`() {
        val settings = ChessConfigLoader.parse(
            mapOf("board" to mapOf("max-per-world" to "16")),
        )
        assertEquals(16, settings.board.maxPerWorld)
    }

    /**
     * 爆炸音量必须夹在 0..1。
     *
     * 音量在 Bukkit 里同时决定可听范围，填 5 会让一枚棋子的爆炸传到很远，
     * 把不下棋的玩家也吵到；负数则没有意义。
     */
    @Test
    fun `爆炸音量夹在零到一之间`() {
        val tooLoud = ChessConfigLoader.parse(
            mapOf("capture-explosion" to mapOf("volume" to 5.0)),
        )
        val negative = ChessConfigLoader.parse(
            mapOf("capture-explosion" to mapOf("volume" to -1.0)),
        )

        assertEquals(1.0, tooLoud.captureExplosion.volume)
        assertEquals(0.0, negative.captureExplosion.volume)
    }

    /**
     * 默认音量要明显低于满音量。
     *
     * 棋盘是桌面尺度的物件，原版爆炸音量会盖掉落子声，所以默认必须压低。
     */
    @Test
    fun `爆炸默认音量压到两成`() {
        val settings = ChessConfigLoader.parse(emptyMap())
        assertEquals(0.2, settings.captureExplosion.volume)
    }

    /**
     * 爆炸半径必须为正且不超过一格。
     *
     * 填 0 会让粒子全叠在一个点上看不出是爆炸；填太大就不再是棋子尺寸的火光了。
     */
    @Test
    fun `爆炸半径必须为正且不超过一格`() {
        val zero = ChessConfigLoader.parse(
            mapOf("capture-explosion" to mapOf("radius" to 0.0)),
        )
        val huge = ChessConfigLoader.parse(
            mapOf("capture-explosion" to mapOf("radius" to 9.0)),
        )

        assertTrue(zero.captureExplosion.radius > 0.0)
        assertTrue(huge.captureExplosion.radius <= 1.0)
    }

    /**
     * 默认半径要与棋子尺寸相当。
     *
     * 需求是"和棋子一样小"：火光直径不该超过一枚棋子的显示尺寸，
     * 否则会盖住相邻格点和棋盘线。
     */
    @Test
    fun `爆炸默认半径不超过棋子显示尺寸`() {
        val settings = ChessConfigLoader.parse(emptyMap())
        val diameter = settings.captureExplosion.radius * 2

        assertTrue(
            diameter <= settings.piece.baseScale,
            "火光直径 $diameter 必须不超过棋子尺寸 ${settings.piece.baseScale}",
        )
    }

    /**
     * 音效留空是有意义的值，表示不播放，不能被当成缺失而退回默认音效。
     */
    @Test
    fun `音效留空表示不播放`() {
        val settings = ChessConfigLoader.parse(
            mapOf("sound" to mapOf("move" to "", "capture" to "")),
        )
        assertEquals("", settings.sound.move)
        assertEquals("", settings.sound.capture)
    }

    /** 将死图腾必须能分别配置红帅与黑将，空值也应保留为关闭演出。 */
    @Test
    fun `将死图腾按阵营读取物品 id`() {
        val settings = ChessConfigLoader.parse(
            mapOf(
                "checkmate-totem" to mapOf(
                    "red" to "custom:red_king",
                    "black" to "",
                ),
            ),
        )
        assertEquals("custom:red_king", settings.checkmateTotem.red)
        assertEquals("", settings.checkmateTotem.black)
    }

    /** 限着回合数配 0 表示关掉判和，要原样保留而不是兜成默认 60 */
    @Test
    fun `限着配零表示关掉判和`() {
        val settings = ChessConfigLoader.parse(
            mapOf("game" to mapOf("draw-move-limit" to 0)),
        )
        assertEquals(0, settings.game.drawMoveLimit)
    }

    /** 默认图腾 ID 必须与 PackSpec 实际生成的 CE 物品 ID 对齐，否则将死演出会静默失效。 */
    @Test
    fun `默认将死图腾 ID 对齐生成物品`() {
        assertEquals("muchess:red_king", ChessSettings.DEFAULT.checkmateTotem.red)
        assertEquals("muchess:black_king", ChessSettings.DEFAULT.checkmateTotem.black)
    }

    /** 将死演出使用败方的将或帅，而不是误用胜方资源。 */
    @Test
    fun `将死图腾按败方阵营选择`() {
        val settings = CheckmateTotemSettings("custom:red", "custom:black")
        assertEquals("custom:red", checkmateTotemItemId(Side.FIRST, settings))
        assertEquals("custom:black", checkmateTotemItemId(Side.SECOND, settings))
    }

    /** 机器人搜索参数要被完整读出来 */
    @Test
    fun `机器人搜索参数被正确读出`() {
        val settings = ChessConfigLoader.parse(
            mapOf(
                "bot" to mapOf(
                    "think-ticks" to 5,
                    "depth" to 3,
                    "candidate-radius" to 3,
                    "max-candidates" to 40,
                    "think-time-limit-ms" to 800,
                ),
            ),
        )
        assertEquals(5, settings.bot.thinkTicks)
        assertEquals(3, settings.bot.depth)
        assertEquals(3, settings.bot.candidateRadius)
        assertEquals(40, settings.bot.maxCandidates)
        assertEquals(800L, settings.bot.thinkTimeLimitMillis)
    }

    /**
     * 搜索深度必须被钳进 1..6。
     *
     * 服主填 0 或负数会让 `BotOptions` 的 require 抛异常，机器人每次落子都炸；
     * 填 20 会让一手棋算到服务器卡死。这两种都不能原样放过去。
     */
    @Test
    fun `搜索深度被钳进合法范围`() {
        val zero = ChessConfigLoader.parse(mapOf("bot" to mapOf("depth" to 0)))
        val huge = ChessConfigLoader.parse(mapOf("bot" to mapOf("depth" to 20)))
        assertEquals(1, zero.bot.depth)
        assertEquals(6, huge.bot.depth)
    }

    /**
     * 候选上限有下界，不能被压到 1。
     *
     * 压到 1 之后每个节点只看一手，搜索退化成"沿着排序第一名一路走到底"，
     * 活三级别的防守就漏了（必胜必防那两手另有兜底，不受影响）。
     */
    @Test
    fun `候选上限有下界`() {
        val tiny = ChessConfigLoader.parse(mapOf("bot" to mapOf("max-candidates" to 1)))
        assertEquals(4, tiny.bot.maxCandidates)
    }

    /**
     * 思考时限允许配 0（不限时），但不允许负数。
     *
     * 0 是有意义的取值：离线跑性能测试时不希望搜索被时限提前打断。
     * 负数会让每次搜索一开始就判超时，机器人直接交出空结果。
     */
    @Test
    fun `思考时限允许零但不允许负数`() {
        val unlimited = ChessConfigLoader.parse(
            mapOf("bot" to mapOf("think-time-limit-ms" to 0)),
        )
        val negative = ChessConfigLoader.parse(
            mapOf("bot" to mapOf("think-time-limit-ms" to -100)),
        )
        assertEquals(0L, unlimited.bot.thinkTimeLimitMillis)
        assertEquals(0L, negative.bot.thinkTimeLimitMillis)
    }

    /**
     * 配置到搜索参数的映射必须逐项对齐。
     *
     * 这两个类型刻意分开（搜索层要能脱离配置系统单测），代价是多一层搬运。
     * 搬运漏掉一项不会报错，只会让服主改了配置却没生效。
     */
    @Test
    fun `配置映射成搜索参数时逐项对齐`() {
        val bot = BotSettings(
            thinkTicks = 1,
            depth = 4,
            candidateRadius = 3,
            maxCandidates = 33,
            thinkTimeLimitMillis = 777L,
        )
        val options = bot.toBotOptions()
        assertEquals(4, options.depth)
        assertEquals(3, options.candidateRadius)
        assertEquals(33, options.maxCandidates)
        assertEquals(777L, options.timeLimitMillis)
    }

    /** 悬浮字的六项参数都要能被读出来，漏搬一项的症状是服主改了配置没生效 */
    @Test
    fun `悬浮字配置被正确读出`() {
        val settings = ChessConfigLoader.parse(
            mapOf(
                "board-label" to mapOf(
                    "enabled" to false,
                    "y-offset" to 2.0,
                    "scale" to 0.8,
                    "view-range" to 1.5,
                    "hitbox-width" to 1.4,
                    "hitbox-height" to 0.6,
                ),
            ),
        )
        assertTrue(!settings.boardLabel.enabled)
        assertEquals(2.0, settings.boardLabel.yOffset)
        assertEquals(0.8, settings.boardLabel.scale)
        assertEquals(1.5, settings.boardLabel.viewRange)
        assertEquals(1.4, settings.boardLabel.hitboxWidth)
        assertEquals(0.6, settings.boardLabel.hitboxHeight)
    }

    /** 没写 board-label 段时退回默认值，老配置文件升级上来不能因此少一行悬浮字 */
    @Test
    fun `缺少悬浮字段落时退回默认值`() {
        val settings = ChessConfigLoader.parse(emptyMap())
        assertEquals(BoardLabelSettings(), settings.boardLabel)
        assertTrue(settings.boardLabel.enabled, "默认必须开着，否则玩家找不到入座入口")
    }

    /**
     * 文字缩放填 0 必须被兜住。
     *
     * 这条守的是"服主填了危险值不会让按钮消失"：缩放 0 的 TextDisplay 完全不可见，
     * 而悬浮字是入座与开局的主要入口，看不见就等于这个功能没了。
     * 症状还只是"棋盘上方什么都没有"，服主很难联想到是自己填的缩放。
     */
    @Test
    fun `悬浮字缩放配零被钳到下界`() {
        val zero = ChessConfigLoader.parse(mapOf("board-label" to mapOf("scale" to 0.0)))
        val negative = ChessConfigLoader.parse(mapOf("board-label" to mapOf("scale" to -2.0)))

        assertTrue(zero.boardLabel.scale > 0.0, "缩放 0 会让悬浮字彻底不可见")
        assertTrue(negative.boardLabel.scale > 0.0, "负缩放会让文字翻面")
    }

    /**
     * 判定框宽高填 0 必须被兜住。
     *
     * TextDisplay 自己没有命中箱，右键全靠叠在同位置的 Interaction 实体接。
     * 判定框任一边为 0 就射线打不中，玩家点了没有任何反应 —— 按钮点不到
     * 就等于功能没了，而且比"看不见"更难排查，因为字还好好挂在那里。
     */
    @Test
    fun `判定框宽高配零被钳到下界`() {
        val settings = ChessConfigLoader.parse(
            mapOf("board-label" to mapOf("hitbox-width" to 0.0, "hitbox-height" to 0.0)),
        )

        assertTrue(settings.boardLabel.hitboxWidth > 0.0, "判定框宽 0 就点不到")
        assertTrue(settings.boardLabel.hitboxHeight > 0.0, "判定框高 0 就点不到")
    }

    /**
     * 可见距离倍率也不能为 0。
     *
     * 这一项容易被误当成格数填（0 会被当成"用默认"），但它是原版 Display 的倍率属性，
     * 实际距离约为 倍率 × 64 格，填 0 就是任何距离都不渲染。
     */
    @Test
    fun `可见距离倍率不能为零`() {
        val settings = ChessConfigLoader.parse(mapOf("board-label" to mapOf("view-range" to 0.0)))
        assertTrue(settings.boardLabel.viewRange > 0.0, "倍率 0 等于永远不渲染")
    }

    /**
     * 悬浮字高度不能为负。
     *
     * 负数会让字沉进棋盘模型里，既看不见也点不到。
     *
     * 原来这里断言钳到 0.0，现在改成"必须严格大于 0"：0 与负数的后果是同一个
     * （见下面那条 0 值用例），下界随之从 0.0 抬到了 0.1，写死 0.0 会把这次修复锁回去。
     */
    @Test
    fun `悬浮字高度不能为负`() {
        val settings = ChessConfigLoader.parse(mapOf("board-label" to mapOf("y-offset" to -1.0)))
        assertTrue(settings.boardLabel.yOffset > 0.0, "负高度会让字沉进棋盘模型")
    }

    /**
     * 悬浮字高度填 0 也必须被抬起来。
     *
     * 这条守的是一个**曾经漏掉的**下界：其余四项（scale、view-range、hitbox 宽高）都用
     * 0.1 兜住了，只有 y-offset 的下界写成 0.0，于是填 0 能原样通过。
     *
     * 而 0 并不是"贴着棋盘面"这种可用值：棋盘家具自己有厚度，高度 0 的字直接埋在模型里，
     * 看不见也点不到 —— 与填负数完全等价。症状是"棋盘上方什么都没有"，
     * 服主只会以为悬浮字功能坏了，不会怀疑自己填的这个 0，所以必须在加载期就抬上来。
     */
    @Test
    fun `悬浮字高度配零被钳到下界`() {
        val settings = ChessConfigLoader.parse(mapOf("board-label" to mapOf("y-offset" to 0.0)))
        assertTrue(
            settings.boardLabel.yOffset > 0.0,
            "高度 0 会让字埋进棋盘模型，与负数一样看不见也点不到",
        )
    }

    /**
     * 模板里的悬浮字默认值必须与代码默认值一致。
     *
     * 不一致的后果是"删掉配置项"与"保留模板默认值"两种情况下悬浮字的位置和大小不同，
     * 排查起来非常费劲。
     */
    @Test
    fun `模板悬浮字默认值与代码一致`() {
        val stream = requireNotNull(javaClass.classLoader.getResourceAsStream("config.yml"))
        val template = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val defaults = ChessSettings.DEFAULT.boardLabel

        assertTrue(
            Regex("(?m)^board-label:$").containsMatchIn(template),
            "模板里缺少 board-label 段，服主拿不到默认值与注释",
        )
        assertTrue(
            Regex("(?m)^  enabled: ${defaults.enabled}$").containsMatchIn(template),
            "模板里的 enabled 与代码默认值 ${defaults.enabled} 不一致",
        )
        assertTrue(
            Regex("(?m)^  y-offset: ${defaults.yOffset}$").containsMatchIn(template),
            "模板里的 y-offset 与代码默认值 ${defaults.yOffset} 不一致",
        )
        assertTrue(
            Regex("(?m)^  scale: ${defaults.scale}$").containsMatchIn(template),
            "模板里的 scale 与代码默认值 ${defaults.scale} 不一致",
        )
        assertTrue(
            Regex("(?m)^  view-range: ${defaults.viewRange}$").containsMatchIn(template),
            "模板里的 view-range 与代码默认值 ${defaults.viewRange} 不一致",
        )
        assertTrue(
            Regex("(?m)^  hitbox-width: ${defaults.hitboxWidth}$").containsMatchIn(template),
            "模板里的 hitbox-width 与代码默认值 ${defaults.hitboxWidth} 不一致",
        )
        assertTrue(
            Regex("(?m)^  hitbox-height: ${defaults.hitboxHeight}$").containsMatchIn(template),
            "模板里的 hitbox-height 与代码默认值 ${defaults.hitboxHeight} 不一致",
        )
    }

    /**
     * 模板里的机器人默认值必须与代码默认值一致。
     *
     * 不一致的后果是"删掉配置项"与"保留模板默认值"两种情况下机器人表现不同，
     * 排查起来非常费劲。
     */
    @Test
    fun `模板机器人默认值与代码一致`() {
        val stream = requireNotNull(javaClass.classLoader.getResourceAsStream("config.yml"))
        val template = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val defaults = ChessSettings.DEFAULT.bot
        assertTrue(
            Regex("(?m)^  depth: ${defaults.depth}$").containsMatchIn(template),
            "模板里的 depth 与代码默认值 ${defaults.depth} 不一致",
        )
        assertTrue(
            Regex("(?m)^  candidate-radius: ${defaults.candidateRadius}$").containsMatchIn(template),
            "模板里的 candidate-radius 与代码默认值不一致",
        )
        assertTrue(
            Regex("(?m)^  max-candidates: ${defaults.maxCandidates}$").containsMatchIn(template),
            "模板里的 max-candidates 与代码默认值不一致",
        )
        assertTrue(
            Regex("(?m)^  think-time-limit-ms: ${defaults.thinkTimeLimitMillis}$")
                .containsMatchIn(template),
            "模板里的 think-time-limit-ms 与代码默认值不一致",
        )
    }

    /**
     * 背景音乐总闸默认必须是关的。
     *
     * 这条锁住的是"升级不吵人"：老服主升上来什么都不改，棋盘不该突然开始放音乐。
     * 模板与代码默认值一起断言，两边任一被改开都会红。
     */
    @Test
    fun `背景音乐默认关闭`() {
        assertTrue(!ChessSettings.DEFAULT.go.openingBgm.enabled)
        val stream = requireNotNull(javaClass.classLoader.getResourceAsStream("config.yml"))
        val template = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        assertTrue(
            Regex("(?m)^    enabled: false$").containsMatchIn(template),
            "模板里的 opening-bgm.enabled 必须默认 false",
        )
    }

    /** 四项都填了要被完整读出来，不能有哪一项被忽略后悄悄用默认值 */
    @Test
    fun `背景音乐配置被正确读出`() {
        val settings = ChessConfigLoader.parse(
            mapOf(
                "go" to mapOf(
                    "opening-bgm" to mapOf(
                        "enabled" to true,
                        "sound" to "minecraft:music_disc.cat",
                        "volume" to 0.35,
                        "loop-period-seconds" to 185,
                    ),
                ),
            ),
        )
        val bgm = settings.go.openingBgm
        assertTrue(bgm.enabled)
        assertEquals("minecraft:music_disc.cat", bgm.sound)
        assertEquals(0.35, bgm.volume)
        assertEquals(185, bgm.loopPeriodSeconds)
    }

    /**
     * 周期填 0 或负数被抬到 1 秒。
     *
     * 防的是巡检退化成每秒重播：巡检每秒跑一次，周期 0 会让同一首曲子
     * 一分钟叠 60 遍，音量翻几十倍，且没有任何报错。
     */
    @Test
    fun `背景音乐周期为零或负数被抬到一秒`() {
        listOf(0, -1, -3600).forEach { raw ->
            val settings = ChessConfigLoader.parse(
                mapOf("go" to mapOf("opening-bgm" to mapOf("loop-period-seconds" to raw))),
            )
            assertEquals(1, settings.go.openingBgm.loopPeriodSeconds, "周期 $raw 应被抬到 1 秒")
        }
    }

    /**
     * 服主换成 20 秒的短曲时，周期必须保持 20 不被篡改。
     *
     * 这条断言锁死"不与自带曲长挂钩"的取舍：把下界改成自带曲长 66 看着更"安全"，
     * 实际会给换了短曲的服凭空造出 46 秒静音，而服主完全不知道值被改了。
     */
    @Test
    fun `背景音乐周期小于自带曲长时不被篡改`() {
        val settings = ChessConfigLoader.parse(
            mapOf("go" to mapOf("opening-bgm" to mapOf("loop-period-seconds" to 20))),
        )
        assertEquals(20, settings.go.openingBgm.loopPeriodSeconds)
    }

    /**
     * 音量夹在 0.0~1.0。
     *
     * 上界不是洁癖：MC 对 volume>1 的处理是放大**可听距离**而不是音量，
     * 服主填 5 只会让隔一片地的人被迫听棋，棋盘边并不会更响。
     */
    @Test
    fun `背景音乐音量被夹在合法区间`() {
        val loud = ChessConfigLoader.parse(
            mapOf("go" to mapOf("opening-bgm" to mapOf("volume" to 5.0))),
        )
        assertEquals(1.0, loud.go.openingBgm.volume)
        val negative = ChessConfigLoader.parse(
            mapOf("go" to mapOf("opening-bgm" to mapOf("volume" to -2.0))),
        )
        assertEquals(0.0, negative.go.openingBgm.volume)
    }

    /**
     * 模板里的背景音乐默认值必须与代码默认值一致。
     *
     * 尤其是周期：模板写 66、代码写别的，会让"删掉这一项"与"保留模板值"
     * 放出两种不同的叠播行为，靠耳朵根本排查不出来。
     */
    @Test
    fun `模板背景音乐默认值与代码一致`() {
        val stream = requireNotNull(javaClass.classLoader.getResourceAsStream("config.yml"))
        val template = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val defaults = ChessSettings.DEFAULT.go.openingBgm
        assertTrue(
            Regex("(?m)^    sound: \"${Regex.escape(defaults.sound)}\"$").containsMatchIn(template),
            "模板里的 sound 与代码默认值 ${defaults.sound} 不一致",
        )
        assertTrue(
            Regex("(?m)^    volume: ${defaults.volume}$").containsMatchIn(template),
            "模板里的 volume 与代码默认值 ${defaults.volume} 不一致",
        )
        assertTrue(
            Regex("(?m)^    loop-period-seconds: ${defaults.loopPeriodSeconds}$")
                .containsMatchIn(template),
            "模板里的 loop-period-seconds 与代码默认值 ${defaults.loopPeriodSeconds} 不一致",
        )
    }
}
