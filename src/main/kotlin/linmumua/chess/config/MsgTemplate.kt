package linmumua.chess.config

import linmumua.chess.game.GameType
import linmumua.chess.rule.core.Piece
import linmumua.chess.rule.core.Side

/**
 * 文案模板的纯逻辑：占位符替换与语言键拼装。
 *
 * 单独成一个对象，是因为 [Msg] 需要持有 Adventure 的 MiniMessage 解析器，
 * 而 Adventure 由服务端提供、单测 classpath 上没有。
 * 两者放在同一个 object 里，类初始化会因为找不到 MiniMessage 而整体失败，
 * 把本来能单测的纯函数一起带崩（这个坑已经踩过一次）。
 *
 * 所以这里只放不依赖任何平台类型的逻辑，保证可单测；
 * 需要发消息、需要解析组件的部分留在 [Msg]。
 */
object MsgTemplate {

    /**
     * 把命名占位符替换成实际值。
     *
     * 占位符写法是 `<name>`，与 MiniMessage 标签同形；未知标签会被解析器忽略，
     * 所以漏传一个占位符不会把整条消息打崩，只是那一段不显示。
     */
    fun render(template: String, placeholders: Array<out Pair<String, Any?>>): String {
        if (placeholders.isEmpty()) {
            return template
        }
        var result = template
        for ((name, value) in placeholders) {
            result = result.replace("<$name>", value?.toString() ?: "")
        }
        return result
    }

    /**
     * 棋子名在语言键里的阵营部分。
     *
     * [Side] 按行棋先后命名（FIRST/SECOND），但象棋的棋子键沿用"红/黑"这套叫法 ——
     * 服主的 `lang/zh_CN.yml` 里写的是 `piece-red-horse`。
     * 所以这里显式做一层映射，不能图省事用 `side.name.lowercase()`：
     * 那样键会变成 `piece-first-horse`，服主的语言文件里全部取不到，棋子名会集体退成兜底样式。
     *
     * 五子棋与围棋的棋子键不走这里，它们有自己的黑白子命名。
     *
     * 阵营名（`side-*`）不再用这个映射：它按棋种分流，见 [GameType.sideSlug]。
     */
    private fun sideSlug(side: Side): String = if (side == Side.FIRST) "red" else "black"

    /** 棋子名的语言键，形如 `piece-red-horse` */
    fun pieceKey(piece: Piece): String =
        "piece-${sideSlug(piece.side)}-${piece.type.name.lowercase()}"

    /**
     * 阵营名的语言键，象棋形如 `side-red`，围棋与五子棋形如 `side-stone-black`。
     *
     * 必须带上棋种：阵营名的颜色叫法由棋种决定（见 [GameType.sideSlug]），
     * 早先这里只按 [Side] 取键，围棋黑方会显示成"红方"。
     */
    fun sideKey(side: Side, gameType: GameType): String = "side-${gameType.sideSlug(side)}"
}
