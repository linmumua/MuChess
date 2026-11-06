package linmumua.chess.rule.core

/**
 * 一步棋。
 *
 * [captured] 为被吃掉的棋子，没吃子时为 null。
 * 该字段由棋盘在生成走法时填好，撤销走法时用它复原，
 * 因此不允许调用方自行伪造。
 */
data class Move(
    val from: Square,
    val to: Square,
    val piece: Piece,
    val captured: Piece? = null,
    /**
     * 是否为虚着（围棋的 pass）。
     *
     * 只有围棋会置 true。为 true 时 [from]、[to]、[captured] **全部无意义** ——
     * 虚着不落子也不吃子，唯一有效的信息是 [piece] 的 `side`，即谁虚的这一手。
     *
     * 为什么需要这个字段而不是用坐标表达：落子式棋种（五子棋、围棋）本身就是
     * `from == to`，靠坐标区分不开"下在这一点"和"这手不下"；而盘外哨兵坐标会让
     * `board[square]` 直接数组越界。带默认值的布尔字段对现有两个棋种零影响，
     * 也让"这是一手虚着"由类型系统表达，不依赖坐标魔数约定。
     */
    val pass: Boolean = false,
) {
    /** 是否为吃子着法 */
    val isCapture: Boolean get() = captured != null

    override fun toString(): String = when {
        pass -> "$piece pass"
        captured != null -> "$piece $from->$to x$captured"
        else -> "$piece $from->$to"
    }
}
