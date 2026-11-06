package linmumua.chess.interact

import linmumua.chess.rule.core.Board
import linmumua.chess.rule.core.Move
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.core.Square
import linmumua.chess.rule.xiangqi.CheckDetector
import linmumua.chess.rule.xiangqi.MoveRejection

/**
 * 否决原因 → 语言键的映射。
 *
 * **当前交互层不使用本表。** 走棋交互已改成"不出声"：选中一枚子时全部合法落点用白圈标出、
 * 可吃子用红框标出，点在标记之外就是不能走，[MoveExecutor] 的所有 rejected 分支都是 Unit，
 * 不再往聊天栏发文案（见 RejectionMessagesTest 守的那条产品决定）。因此本对象在 main 代码里
 * 没有任何调用方，`move-illegal*` 这批键也已经从 `lang/zh_CN.yml` 删掉，只剩围棋那两条
 * `place-illegal-suicide` / `place-illegal-ko` 还在语言文件里。
 *
 * 保留不删是为了留住这份"哪个原因该说哪句话"的判断：这些合并与拆分的取舍
 * （见 [keyOf] 与 [selfCheckKey] 的注释）是照着规则推出来的，删了以后想恢复文字提示
 * 得重新推一遍。要恢复时需要同步补回对应语言键，不能只改代码。
 *
 * 下面这段是当初设计这张表时的依据，行为改动前后都成立，一并留着：
 *
 * 规则层只回一个 [MoveRejection]，文案在语言文件里，这里是两者唯一的对照表。
 * 项目曾要求"蹩马腿和送将必须给不同的话"，所以这张表按原因逐条列全，
 * 不允许出现"所有非法都发 move-illegal"这种偷懒写法。
 *
 * 用 `when` 而不是 Map 是有意的：`when` 对枚举做穷尽检查，
 * 以后规则层加一个新原因，这里编译期就会报错，不会静默漏掉一条文案。
 */
object RejectionMessages {

    /** 兜底键，只在语言文件缺具体键时使用 */
    const val FALLBACK_KEY = "move-illegal"

    /**
     * 取某个否决原因对应的语言键。
     *
     * 注意几处刻意的合并与拆分：
     * - [MoveRejection.CANNON_NO_SCREEN] 与 [MoveRejection.CANNON_TOO_MANY_SCREENS]
     *   共用 `move-illegal-cannon-screen`，因为文案已经把"不多不少"说清楚了，
     *   拆成两条对玩家没有额外信息量。
     * - 帥/將 与 仕/士 出九宫共用 `move-illegal-out-of-palace`，
     *   该文案带 `<piece>` 占位符，由调用方填具体棋子名来区分。
     * - [MoveRejection.SELF_CHECK] 单独一条 `move-illegal-self-check`，
     *   绝不与蹩马腿、塞象眼混用。
     */
    fun keyOf(rejection: MoveRejection): String = when (rejection) {
        // 起点问题：点到空格或点到对方的子
        MoveRejection.EMPTY_ORIGIN -> "move-illegal"
        MoveRejection.NOT_OWN_PIECE -> "not-your-piece"
        MoveRejection.WRONG_TURN -> "not-your-turn"

        // 落点本身不成立
        MoveRejection.SAME_SQUARE -> "move-illegal"
        MoveRejection.OFF_BOARD -> "move-illegal"
        MoveRejection.CAPTURE_OWN_PIECE -> "move-illegal-own-piece"
        MoveRejection.INVALID_PATTERN -> "move-illegal"

        // 九宫与过河限制
        MoveRejection.KING_OUT_OF_PALACE -> "move-illegal-out-of-palace"
        MoveRejection.ADVISOR_OUT_OF_PALACE -> "move-illegal-out-of-palace"
        MoveRejection.ELEPHANT_CROSSED_RIVER -> "move-illegal-cross-river"

        // 阻挡类，三种阻挡各自一条，这是本项目的硬要求
        MoveRejection.ELEPHANT_EYE_BLOCKED -> "move-illegal-elephant-eye"
        MoveRejection.HORSE_LEG_BLOCKED -> "move-illegal-horse-leg"
        MoveRejection.PATH_BLOCKED -> "move-illegal-blocked-path"

        // 炮架数量不对，两种情况共用一条已说清的文案
        MoveRejection.CANNON_NO_SCREEN -> "move-illegal-cannon-screen"
        MoveRejection.CANNON_TOO_MANY_SCREENS -> "move-illegal-cannon-screen"

        // 兵卒方向限制
        MoveRejection.PAWN_BACKWARD -> "move-illegal-pawn-backward"
        MoveRejection.PAWN_SIDEWAYS_BEFORE_RIVER -> "move-illegal-pawn-sideways"

        // 送将，含飞将对脸与不应将
        MoveRejection.SELF_CHECK -> "move-illegal-self-check"

        // 摆局面出错，属于数据异常而不是玩家操作问题
        MoveRejection.MISSING_KING -> "move-illegal"

        // 落点已有子：棋盘上一眼看得见，沿用"不出声"的处理，不刷聊天栏
        MoveRejection.SQUARE_OCCUPIED -> "move-illegal"

        // 围棋的禁自杀与打劫是**棋盘上看不出来**的规则，
        // 与将军同属"必须明说"的例外，所以这两条给真文案。
        MoveRejection.GO_SUICIDE -> "place-illegal-suicide"
        MoveRejection.GO_KO -> "place-illegal-ko"
    }

    /**
     * 该原因的提示是否需要 `<piece>` 占位符。
     *
     * 只有出九宫那条文案里带棋子名，其余文案是固定句子。
     * 交互层据此决定要不要多算一次棋子显示名。
     */
    fun needsPieceName(rejection: MoveRejection): Boolean =
        rejection == MoveRejection.KING_OUT_OF_PALACE ||
            rejection == MoveRejection.ADVISOR_OUT_OF_PALACE

    /**
     * 该原因的提示是否需要 `<side>` 占位符。
     *
     * `not-your-turn` 要告诉玩家现在轮到谁。
     */
    fun needsSideName(rejection: MoveRejection): Boolean =
        rejection == MoveRejection.WRONG_TURN

    /**
     * 把 [MoveRejection.SELF_CHECK] 细分成三种更具体的提示。
     *
     * 规则层按项目约定把"飞将对脸、不应将、走进攻击范围"统一归到送将
     * （见 XiangqiRules.rejectionOf 的注释），这在规则上是对的，
     * 但对玩家来说这三件事的原因完全不同：
     * 一个是两将照面，一个是没解将，一个是自己走进了对方的射程。
     * 所以在交互层用规则层的**公开 API** 再判一次，规则层不需要改动。
     *
     * 返回的 `move-illegal-face-off` 与 `move-illegal-must-answer-check` 两个键，
     * 连同 `move-illegal-self-check`，**当前都不在 `lang/zh_CN.yml` 里**：
     * 走棋交互改成不出声之后这批键已删（见类头说明）。恢复文字提示时要一并补回。
     *
     * 判定顺序有讲究：先看照面，再看走之前是否已被将军。
     * 因为"被将军时随便走一步"往往同时满足后两条，
     * 而玩家最需要知道的是"你正在被将，先解将"。
     *
     * @param board 走之前的局面，本方法不会改动它
     * @param move 玩家想走的那一步
     * @return 更具体的语言键
     */
    fun selfCheckKey(board: Board, move: Move): String {
        val mover = move.piece.side
        // 在快照上试走，绝不动原局面
        val after = board.copy().also { it.apply(move) }
        if (kingsFaceEachOther(after)) {
            return "move-illegal-face-off"
        }
        // 走之前就被将军，说明这一步没有解将
        if (CheckDetector.isInCheck(board, mover)) {
            return "move-illegal-must-answer-check"
        }
        return "move-illegal-self-check"
    }

    /**
     * 两将是否同纵线且中间无子。
     *
     * 只用 [Board] 的公开读接口，不复制规则层的走法逻辑。
     */
    private fun kingsFaceEachOther(board: Board): Boolean {
        val red = board.kingSquare(Side.FIRST) ?: return false
        val black = board.kingSquare(Side.SECOND) ?: return false
        if (red.file != black.file) {
            return false
        }
        val from = minOf(red.rank, black.rank) + 1
        val to = maxOf(red.rank, black.rank) - 1
        for (rank in from..to) {
            if (!board.isEmpty(Square(red.file, rank))) {
                return false
            }
        }
        return true
    }
}
