package linmumua.chess.config

import linmumua.chess.game.GameType
import linmumua.chess.rule.core.Piece
import linmumua.chess.rule.core.Side
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import taboolib.common.platform.function.warning
import taboolib.platform.util.asLangTextOrNull

/**
 * 玩家可见文本的唯一出口。
 *
 * 代码里只出现键名，文案全部来自 `lang/zh_CN.yml`。任何地方要给玩家发话都走这里，
 * 不允许自己拼字符串再 sendMessage。
 *
 * 为什么不直接用 TabooLib 的 `sendLang`：
 * 语言文件用的是命名占位符（`<piece>`、`<count>`），而 TabooLib 的 `TypeText`
 * 末尾只做 `replaceWithOrder`，也就是只认 `{0}` 这种顺序占位符
 * （已按 minecraft-i18n 的 TypeText 字节码确认）。
 * 命名占位符可读性更好，也不怕以后调整语序，所以这里自己做一次替换。
 *
 * 为什么不用 TabooLib 的 `SimpleComponent`（`String.component()`）：
 * 它不是 MiniMessage。按 DefaultSimpleComponent 的字节码，它的分隔符是
 * `[` `]` `(` `)` `;`，语法形如 `[文字](color=gray)`；
 * 遇到 `<gray>` 这种标签既不解析也不报错，直接当普通文字发出去，
 * 玩家聊天栏里会看到一堆裸的 `<gray></gray>`。
 * 本项目文案规范写死了用 MiniMessage 标签，所以这里直接用 Adventure 的
 * [MiniMessage]（Paper 自带，不需要额外依赖）解析后发送。
 */
object Msg {

    /** 前缀键，所有面向玩家的提示都自动带上 */
    private const val PREFIX_KEY = "prefix"

    /**
     * MiniMessage 解析器。
     *
     * 用 `miniMessage()` 的标准实例，标签集与 MiniMessage 文档一致，
     * 服主照文档写 `<gradient>`、`<hover>` 都能用。
     *
     * 必须是 `by lazy` 而不是直接赋值：本对象里 [render]、[pieceKey] 这些是纯函数，
     * 单测会直接调。Adventure 的实现由服务端提供，测试 classpath 上没有，
     * 一旦写成字段初始化，object 的类初始化就会在测试里抛
     * ExceptionInInitializerError，把同一对象内的纯函数一起带崩。
     * 惰性化之后，不发消息就不会碰 Adventure。
     */
    private val miniMessage by lazy { MiniMessage.miniMessage() }

    /**
     * 把一段 MiniMessage 文本解析成组件。
     *
     * 解析失败不抛给调用方：文案是服主可改的，一处标签写错不该让整个功能崩。
     * 但必须记日志，否则服主只会看到消息莫名变样却不知道哪里错了。降级内容先转义
     * MiniMessage 标签再交回同一解析器，保证玩家可见文本始终只走 MiniMessage 链路。
     */
    private fun parse(text: String): Component =
        try {
            miniMessage.deserialize(text)
        } catch (error: Exception) {
            warning("文案解析失败，已转义 MiniMessage 标签：$text（${error.message}）")
            miniMessage.deserialize("<gray>${miniMessage.escapeTags(text)}</gray>")
        }

    /** 占位符替换，纯逻辑在 [MsgTemplate]，这里只转发 */
    fun render(template: String, placeholders: Array<out Pair<String, Any?>>): String =
        MsgTemplate.render(template, placeholders)

    /**
     * 取一条渲染好的文案，不带前缀。
     *
     * 键不存在时返回 null 并记日志：漏配语言键属于开发期错误，
     * 不能静默发一条空消息让人以为功能没触发。
     */
    fun text(sender: CommandSender, key: String, vararg placeholders: Pair<String, Any?>): String? {
        val template = sender.asLangTextOrNull(key)
        if (template == null) {
            warning("语言文件缺少键：$key")
            return null
        }
        return render(template, placeholders)
    }

    /**
     * 取一条不带前缀的 Adventure 组件。
     *
     * 箱子菜单标题、按钮名与 Lore 也必须复用同一个 MiniMessage 解析器，不能在 UI 层另建
     * 文本链路。缺键时沿用 [text] 的日志并返回 null，由调用方停止打开不完整菜单。
     */
    fun component(sender: CommandSender, key: String, vararg placeholders: Pair<String, Any?>): Component? =
        text(sender, key, *placeholders)?.let(::parse)

    /**
     * 给一个接收者发消息，自动带前缀。
     *
     * 前缀与正文拼成一段再解析，而不是分别解析后拼组件：
     * 拼字符串只解析一次，也让前缀里的标签作用域能自然结束在前缀内。
     *
     * `CommandSender` 在 Paper 上本身就是 Adventure 的 Audience，
     * 直接 sendMessage(Component) 即可，不需要再过 TabooLib 的适配层。
     */
    fun send(sender: CommandSender, key: String, vararg placeholders: Pair<String, Any?>) {
        val body = text(sender, key, *placeholders) ?: return
        val prefix = sender.asLangTextOrNull(PREFIX_KEY) ?: ""
        sender.sendMessage(parse(prefix + body))
    }

    /** 不带前缀地发一条，用于终局播报这类需要独立成行的内容 */
    fun sendRaw(sender: CommandSender, key: String, vararg placeholders: Pair<String, Any?>) {
        val body = text(sender, key, *placeholders) ?: return
        sender.sendMessage(parse(body))
    }

    /** 批量发送，用于对局内双方加观战者的播报 */
    fun sendAll(
        receivers: Collection<CommandSender>,
        key: String,
        vararg placeholders: Pair<String, Any?>,
    ) {
        receivers.forEach { send(it, key, *placeholders) }
    }

    /**
     * 把一条文案发到动作栏，也就是聊天栏上方那一行。
     *
     * 用于对局内的即时状态提示：这类信息只在当下有意义，过一会儿就没用了，
     * 塞进聊天栏会把真正需要留存的记录（终局播报、命令回执）挤走。
     * 动作栏会自动淡出，不留痕迹，正好合适。
     *
     * 不带前缀：动作栏只有一行且居中，`[棋]` 前缀会白占宽度还把正文推偏。
     *
     * 只接受 [org.bukkit.entity.Player]：动作栏是玩家客户端的 HUD 元素，
     * 控制台与命令方块没有这个概念。
     */
    fun actionBar(player: Player, key: String, vararg placeholders: Pair<String, Any?>) {
        val body = text(player, key, *placeholders) ?: return
        player.sendActionBar(parse(body))
    }

    /** 批量发动作栏，用于对局内双方加观战者共享的即时状态 */
    fun actionBarAll(
        players: Collection<Player>,
        key: String,
        vararg placeholders: Pair<String, Any?>,
    ) {
        players.forEach { actionBar(it, key, *placeholders) }
    }

    /** 棋子名的语言键，形如 `piece-red-horse` */
    fun pieceKey(piece: Piece): String = MsgTemplate.pieceKey(piece)

    /** 阵营名的语言键，象棋形如 `side-red`，围棋与五子棋形如 `side-stone-black` */
    fun sideKey(side: Side, gameType: GameType): String = MsgTemplate.sideKey(side, gameType)

    /** 取棋子的 MiniMessage 显示片段；缺键时仍按阵营色生成带标签的传统名 */
    fun pieceName(sender: CommandSender, piece: Piece): String =
        sender.asLangTextOrNull(pieceKey(piece)) ?: styledFallback(piece.side, piece.type.nameOf(piece.side))

    /**
     * 取阵营的 MiniMessage 显示片段；缺键时同样保留阵营色。
     *
     * 棋种必须传进来：阵营名的叫法按棋种分流（象棋红/黑，围棋与五子棋黑/白），
     * 见 [GameType.sideSlug]。
     */
    fun sideName(sender: CommandSender, side: Side, gameType: GameType): String =
        sender.asLangTextOrNull(sideKey(side, gameType)) ?: styledFallback(side, side.name)

    /** 先手用 gold、后手用 aqua；转义文本后再组成可安全嵌入模板的 MiniMessage 片段 */
    private fun styledFallback(side: Side, text: String): String {
        val color = if (side == Side.FIRST) "gold" else "aqua"
        return "<$color>${miniMessage.escapeTags(text)}</$color>"
    }
}
