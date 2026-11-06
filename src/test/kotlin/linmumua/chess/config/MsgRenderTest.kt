package linmumua.chess.config

import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * 命名占位符替换测试。
 *
 * 语言文件用 `<piece>` 这类命名占位符，而 TabooLib 原生只支持 `{0}` 顺序占位符，
 * 所以替换是自己做的。替换错了玩家会看到 `<piece>` 这种原文，
 * 或者更糟 —— 消息里少一段关键信息。
 *
 * 打的是 [MsgTemplate] 而不是 [Msg]：后者持有 Adventure 的 MiniMessage 解析器，
 * 而 Adventure 由服务端提供，单测 classpath 上没有，碰它会直接类初始化失败。
 */
class MsgRenderTest {

    /** 单个占位符替换 */
    @Test
    fun `替换单个占位符`() {
        val result = MsgTemplate.render("选中 <piece>", arrayOf("piece" to "馬"))
        assertEquals("选中 馬", result)
    }

    /** 多个占位符一次替换完 */
    @Test
    fun `替换多个占位符`() {
        val result = MsgTemplate.render(
            "选中 <piece>，可落点 <count> 处",
            arrayOf("piece" to "車", "count" to 5),
        )
        assertEquals("选中 車，可落点 5 处", result)
    }

    /** 同一个占位符出现多次要全部替换 */
    @Test
    fun `重复出现的占位符全部替换`() {
        val result = MsgTemplate.render("<side> 胜，<side> 先走", arrayOf("side" to "红方"))
        assertEquals("红方 胜，红方 先走", result)
    }

    /**
     * 没有占位符时原样返回。
     *
     * 大部分提示是固定句子，这条路径最常走，不能因为空数组就出问题。
     */
    @Test
    fun `没有占位符时原样返回`() {
        val template = "<gray>取消选中</gray>"
        assertEquals(template, MsgTemplate.render(template, emptyArray()))
    }

    /**
     * 棋子与阵营名本身就是 MiniMessage 片段，模板不能再套一层固定颜色。
     *
     * 这样红方的 gold 与黑方的 aqua 都由占位符值决定；替换完成后整条文本只解析一次。
     */
    @Test
    fun `MiniMessage片段能直接嵌入模板`() {
        val result = MsgTemplate.render(
            "<piece> <dark_gray>»</dark_gray> <gray>落到 </gray><aqua><target></aqua>",
            arrayOf("piece" to "<gold>炮</gold>", "target" to "5,3"),
        )
        assertEquals(
            "<gold>炮</gold> <dark_gray>»</dark_gray> <gray>落到 </gray><aqua>5,3</aqua>",
            result,
        )
        assertTrue(result.contains("<gold>炮</gold>"), "占位符携带的颜色标签必须保留给组件解析器")
        assertTrue(result.contains("<gray>"))
    }

    /**
     * 占位符值为 null 时替换成空串。
     *
     * 宁可少一段文字，也不要让 `null` 这个词出现在玩家眼前。
     */
    @Test
    fun `空值替换成空串`() {
        val result = MsgTemplate.render("胜者 <winner>", arrayOf("winner" to null))
        assertEquals("胜者 ", result)
    }

    /**
     * 传了模板里没有的占位符不报错。
     *
     * 调用方多传一个参数属于无害情况，不该抛异常打断消息发送。
     */
    @Test
    fun `多传的占位符被忽略`() {
        val result = MsgTemplate.render("固定文案", arrayOf("piece" to "馬"))
        assertEquals("固定文案", result)
    }

    /** 数字与布尔值都按 toString 处理 */
    @Test
    fun `非字符串值按文本处理`() {
        val result = MsgTemplate.render("<a>/<b>", arrayOf("a" to 3, "b" to true))
        assertEquals("3/true", result)
    }

    /** 棋子与阵营的语言键拼法要稳定，改了会导致全部棋子名取不到 */
    @Test
    fun `棋子与阵营语言键拼法正确`() {
        val piece = linmumua.chess.rule.core.Piece(
            linmumua.chess.rule.core.PieceType.HORSE,
            linmumua.chess.rule.core.Side.FIRST,
        )
        assertEquals("piece-red-horse", MsgTemplate.pieceKey(piece))
        assertEquals(
            "side-black",
            MsgTemplate.sideKey(linmumua.chess.rule.core.Side.SECOND, linmumua.chess.game.GameType.XIANGQI),
        )
    }

    /**
     * 阵营名必须按棋种分流，围棋盘上不能出现"红方"。
     *
     * 这是修过的 bug：阵营名的键早先写死成 FIRST→red，因为象棋是红先。
     * 但 [linmumua.chess.rule.core.Side] 只表示行棋先后，围棋的先手是黑棋 ——
     * 于是围棋对局的提示语与棋盘悬浮字上，黑方一律显示成"红方"。
     *
     * 这条用例的意义不在"函数返回了什么字符串"，而在于把"颜色由棋种决定、
     * 不由行棋先后决定"这条约束钉住：谁要是把映射搬回 Side 上，这里立刻红。
     */
    @Test
    fun `阵营名按棋种分流_围棋先手是黑棋不是红方`() {
        val first = linmumua.chess.rule.core.Side.FIRST
        val second = linmumua.chess.rule.core.Side.SECOND

        // 象棋：红先黑后，沿用服主语言文件里原有的键
        assertEquals("side-red", MsgTemplate.sideKey(first, linmumua.chess.game.GameType.XIANGQI))
        assertEquals("side-black", MsgTemplate.sideKey(second, linmumua.chess.game.GameType.XIANGQI))

        // 围棋与五子棋：黑先白后，且先手绝不能取到象棋那套红方的键
        for (type in listOf(linmumua.chess.game.GameType.GO, linmumua.chess.game.GameType.GOMOKU)) {
            assertEquals("side-stone-black", MsgTemplate.sideKey(first, type))
            assertEquals("side-stone-white", MsgTemplate.sideKey(second, type))
            assertNotEquals("side-red", MsgTemplate.sideKey(first, type))
        }
    }

    /** 新增的阵营名键必须真的躺在语言文件里，否则运行期只会静默退成兜底样式 */
    @Test
    fun `围棋五子棋的阵营名键已进语言文件`() {
        val languageText = java.io.File("src/main/resources/lang/zh_CN.yml").readText()
        assertTrue(languageText.contains("side-stone-black:"))
        assertTrue(languageText.contains("side-stone-white:"))
    }
}
