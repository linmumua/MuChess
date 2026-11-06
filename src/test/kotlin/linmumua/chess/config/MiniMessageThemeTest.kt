package linmumua.chess.config

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 玩家文案 MiniMessage 主题约束。
 *
 * 颜色不是装饰：gold 表示系统与成功，aqua 表示命令和可操作数据，gray 承担高频正文，
 * amber / rose 分别只用于警告与失败。测试用于防止后续新增文案重新混入高亮白墙或滥用渐变。
 */
class MiniMessageThemeTest {

    private val languageText: String = checkNotNull(javaClass.getResource("/lang/zh_CN.yml")) {
        "测试资源中缺少 lang/zh_CN.yml"
    }.readText()

    @Test
    @DisplayName("高频正文不使用整段亮白色，避免聊天区过度刺眼")
    fun `body text keeps the muted gray role`() {
        assertFalse(languageText.contains("<white>"))
        assertTrue(languageText.contains("<gray>"))
        assertTrue(languageText.contains("<dark_gray>»</dark_gray>"))
    }

    @Test
    @DisplayName("帮助中的可执行命令统一使用 aqua")
    fun `help commands share one action color`() {
        val helpLines = languageText.lineSequence()
            .filter { it.startsWith("help-") && !it.startsWith("help-header:") }
            .toList()

        assertTrue(helpLines.isNotEmpty())
        assertTrue(helpLines.all { it.contains(": \"<aqua>/chess") })
    }

    @Test
    @DisplayName("渐变只保留给终局播报、终局牌子与菜单或帮助标题")
    fun `gradients stay ceremonial`() {
        val gradientKeys = languageText.lineSequence()
            .filter { it.contains("<gradient:") }
            .map { it.substringBefore(':') }
            .toSet()

        assertEquals(
            setOf(
                "game-over-checkmate",
                "game-over-stalemate",
                "game-over-resign",
                "game-over-draw-move-limit",
                // 双方同意的和棋，同属终局播报
                "game-over-draw-agreement",
                "game-over-timeout",
                "game-over-quit",
                // 五子棋与围棋的终局播报，与象棋的终局同属"仪式性"文案
                "game-over-line-win",
                "game-over-draw-full-board",
                "game-over-score-win",
                "game-over-score-draw",
                // 棋盘上的终局牌子。它和上面的终局播报是同一件事的两个出口 ——
                // 聊天栏发一条、棋盘上留一块 —— 色值也刻意跟对应的播报走
                // （判胜用 #fbbf24:#f97316、超时用 #fda4af:#f97316、和棋用 #7dd3fc:#fbbf24、
                // 作废用 #94a3b8:#7dd3fc），所以归在仪式性这一档。
                //
                // 同一批 label 里的另外三条不在此列，也不该进来：label-join 与 label-start 是
                // 可点的按钮，label-playing 是对局中一直挂着的状态行，它们统一用单色 gold ——
                // 让"能点的"和"只是报状态的"在颜色上就分得开。
                "label-finished-win",
                "label-finished-timeout",
                "label-finished-draw",
                "label-finished-void",
                // 菜单标题按棋种分流成三条，仍同属仪式性文案。
                // 分流的理由见语言文件里那段注释：写死一条会让围棋盘的菜单顶着"象棋棋盘"。
                "menu-board-title-xiangqi",
                "menu-board-title-gomoku",
                "menu-board-title-go",
                "help-header",
            ),
            gradientKeys,
        )
    }

    /**
     * 高频成功状态仍然不刷聊天，但开局播报是例外。
     *
     * `board-placed` 与 `piece-selected` 是高频动作的回执，世界里看得见结果，
     * 聊天栏复述一遍只会把终局播报挤走，所以继续禁止。
     *
     * `game-started` 反过来：棋盘放下后不再自动摆子，改成双方入座后有人点"开始对局"才摆。
     * 开局因此变成一个**明确的时点**而不是放下棋盘的副作用，需要一条播报确认它真的发生了，
     * 否则点了牌子的人不确定是自己没点中还是对手还没准备好。它一局只发一次，不构成刷屏。
     */
    @Test
    @DisplayName("高频成功状态不刷聊天，开局播报例外，警告与失败仍使用固定语义色")
    fun `status messages keep semantic colors`() {
        assertFalse(languageText.contains("board-placed:"))
        assertFalse(languageText.contains("piece-selected:"))
        assertTrue(
            languageText.contains("game-started: \"<gold>"),
            "开局播报必须存在且用 gold（系统与成功色）",
        )
        assertTrue(languageText.contains("board-no-space: \"<#fbbf24>"))
        assertTrue(languageText.contains("board-incomplete: \"<#fda4af>"))
        assertTrue(languageText.contains("no-permission: \"<#fda4af>"))
    }

    @Test
    @DisplayName("语言文件中的每个玩家文本值都是 MiniMessage 片段")
    fun `every language value uses minimessage`() {
        val messageLines = languageText.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains(": \"") }
            // lang-version 不是玩家可见文案，是给 LangMigrator 比对的版本标记，
            // 值是纯版本号，不该按 MiniMessage 片段来要求。
            .filterNot { it.startsWith("lang-version:") }
            .toList()

        assertTrue(messageLines.isNotEmpty())
        assertTrue(
            messageLines.all { it.substringAfter(": \"").startsWith("<") },
            "发现没有以 MiniMessage 标签开头的语言值",
        )
    }

    @Test
    @DisplayName("棋子与阵营占位符自带颜色，模板不能再重复包颜色标签")
    fun `semantic placeholders carry their own minimessage style`() {
        val duplicateWrapper = Regex("<(?:gold|aqua)><(?:piece|side|winner|loser|captured)>")

        assertFalse(duplicateWrapper.containsMatchIn(languageText))
        assertTrue(languageText.contains("piece-red-king: \"<gold>帥</gold>\""))
        assertTrue(languageText.contains("piece-black-king: \"<aqua>將</aqua>\""))
        assertTrue(languageText.contains("side-red: \"<gold>红方</gold>\""))
        assertTrue(languageText.contains("side-black: \"<aqua>黑方</aqua>\""))
    }

    /**
     * 入座入口已从命令改成棋盘上方的可点击悬浮字。
     *
     * 原来这里守的是"入座只保留随机入口"，断言 help-join 存在。子命令被删掉之后
     * 那条断言就反了：帮助里列一条玩家敲不出效果的命令，比不列更容易误导。
     * 所以改成守新意图 —— 语言文件里不能再有任何指向该命令的文案。
     *
     * 箱子菜单（shift+右键棋盘）没有被删，它和悬浮字是两个入口指向同一个入座逻辑，
     * 所以 menu-join-* 继续守住：一条按钮名加按棋种分流的三条 lore。
     */
    @Test
    @DisplayName("入座改由悬浮字承担，语言文件不再指向已删除的入座命令")
    fun `joining is random and move history chat is absent`() {
        assertFalse(
            languageText.lineSequence().any { it.startsWith("help-join:") },
            "help-join 应随入座子命令一起删除",
        )
        // 连注释一起查：留着旧命令名会让后来的人以为那个入口还在
        assertFalse(
            languageText.contains("/chess join"),
            "入座命令已删除，语言文件里不该再出现它",
        )
        assertTrue(languageText.contains("menu-join-name: \"<gold>加入对局</gold>\""))
        // 入座 lore 按棋种分流：象棋分红黑，围棋与五子棋分黑白。三条都查，
        // 再反查围棋与五子棋那两条不能出现"红方" —— 那正是分流之前的错误表现，
        // 而且这种错不会报异常，只会在围棋盘上安静地写着象棋的说法。
        assertTrue(languageText.contains("menu-join-lore-xiangqi: \"<gray>点击后随机分配红方或黑方</gray>\""))
        assertTrue(languageText.contains("menu-join-lore-gomoku: \"<gray>点击后随机分配黑棋或白棋</gray>\""))
        assertTrue(languageText.contains("menu-join-lore-go: \"<gray>点击后随机分配黑棋或白棋</gray>\""))
        assertTrue(
            languageText.lineSequence()
                .filter { it.startsWith("menu-join-lore-gomoku:") || it.startsWith("menu-join-lore-go:") }
                .none { it.contains("红") },
            "围棋与五子棋盘上没有红子，入座 lore 不该出现象棋的红黑叫法",
        )
        // 按行首查而不是 contains：这两条守的是"没有 move-done / move-capture 这两个键"，
        // 而 contains 会被任何**以它们结尾**的键名误命中（debug-remove-done 就撞上了 move-done）。
        // 那种误报会让人以为走子播报又回到了聊天栏，排查方向全错。
        assertFalse(
            languageText.lineSequence().any { it.startsWith("move-done:") },
            "走子结果不进聊天栏，不该有 move-done 键",
        )
        assertFalse(
            languageText.lineSequence().any { it.startsWith("move-capture:") },
            "吃子结果不进聊天栏，不该有 move-capture 键",
        )
    }

    /**
     * 终局牌子与对应的终局播报必须同色。
     *
     * 一个终局会同时出现在两个地方：聊天栏发一条 `game-over-*`，棋盘上留一块
     * `label-finished-*`。两处配色一致，玩家才会把它们当成同一件事；配色不一致就像
     * 出了两个不同的结论。
     *
     * 这条测试真正防的是分流写错。`BoardLabelRenderer.labelKeyOf` 把九种 [GameResult]
     * 归到四条牌子文案上，其中三处特别容易归错，而且归错了程序不会报任何错，
     * 只会安静地显示错文案：
     * - 超时单列一条，因为 `game-over-timeout` 起手是失败粉 `#fda4af`，
     *   而其余判胜条目起手是 `#fbbf24`。把超时并进判胜，牌子就会跟聊天栏不同色。
     * - 弃局是"这局作废"，不认胜者。归进判胜等于凭空发一场胜绩。
     * - 围棋数子的 winner 可空，null 是平局，整类归判胜就会给平局硬安一个胜方。
     *
     * 这里比色值而不是比键名：色值是能从语言文件读出来的客观事实，
     * 而"哪个 result 该配哪个键"藏在渲染层的 when 里，测试的类路径上没有 Bukkit，
     * 碰不到那个类（`BoardLabelRenderer` 要操作 TextDisplay，paper-api 只在 compileOnly）。
     * 色值对上，至少保证四条牌子各自对齐了它该对齐的那一档播报。
     */
    @Test
    @DisplayName("终局牌子与对应的终局播报使用同一组渐变色")
    fun `finished labels match their announcement colors`() {
        val gradientOf = { key: String ->
            val line = languageText.lineSequence().firstOrNull { it.startsWith("$key:") }
            assertTrue(line != null, "语言文件里缺少 $key")
            val gradient = Regex("<gradient:(#[0-9a-f]{6}:#[0-9a-f]{6})>").find(line!!)
                ?.groupValues?.get(1)
            // 取不到就当场失败，不能返回 null 往下走：两个 null 会在 assertEquals 里比成相等，
            // 那样这条测试会在色值格式改动之后安静地不再检查任何东西
            assertTrue(gradient != null, "$key 的值应当以 <gradient:#色1:#色2> 形式写渐变")
            gradient
        }

        // 判胜：将死、困毙、认输、连五、数子有胜者共用一档，牌子跟着将死那条走
        assertEquals(
            gradientOf("game-over-checkmate"),
            gradientOf("label-finished-win"),
            "判胜牌子必须与判胜播报同色",
        )
        // 超时：起手是失败粉，与判胜那档刻意不同，所以牌子也得单列一条
        assertEquals(
            gradientOf("game-over-timeout"),
            gradientOf("label-finished-timeout"),
            "超时牌子必须与超时播报同色，这条色值和判胜不一样",
        )
        assertNotEquals(
            gradientOf("game-over-checkmate"),
            gradientOf("game-over-timeout"),
            "超时与判胜的播报本来就不同色，这是超时牌子必须单列的原因",
        )
        // 和棋：限着、满盘、数子平局共用一档
        assertEquals(
            gradientOf("game-over-draw-move-limit"),
            gradientOf("label-finished-draw"),
            "和棋牌子必须与和棋播报同色",
        )
        // 作废：弃局不认胜者，配色也和判胜、和棋都分开
        assertEquals(
            gradientOf("game-over-quit"),
            gradientOf("label-finished-void"),
            "弃局牌子必须与弃局播报同色",
        )
    }

    /**
     * 悬浮字是世界里的牌子，不能套用聊天栏的排版。
     *
     * 它显示在 TextDisplay 实体上，只有一行且居中：带 prefix 会把 `[棋]` 印在牌子上，
     * 带 `»` 分隔符则会占掉宽度把正文推偏。两者都是聊天栏才需要的东西。
     *
     * 牌子分两档，颜色上必须分得开：
     * - 能点的 `label-join`、`label-start` 与常驻报状态的 `label-playing` 用单色 gold。
     *   前两条是按钮，第三条整局挂在棋盘上方，都不该抢棋盘视线。
     * - 四条 `label-finished-*` 用渐变，与对应的 `game-over-*` 播报同色，
     *   让聊天栏那条和棋盘上这块看起来是同一件事。精确的渐变键集合由
     *   gradients stay ceremonial 那条测试兜住，这里只查分隔符、前缀与单色档的用色。
     *
     * 数量写成精确值是有意的：新增一条牌子文案就必须回来想清楚它属于哪一档，
     * 而不是随手加完就走 —— 漏了归档，牌子就会在颜色语义上和别的档混在一起。
     */
    @Test
    @DisplayName("棋盘悬浮字不带聊天栏前缀与分隔符")
    fun `board labels are not styled like chat lines`() {
        val labels = languageText.lineSequence()
            .filter { it.startsWith("label-") }
            .toList()

        assertEquals(
            7,
            labels.size,
            "应当有 label-join、label-start、label-playing 与四条 label-finished-* 共七条悬浮字文案",
        )
        assertTrue(labels.none { it.contains("»") }, "悬浮字不该带聊天栏分隔符")
        assertTrue(labels.none { it.contains("<prefix>") }, "悬浮字不该带前缀")

        val plainLabels = labels.filter { !it.startsWith("label-finished-") }
        assertEquals(3, plainLabels.size, "单色档应当正好是 label-join、label-start、label-playing")
        assertTrue(
            plainLabels.all { it.contains("<gold>") },
            "按钮与对局中状态这一档统一用 gold，不用渐变",
        )
        assertTrue(
            plainLabels.none { it.contains("<gradient:") },
            "常驻显示的牌子不用渐变，渐变留给终局那一档",
        )
    }
}
