package linmumua.chess.rule.core

/**
 * 对局双方，按行棋先后而不是颜色命名。
 *
 * 三个棋种的"先行方"颜色并不一致：象棋先行是红方，五子棋与围棋先行是黑方。
 * 早先这里叫 RED/BLACK，五子棋只能靠注释约定"RED 其实是黑棋"，读代码容易记错，
 * 所以改成中性的 [FIRST]/[SECOND]，颜色留给各棋种自己的显示层去映射。
 *
 * 玩家可见的阵营名一律走语言文件，代码里不出现"红/黑"字样。
 */
enum class Side {
    /** 先行方：象棋红方、五子棋黑棋、围棋黑棋 */
    FIRST,

    /** 后行方：象棋黑方、五子棋白棋、围棋白棋 */
    SECOND,
    ;

    /** 另一方 */
    val opposite: Side
        get() = if (this == FIRST) SECOND else FIRST
}
