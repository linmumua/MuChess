package linmumua.chess.rule.core

/**
 * 象棋棋子种类。
 *
 * [redName] / [blackName] 是传统写法差异，仅用于日志与棋谱，
 * 玩家可见文本一律走语言文件，不允许直接拿这两个字段发消息。
 */
enum class PieceType(val redName: String, val blackName: String, val code: Char) {
    /** 帥 / 將 */
    KING("帥", "將", 'k'),

    /** 仕 / 士 */
    ADVISOR("仕", "士", 'a'),

    /** 相 / 象 */
    ELEPHANT("相", "象", 'e'),

    /** 馬 */
    HORSE("馬", "馬", 'h'),

    /** 車 */
    CHARIOT("車", "車", 'r'),

    /** 炮 */
    CANNON("炮", "炮", 'c'),

    /** 兵 / 卒 */
    PAWN("兵", "卒", 'p'),

    /**
     * 五子棋与围棋的素色子。
     *
     * 这两个棋种的子没有种类之分，只按阵营分黑白。单独给一个种类而不是借用 [PAWN]，
     * 是因为渲染层要靠它挑素色圆盘外观 —— 借用兵会让五子棋的子长成象棋的"兵"。
     *
     * 棋谱字符用 's'，与象棋七种都不冲突。
     */
    STONE("子", "子", 's'),
    ;

    /** 取该方的传统名称 */
    fun nameOf(side: Side): String = if (side == Side.FIRST) redName else blackName
}

/**
 * 棋盘上的一枚具体棋子。
 */
data class Piece(val type: PieceType, val side: Side) {

    /**
     * 资源 id 片段，形如 `red_horse`。
     * CraftEngine 物品 id 由此拼出，规则层本身不关心资源。
     *
     * 象棋棋子的阵营段固定用"红/黑"叫法，不能用 [Side] 的枚举名 ——
     * [Side] 按行棋先后命名（FIRST/SECOND），而 CE 资源 id 与资源包里写的是
     * `red_king`、`black_horse` 这一套。跟着枚举名走会变成 `first_king`，
     * 结果是 CE 按 id 取物品全部取不到，棋子在世界里直接不显示。
     *
     * [PieceType.STONE] 走另一套：素色子按**实际颜色**命名（`black_stone`/`white_stone`）。
     * 五子棋与围棋的先行方执黑，所以 FIRST 对应 black_stone，与象棋正好相反。
     */
    val assetKey: String
        get() = if (type == PieceType.STONE) {
            if (side == Side.FIRST) "black_stone" else "white_stone"
        } else {
            "${if (side == Side.FIRST) "red" else "black"}_${type.name.lowercase()}"
        }

    /** 棋谱字符：红方大写，黑方小写 */
    val code: Char get() = if (side == Side.FIRST) type.code.uppercaseChar() else type.code

    override fun toString(): String = "${type.nameOf(side)}($side)"
}
