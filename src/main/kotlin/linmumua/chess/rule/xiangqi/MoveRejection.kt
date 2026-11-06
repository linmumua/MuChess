package linmumua.chess.rule.xiangqi

/**
 * 着法被否掉的原因。
 *
 * 交互层要按不同原因给不同提示（蹩马腿和送将必须是两条不一样的话），
 * 所以规则层不能只回一个 false。这里只给枚举，文案键由交互层映射到 `lang/zh_CN.yml`，
 * 规则层不出现任何玩家可见文本。
 *
 * 三个棋种共用这一份枚举：前几项（起点没子、不该你走、终点不在盘上等）是通用的，
 * 蹩马腿/塞象眼一类只有象棋会返回，占位已满/禁自杀一类只有五子棋与围棋会返回。
 * 没有按棋种拆成三份，是因为交互层的消息映射只想认一个类型 ——
 * 拆开会让 `when` 分支跟着棋种翻倍。
 */
enum class MoveRejection {

    /** 起点没有棋子 */
    EMPTY_ORIGIN,

    /** 起点的子不属于行棋方 */
    NOT_OWN_PIECE,

    /** 现在不该这一方走 */
    WRONG_TURN,

    /** 起点终点相同 */
    SAME_SQUARE,

    /** 终点不在棋盘上 */
    OFF_BOARD,

    /** 终点是自己的子 */
    CAPTURE_OWN_PIECE,

    /** 该子走不出这个形状 */
    INVALID_PATTERN,

    /** 帥/將 出了九宫 */
    KING_OUT_OF_PALACE,

    /** 仕/士 出了九宫 */
    ADVISOR_OUT_OF_PALACE,

    /** 相/象 过河 */
    ELEPHANT_CROSSED_RIVER,

    /** 塞象眼：田字中心有子 */
    ELEPHANT_EYE_BLOCKED,

    /** 蹩马腿：马腿位置有子 */
    HORSE_LEG_BLOCKED,

    /** 車或不吃子的炮，路上有子 */
    PATH_BLOCKED,

    /** 炮吃子但中间没有炮架 */
    CANNON_NO_SCREEN,

    /** 炮吃子但中间隔了两枚以上 */
    CANNON_TOO_MANY_SCREENS,

    /** 兵/卒 后退 */
    PAWN_BACKWARD,

    /** 兵/卒 未过河就横走 */
    PAWN_SIDEWAYS_BEFORE_RIVER,

    /** 送将：走完己方将处于被攻击状态，含飞将对脸与不应将 */
    SELF_CHECK,

    /** 局面里找不到行棋方的将，属于摆局面出错 */
    MISSING_KING,

    /** 落子点已有子：五子棋与围棋专用，它们只能下在空点 */
    SQUARE_OCCUPIED,

    /** 围棋禁自杀：提掉对方无气子之后，自己这块仍然没气 */
    GO_SUICIDE,

    /** 围棋打劫：这一手会让局面立刻还原成上一手之前的样子 */
    GO_KO,
}
