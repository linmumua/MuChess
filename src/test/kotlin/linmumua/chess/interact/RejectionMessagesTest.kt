package linmumua.chess.interact

import java.io.File
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 走棋交互不再往聊天栏发提示。
 *
 * 这组测试守的是一次**产品决定**：选中一枚子时全部合法落点已经用白圈标出、可吃子用红框标出，
 * 点在标记之外就是不能走。再用聊天栏复述"马腿被别住""这不是你的棋子"是同一信息说两遍，
 * 而落子很频繁，一局下来会把聊天栏刷满，把终局播报和命令回执挤走。
 *
 * 唯一保留的是"将军"，因为那是棋盘上看不出来的信息，而且它走动作栏而不是聊天栏。
 *
 * 这组测试原本守的是相反的要求（每种非法原因都要有独立话术）。需求改了，
 * 测试跟着改成守新意图，而不是删掉留一片没有覆盖的空白。
 */
class RejectionMessagesTest {

    /** 语言文件里实际存在的键 */
    private val languageKeys: Set<String> by lazy { loadLanguageKeys() }

    /** 交互层源码，用来确认调用点真的没了 */
    private val moveExecutorSource: String by lazy {
        File("src/main/kotlin/linmumua/chess/interact/MoveExecutor.kt").readText(Charsets.UTF_8)
    }

    /** 命令层源码，用来确认命令回执走的是哪个通道 */
    private val commandHandlerSource: String by lazy {
        File("src/main/kotlin/linmumua/chess/command/ChessCommandHandler.kt").readText(Charsets.UTF_8)
    }

    /** CE 家具拆除路径的源码，board-remove-in-game 的另一个调用点在这里 */
    private val placeListenerSource: String by lazy {
        File("src/main/kotlin/linmumua/chess/compat/CraftEnginePlaceListener.kt").readText(Charsets.UTF_8)
    }

    /**
     * 语言文件里不该再有 move-illegal-* 这批键。
     *
     * 留着不用的文案会让后来的人以为这个功能还在，改文案改了半天发现游戏里根本不显示。
     */
    @Test
    @DisplayName("语言文件不再保留非法走法提示")
    fun `illegal move keys are gone`() {
        val leftover = languageKeys.filter { it.startsWith("move-illegal") }
        assertTrue(
            leftover.isEmpty(),
            "语言文件里仍有非法走法提示，但交互层已经不发了：$leftover",
        )
    }

    /**
     * 走棋交互期间会高频触发的几条提示都不该再存在于语言文件。
     *
     * `not-in-game` 不在这个名单里：它是 /chess leave、/chess resign 的命令回执，
     * 属于命令层而不是走棋交互，必须保留，否则玩家敲了命令没反应会以为插件坏了。
     * 它保留但改走动作栏，见 command replies use the action bar 那条。
     */
    @Test
    @DisplayName("走棋交互期间的高频提示都已移除")
    fun `high frequency interaction prompts are gone`() {
        val removed = listOf("not-your-turn", "not-your-piece", "piece-no-moves", "check-self-warning")
        val leftover = removed.filter { it in languageKeys }
        assertTrue(leftover.isEmpty(), "以下走棋交互提示应已删除：$leftover")

        assertTrue(
            "not-in-game" in languageKeys,
            "not-in-game 是命令回执不是走棋提示，必须保留",
        )
    }

    /**
     * 交互层不该再调用 Msg.send。
     *
     * 这是防腐的关键一条：只删语言键的话，代码里的 `Msg.send(player, "not-your-piece")`
     * 会变成"缺键"而只在控制台刷警告，玩家侧看起来"正常"，很难发现。
     * 终局播报用的是 sendRaw，不受这条约束。
     */
    @Test
    @DisplayName("交互层不再调用聊天栏发送")
    fun `interaction layer does not send chat messages`() {
        assertTrue(
            !moveExecutorSource.contains("Msg.send("),
            "MoveExecutor 仍在调用 Msg.send，走棋交互不该再往聊天栏发东西",
        )
    }

    /**
     * 将军必须走动作栏，而且只有一条。
     *
     * 动作栏会自动淡出、不占聊天记录，正好适合这种"过一手就没意义"的即时状态。
     */
    @Test
    @DisplayName("将军只有一条且发到动作栏")
    fun `check warning goes to the action bar`() {
        assertTrue("check-warning" in languageKeys, "将军提示必须存在")
        assertTrue(
            moveExecutorSource.contains("Msg.actionBarAll(session.audience(), \"check-warning\")"),
            "将军必须通过动作栏发给全场",
        )
    }

    /**
     * 将军文案不该再带阵营占位符。
     *
     * 全场同一句，谁被将棋盘上一眼看得出来；留着 <side> 会渲染成空串或报缺占位符。
     */
    @Test
    @DisplayName("将军文案不带阵营占位符")
    fun `check warning has no side placeholder`() {
        val template = loadLanguageValue("check-warning")
        assertTrue(template != null, "读不到 check-warning 的文案")
        assertTrue(
            !template!!.contains("<side>"),
            "将军文案不该再带 <side>，现在全场共用一句：$template",
        )
    }

    /**
     * 重复入座整条取消，语言键与调用点都不留。
     *
     * 这条路径是**被动**触发的：玩家点棋盘上方的牌子、点棋盘家具，手一快就是连点几下。
     * 而"你已经在这局里"这件事，自己的座位、盘面、牌子上的字全都在说，
     * 再刷一行是把玩家已经看见的信息复述一遍，连点时直接糊满屏。
     *
     * 与 `not-in-game` 的区别在触发方式，不在内容重要性：那条是玩家**主动**敲命令后的答复，
     * 没有任何动静会让人以为插件坏了；这条点一下就触发，没有"有人在等一个答复"这回事。
     * 所以那条改走动作栏保留，这条整条删。
     */
    @Test
    @DisplayName("重复入座既没有语言键也没有调用点")
    fun `duplicate seat prompt is gone entirely`() {
        assertTrue(
            "seat-already" !in languageKeys,
            "seat-already 应当整条删除：重复入座是被动触发，不给反馈",
        )
        // 查带引号的形式，只命中字符串字面量；代码注释里提到这个键名不算调用点
        assertTrue(
            !commandHandlerSource.contains("seat-already\""),
            "命令层仍在发 seat-already，这条提示已经取消",
        )
    }

    /**
     * 命令回执与拆盘拦截走动作栏，既不删也不留在聊天栏。
     *
     * 这两条和 move-illegal-* 那批的差别在于：删掉它们会让玩家看到"什么都没发生"。
     * - `not-in-game` 是玩家敲了 /chess leave、/chess resign、/chess pass 之后的答复。
     * - `board-remove-in-game` 是拆棋盘被拦下的原因，玩家眼里只看到家具没掉下来。
     *
     * 但它们同样不该占聊天记录：两条都可能连着触发好几次（连敲命令、连挖几下家具），
     * 内容又只在当下有意义。动作栏正好满足"必须有反馈 + 过一会儿自己消失"。
     */
    @Test
    @DisplayName("命令回执与拆盘拦截走动作栏而不是聊天栏")
    fun `command replies use the action bar`() {
        for (key in listOf("not-in-game", "board-remove-in-game")) {
            assertTrue(key in languageKeys, "$key 是必须给玩家的反馈，不能删")
            assertTrue(
                !commandHandlerSource.contains("Msg.send(player, \"$key\")"),
                "$key 不该再走聊天栏，改用 Msg.actionBar",
            )
            assertTrue(
                commandHandlerSource.contains("Msg.actionBar(player, \"$key\")"),
                "$key 必须通过动作栏发给玩家",
            )
        }

        // 挖家具那条路径在 CE 监听里，和 /chess remove 是同一条键的两个入口。
        // 只改一处会出现一个入口走动作栏、另一个走聊天栏的分裂表现。
        assertTrue(
            !placeListenerSource.contains("Msg.send(player, \"board-remove-in-game\")"),
            "拆家具被拦下时仍在走聊天栏",
        )
        assertTrue(
            placeListenerSource.contains("Msg.actionBar(player, \"board-remove-in-game\")"),
            "拆家具被拦下时也必须走动作栏，与 /chess remove 保持同一个通道",
        )
    }

    /** 从语言文件里读出全部顶层键 */
    private fun loadLanguageKeys(): Set<String> = languageLines()
        .mapNotNull { line -> Regex("^([a-z0-9-]+):").find(line)?.groupValues?.get(1) }
        .toSet()

    /** 读一个键的原始值，用于检查占位符 */
    private fun loadLanguageValue(key: String): String? = languageLines()
        .firstOrNull { it.startsWith("$key:") }
        ?.removePrefix("$key:")
        ?.trim()
        ?.trim('"')

    /**
     * 逐行读语言文件。
     *
     * 不用 SnakeYAML：它在本项目是 compileOnly + @RuntimeDependency 运行期下载，
     * 测试 classpath 上没有，用了会 NoClassDefFoundError。
     */
    private fun languageLines(): List<String> =
        File("src/main/resources/lang/zh_CN.yml").readLines(Charsets.UTF_8).map(String::trim)
}
