package linmumua.chess.config

import linmumua.chess.game.bot.BotOptions

/**
 * config.yml 的强类型模型。
 *
 * 这里只放"配置长什么样"，不放"怎么读"。读取在 [ChessConfigLoader]，
 * 这样解析逻辑能脱离服务端单测，模型本身也不会被 SnakeYAML 的类型细节污染。
 *
 * 每个字段的默认值与 `src/main/resources/config.yml` 的注释一一对应，
 * 服主删掉某一项时按这里的默认值兜底，不报错。
 */
data class ChessSettings(
    /** 调试日志开关，对应 config.yml 的 debug */
    val debug: Boolean = false,
    val craftEngine: CraftEngineSettings = CraftEngineSettings(),
    val checkmateTotem: CheckmateTotemSettings = CheckmateTotemSettings(),
    val board: BoardSettings = BoardSettings(),
    val piece: PieceSettings = PieceSettings(),
    val highlight: HighlightSettings = HighlightSettings(),
    val boardLabel: BoardLabelSettings = BoardLabelSettings(),
    val game: GameSettings = GameSettings(),
    val sound: SoundSettings = SoundSettings(),
    val captureExplosion: CaptureExplosionSettings = CaptureExplosionSettings(),
    val storage: StorageSettings = StorageSettings(),
    val bot: BotSettings = BotSettings(),
    val gomoku: GomokuSettings = GomokuSettings(),
    val go: GoSettings = GoSettings(),
) {
    companion object {
        /** 全默认值，配置文件读不到时用这一份 */
        val DEFAULT = ChessSettings()
    }
}

/** 资源包生成相关开关，本层只读不写，真正生成由 pack 模块负责 */
data class CraftEngineSettings(
    val autoGeneratePack: Boolean = true,
    val autoReload: Boolean = true,
    val skipIfUnchanged: Boolean = true,
)

/** 将死时播放的 CraftEngine 图腾物品 id；空字符串表示关闭对应阵营的演出 */
data class CheckmateTotemSettings(
    val red: String = "muchess:red_king",
    val black: String = "muchess:black_king",
)

/** 棋盘放置约束 */
data class BoardSettings(
    /** 单个世界棋盘数量上限 */
    val maxPerWorld: Int = 64,
    /** 是否要求 2x2 范围完全空旷 */
    val requireFlatArea: Boolean = true,
    /** 棋盘面相对方块顶面的抬升，避免与地面 z-fighting */
    val surfaceOffset: Double = 0.02,
    /**
     * 拆掉棋盘家具时是否退还一个棋盘物品。
     *
     * CE 默认掉落由破坏监听关闭，再按本开关统一返还一个，保证一副家具最多产出
     * 一个棋盘物品。关掉的话拆除后不返还。
     */
    val refundOnBreak: Boolean = true,
)

/** 棋子显示实体的外观与动画参数 */
data class PieceSettings(
    /**
     * 未选中时的缩放。
     *
     * 上限由格点间距决定：棋盘 2x2 去掉两侧 0.12 边距后可用跨度 1.76，
     * 10 条横线的间距是 1.76/9 ≈ 0.196。默认 0.17 约占间距 87%，
     * 与实际棋盘的棋子比例接近；再大相邻棋子会互相压边。
     */
    val baseScale: Double = 0.17,
    /** 选中后放大到的缩放，略大于 base 但不超过一格间距 */
    val selectedScale: Double = 0.21,
    /** 选中后浮起高度，单位方块。棋子缩小后这个值也要跟着收 */
    val hoverHeight: Double = 0.12,
    /**
     * 浮起与放大的插值时长，单位 tick。
     * 给 0 表示不做插值，直接切换到目标变换。
     *
     * 只管选中态的浮起与落下，不参与走子的三段动画时长。
     */
    val animationTicks: Int = 4,
    /**
     * 走子总时长，单位 tick。
     *
     * 2 tick 即 0.1 秒，是"抬起 → 飞过去 → 落下"的**整段**时长，不是其中一段。
     * 平移与升降在这 2 tick 里同时完成，走一条连续弧线；分段的话每段只剩一帧，
     * 那就不是动作而是跳变。
     *
     * 2 tick 也是加载器允许的下限（[ChessConfigLoader] 里的 MIN_MOVE_TICKS）：
     * 再短就没有起落两帧，弧线退化成直接传送。
     *
     * 这段时间内该棋盘不接受新点击，所以这个值同时决定输入锁定时长。
     */
    val moveTicks: Int = 2,
    /**
     * 走子弧线最高点的高度，单位方块。
     *
     * 与 [hoverHeight] 取同一个 0.12：动画只有 2 tick，抬得再高也来不及看清，
     * 反而会让棋子在两帧之间蹦一下。等高的好处是选中态浮起后走子不会先沉再抬。
     *
     * 加载器会把这个值钳到不低于 [hoverHeight]，所以 0.12 正好压在下界上。
     */
    val moveLiftHeight: Double = 0.12,
)

/** 合法落点白圈与将军警示参数。 */
data class HighlightSettings(
    /**
     * 空合法落点白圈的显示缩放；贴图自带透明边距，默认可见外径略小于普通棋子。
     *
     * 比 [PieceSettings.baseScale] 小：白圈只是"这里能走"的指示，不该做得跟棋子一样大，
     * 否则一次选中撒出七八个圈会把棋盘线和相邻棋子盖住。
     * 贴图外径占 84%，所以默认值 0.06 对应的可见外径约 0.050 格，约为棋子直径的 29%。
     */
    val markerScale: Double = 0.06,
    /** 白圈相对棋盘交点的额外垂直抬升；只改 Y，不参与 X/Z 格点校正 */
    val markerYOffset: Double = 0.0,
    /** 被将军时是否给将/帥套一圈短暂警示粒子 */
    val markCheckedKing: Boolean = true,
)

/**
 * 棋盘上方那行可点击悬浮字的参数。
 *
 * 这行字是"入座 / 开始对局"的世界内入口，由 `render.BoardLabelRenderer` 用
 * 一个 TextDisplay（负责显示）加一个 Interaction（负责被点）实现 ——
 * TextDisplay 自己没有命中箱，右键点不到，所以判定框必须单独给。
 * 文案在 `lang/zh_CN.yml` 的 `label-join` 与 `label-start`。
 */
data class BoardLabelSettings(
    /**
     * 总开关。
     *
     * 关掉后棋盘上方不显示任何东西，入座与开局都只剩 shift+右键棋盘打开的箱子菜单 ——
     * 菜单里那枚按钮会按状态在"加入对局"与"开始对局"之间切换（见 `interact.BoardMenu`），
     * 所以关掉之后流程仍走得通，不会卡在满座等不到开局。
     *
     * 默认开着是因为玩家得先知道有那个菜单：右键棋盘看得见牌子，
     * 而"shift+右键才出菜单"没有任何视觉提示。
     */
    val enabled: Boolean = true,
    /**
     * 悬浮字相对棋盘面的高度，单位方块。
     *
     * 默认 1.2 约在站立玩家的视线高度：低头看棋盘、抬头就看见牌子。
     * 加载器把下界钳在 0.1 而不是 0.0 —— 0 与负数一样会让字沉到棋盘模型里面去，
     * 看不见也点不到。
     */
    val yOffset: Double = 1.2,
    /**
     * 文字缩放。
     *
     * 原版 TextDisplay 的 1.0 是给告示牌那种远距离大字用的，摆在 2x2 棋盘上方会
     * 宽到盖住整个棋盘。默认 0.5 大约是一行普通聊天字的观感。
     */
    val scale: Double = 0.5,
    /**
     * 可见距离**倍率**，不是格数。
     *
     * 这是原版 Display 实体的 view range 属性，实际可见距离约为 倍率 × 64 格，
     * 且还会被客户端自己的实体渲染距离设置压制。
     * 默认 0.5 对应约 32 格：走近才看见，避免一片棋盘区在远处糊成一堆浮字。
     */
    val viewRange: Double = 0.5,
    /**
     * 点击判定框宽度，单位方块。
     *
     * 与文字的渲染宽度是两件事：判定框略宽一点更好点，但大到几格就会挡住棋盘边上的棋子，
     * 玩家想选边角的子会误点开牌子。默认 1.0 覆盖文案的横向范围。
     */
    val hitboxWidth: Double = 1.0,
    /**
     * 点击判定框高度，单位方块。
     *
     * 默认 0.4，比一行字略高，够点又不会在垂直方向拦住射线。
     */
    val hitboxHeight: Double = 0.4,
)

/** 对局流程可调项 */
data class GameSettings(
    /** 无吃子判和的回合数，0 表示不启用限着判和 */
    val drawMoveLimit: Int = 60,
    /** 单方每步思考时限，单位秒，0 表示不限时 */
    val turnTimeoutSeconds: Int = 0,
    /** 是否允许悔棋 */
    val allowUndo: Boolean = false,
    /** 是否允许旁观者点击棋盘 */
    val spectatorCanClick: Boolean = false,
)

/**
 * 音效 id。
 *
 * 值是 Minecraft 的 sound id 字符串（例如 `minecraft:block.wood.hit`），
 * 空字符串表示这一项不播放。同样不在配置层转成 Bukkit 类型。
 */
data class SoundSettings(
    val move: String = "minecraft:block.wood.hit",
    val capture: String = "minecraft:entity.item.break",
    val check: String = "minecraft:block.note_block.pling",
    val gameOver: String = "minecraft:entity.player.levelup",
    val select: String = "minecraft:ui.button.click",
)

/**
 * 炮吃子的爆炸表现。
 *
 * 只给炮：炮是唯一隔子远距离吃子的兵种，一声闷响配一小团火光符合它的意象；
 * 其它兵种是贴身吃子，加爆炸反而吵。
 */
data class CaptureExplosionSettings(
    /** 是否启用炮吃子的爆炸表现 */
    val enabled: Boolean = true,
    /**
     * 爆炸音效 id，留空表示只要粒子不要声音。
     *
     * 与 [SoundSettings.capture] 叠加播放，不替换它。
     */
    val sound: String = "minecraft:entity.generic.explode",
    /**
     * 爆炸音量，1.0 是原版满音量。
     *
     * 默认 0.2：棋盘是桌面尺度的物件，原版爆炸音量会盖掉落子声。
     * 这个值同时决定可听范围，调大会让远处玩家也听见。
     */
    val volume: Double = 0.2,
    /**
     * 爆炸的可见半径，单位方块。
     *
     * 默认 0.085，约等于 `piece.base-scale`（0.17）的一半，也就是让火光正好
     * 铺满一枚棋子的大小。原版 EXPLOSION 粒子尺寸写死在客户端改不了，
     * 所以这里用可控尺寸的 DUST_COLOR_TRANSITION 自己堆出一小团。
     */
    val radius: Double = 0.085,
)

/** 落盘策略 */
data class StorageSettings(
    /** 已放置棋盘的自动写盘间隔，单位秒 */
    val saveIntervalSeconds: Int = 300,
)

/**
 * 机器人参数。
 *
 * 机器人用 alpha-beta 搜索（见 `game.bot.AlphaBetaSearch`），象棋与五子棋
 * 各有自己的估值器。默认 2 层：能看出"吃了会被反吃"，也会堵对手的连四。
 * 它是能陪着下的对手，但不是棋力引擎 —— 没有开局库、没有残局库。
 */
data class BotSettings(
    /**
     * 机器人思考延迟，单位 tick。
     *
     * 给 0 会在玩家落子的同一 tick 里立刻回手，看着很鬼畜；
     * 默认 20 tick（1 秒）留出可感知的停顿。
     *
     * 这是**表现**上的停顿，与 [thinkTimeLimitMillis] 的搜索耗时上限是两件事。
     */
    val thinkTicks: Int = 20,
    /**
     * 搜索层数（半回合）。
     *
     * 2 表示"我走一步、对手回一步"。这是能看出反击的最低层数：
     * 1 层只看到自己这手的收益，会拿車去吃有保护的兵。
     *
     * 调到 3 以上棋力略升但耗时明显上涨（象棋分支约 40，每加一层乘 40），
     * 且必须靠 [thinkTimeLimitMillis] 兜住。允许范围 1~6。
     */
    val depth: Int = 2,
    /**
     * 落子式棋种（五子棋）的候选半径：只考虑已有子周围这么多格内的空点。
     *
     * 15×15 空盘有 225 个点，全搜两层是 5 万个局面，而离战场十格远的点
     * 在五子棋里毫无意义。默认 2 格。允许范围 1~4。
     */
    val candidateRadius: Int = 2,
    /**
     * 每个节点最多展开多少候选着法。
     *
     * 候选已按好坏排序，截断丢掉的是评分最低的那些。
     * 必胜必防手不受影响 —— 那条独立于候选生成。允许范围 4~120。
     */
    val maxCandidates: Int = 24,
    /**
     * 每手搜索的耗时上限，单位毫秒。
     *
     * 超时交出**上一层已完整搜完**的结果，不交半层结果。
     * 给 0 表示不限时（只在离线跑测试时才该这么配）。
     *
     * 默认 200ms：搜索在异步线程跑，但 debug 命令可能同时开几十盘，
     * 不限时会让线程池堆积。允许范围 0~5000。
     */
    val thinkTimeLimitMillis: Long = 200L,
) {
    /**
     * 映射成搜索层的参数对象。
     *
     * 配置层与搜索层刻意分成两个类型：搜索层是纯逻辑，要能脱离配置系统跑单测，
     * 不该为了造一个测试用的参数去构造整份 `ChessSettings`。
     * 范围钳制在 `ChessConfigLoader` 里做完，这里只做搬运。
     */
    fun toBotOptions(): BotOptions = BotOptions(
        depth = depth,
        candidateRadius = candidateRadius,
        maxCandidates = maxCandidates,
        timeLimitMillis = thinkTimeLimitMillis,
    )
}

/**
 * 五子棋专有参数。
 *
 * [baseScale] 单独给一份而不是共用 [PieceSettings.baseScale]：
 * 五子棋 15×15 铺在 2x2 上，格点间距只有 0.126 格，而象棋的 0.17 缩放
 * 会让棋子直径达到间距的 124%，相邻两子直接压边重叠。
 */
data class GomokuSettings(
    /**
     * 五子棋棋子缩放。
     *
     * 默认 0.116：圆盘直径约占格点间距的 85%，与象棋子在自己盘上的观感一致。
     * 调大到 0.14 以上相邻子就会互相压边。
     */
    val baseScale: Double = 0.116,
    /**
     * 是否启用禁手判负（三三、四四、长连）。
     *
     * 默认关闭：禁手是高水平对局才需要的平衡规则，默认开会让新手莫名其妙被判负。
     */
    val forbiddenMovesEnabled: Boolean = false,
)

/**
 * 围棋专有参数。
 *
 * [baseScale] 单独给一份的理由与 [GomokuSettings.baseScale] 相同，只是数值不同：
 * 围棋 19×19 铺在 3x3 上，格点间距 (3.0-0.24)/18 ≈ 0.153 格。
 */
data class GoSettings(
    /**
     * 围棋棋子缩放。
     *
     * 默认 0.130：圆盘直径约占格点间距（≈0.153 格）的 85%，与另两个棋种的观感一致。
     * 调大到 0.16 以上相邻子就会互相压边。
     */
    val baseScale: Double = 0.130,
    /**
     * 贴给白方的子数（中国数子法）。
     *
     * 单位是**子**不是目：本项目用数子法，3.75 子 ≈ 7.5 目。
     * 填数目法的 6.5 会让贴子几乎翻倍，白方凭空多出近一倍补偿。
     */
    val komi: Double = linmumua.chess.rule.go.GoRuleOptions.DEFAULT_KOMI,
    /**
     * 围棋机器人每个节点最多展开多少个候选着法。
     *
     * 单独一档而不是共用 [BotSettings.maxCandidates]（默认 24）：围棋 19 路需要
     * 更狠的裁剪，但直接改共用值会连带削弱五子棋的防守（那边调小会漏掉活三级别的防守）。
     *
     * 默认 12 的依据是实测：19×19 中盘 `legalMoves` 单次约 1ms，两层搜索节点数是
     * 候选数的平方。24 个候选 → 576 节点 ≈ 576ms，超出默认 200ms 思考预算；
     * 12 个候选 → 144 节点 ≈ 144ms，落在预算内且留余量。
     *
     * 不宜低于 8：候选来源有三类（附近空点、救己方危块、提对方危块），
     * 太小会把后两类挤掉，机器人就既不救子也不提子了。
     */
    val botMaxCandidates: Int = 12,
    /** 开局背景音乐 */
    val openingBgm: GoBgmSettings = GoBgmSettings(),
)

/**
 * 围棋开局背景音乐。
 *
 * 只有围棋有这一档：象棋与五子棋一局几分钟，配长曲子反而吵；围棋一局动辄半小时以上，
 * 有段静的背景音才对得上那个节奏。
 *
 * 音频由构建期写进 CraftEngine 资源包（见 buildSrc 的 `PackSpec.GO_BGM_SOUND_ID`），
 * 服主不用手工拼包。CE 缺失时资源包不会下发，客户端收不到这个声音事件，
 * `playSound` 静默无声 —— 不会报错，也不影响对局。
 */
data class GoBgmSettings(
    /**
     * 全局是否启用。
     *
     * **默认关闭。** 背景音乐是很主观的东西，默认响起来会打扰那些只想安静下棋的人；
     * 而且它是新特性，默认关能让服主先自己听一遍再决定要不要放出去。
     * 开着之后每局还能各自关（见对局菜单的开关按钮），这里是总闸。
     */
    val enabled: Boolean = false,
    /**
     * 声音事件 id。
     *
     * 默认 `muchess:music.go_opening` 就是本插件资源包自带的那首。
     * 与 buildSrc 的 `PackSpec.GO_BGM_SOUND_ID` 是**同一个值的两份字面量**：buildSrc 不在
     * 运行期 classpath 上，没法共享常量，改一侧必须同步另一侧（棋子 assetKey 也是这么处理的）。
     *
     * 填别的 id 可以换成服主自己包里的曲子，甚至原版音乐（如 `minecraft:music_disc.cat`）。
     * **留空表示不播放**，效果等同于把 [enabled] 关掉。
     */
    val sound: String = "muchess:music.go_opening",
    /**
     * 音量。
     *
     * 走 `SoundCategory.MUSIC`，玩家能在客户端音量设置里单独调小或静音，不影响落子声。
     * 默认 0.6 而不是 1.0：这是背景音，压过棋子落盘的"哒"声就本末倒置了。
     */
    val volume: Double = 0.6,
    /**
     * 重播周期，秒。
     *
     * MC 客户端不支持让一个声音事件自己循环，只能到点重播，所以这个值必须**略大于**
     * 曲子时长 —— 小了同一段音乐会叠着放，越叠越吵。自带曲子 65.55 秒，默认给 66。
     *
     * 换成自己的曲子就要跟着改：改小了叠播，改大了两遍之间有长静音。
     */
    val loopPeriodSeconds: Int = 66,
)
