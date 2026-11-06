package linmumua.chess.interact

import linmumua.chess.rule.core.Board
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.core.Square

/**
 * 右键点在棋盘上意味着什么。
 *
 * 这是交互的决策核心，做成**纯函数**：输入局面、点击点、当前选中态、点击者阵营，
 * 输出一个动作意图。不碰 Bukkit、不发消息、不动实体，因此可以完整单测
 * "第一次点选中、再点同一枚取消、点合法落点走子、点非法落点报错" 这些分支。
 *
 * 执行动作是 [MoveExecutor] 的事，本类只负责判断"该做什么"。
 */
object ClickResolver {

    /**
     * 解析一次右键。
     *
     * @param board 当前局面
     * @param clicked 玩家点到的交叉点
     * @param selection 该玩家当前的选中态，没选中传 null
     * @param mover 点击者的阵营，观战者传 null
     */
    fun resolve(
        board: Board,
        clicked: Square,
        selection: SelectionState?,
        mover: Side?,
        isPlacement: Boolean = false,
    ): ClickAction {
        // 观战者只能看，不产生任何动作
        if (mover == null) {
            return ClickAction.Spectate
        }

        // 不是自己的回合就什么都不给：不能选中、不能换选中、不能落子。
        //
        // 这道门必须在这里，不能只靠走子时的 WRONG_TURN 拦。否则非行棋方右键自己的子
        // 会真的进选中态 —— 棋子浮起放大、合法落点白圈全亮出来，一直到点落点才被拒，
        // 等于让对手在别人回合里预演走法，还会跟行棋方的选中态抢同一副棋盘的高亮。
        //
        // 唯一的例外是已经存在的选中态：轮次切换的瞬间可能残留，交给下面 Deselect 分支
        // 让玩家自己点掉。这里不主动清，清理是渲染层的事。
        if (mover != board.sideToMove) {
            // 已选中且点的就是那枚子，允许取消 —— 否则选中态在对方回合里卡住没法退出
            if (selection != null && clicked == selection.square) {
                return ClickAction.Deselect(clicked)
            }
            return ClickAction.NotYourTurn(clicked)
        }

        val clickedPiece = board[clicked]

        // 落子式棋种（五子棋、围棋）：一次右键直接落子，没有选中态这回事。
        // 空点就下，已有子就当点空 —— 不给"这里有子了"的提示，
        // 因为棋盘上一眼看得见，这与走棋交互"不出声"的约定一致。
        if (isPlacement) {
            return if (clickedPiece == null) {
                ClickAction.Place(clicked)
            } else {
                ClickAction.Ignore
            }
        }

        if (selection == null) {
            // 没选中的情况下，只有点自己的子才有意义
            return when {
                clickedPiece == null -> ClickAction.Ignore
                clickedPiece.side != mover -> ClickAction.NotOwnPiece(clicked)
                else -> ClickAction.Select(clicked)
            }
        }

        // 再点同一枚 = 取消选中
        if (clicked == selection.square) {
            return ClickAction.Deselect(clicked)
        }

        // 点到自己另一枚子 = 换选中，而不是"吃自己的子"这种报错
        if (clickedPiece != null && clickedPiece.side == mover) {
            return ClickAction.Switch(selection.square, clicked)
        }

        // 点的是高亮过的合法落点 = 走子或吃子
        if (selection.canMoveTo(clicked)) {
            return ClickAction.Move(selection.square, clicked, clickedPiece != null)
        }

        // 点的是没高亮的点：仍然要走一遍规则判定，好告诉玩家到底哪儿不对。
        // 这条分支是需求强调的部分 —— 不能只是静默忽略，
        // 蹩马腿和送将必须给出不同的话。
        return ClickAction.IllegalTarget(selection.square, clicked)
    }
}

/**
 * 一次右键的动作意图。
 *
 * 用密封接口列全分支，这样 [MoveExecutor] 里的 `when` 有穷尽检查，
 * 以后加一种交互不会漏处理。
 */
sealed interface ClickAction {

    /** 选中某枚子，要浮起放大、显示描边与白圈落点 */
    data class Select(val square: Square) : ClickAction

    /** 取消选中，棋子落回棋盘面 */
    data class Deselect(val square: Square) : ClickAction

    /** 换选中：把 [from] 放下，选中 [to] */
    data class Switch(val from: Square, val to: Square) : ClickAction

    /**
     * 落子。
     *
     * [isCapture] 只用来挑音效与文案，真正的被吃子信息由规则层填。
     */
    data class Move(val from: Square, val to: Square, val isCapture: Boolean) : ClickAction

    /**
     * 在空点落一枚新子：五子棋与围棋专用。
     *
     * 与 [Move] 分开是因为两者的表现完全不同：[Move] 要把已有的棋子实体从起点搬到终点，
     * [Place] 是新生成一枚。规则层那边同样分得开 —— 落子式的着法 from 与 to 相同。
     */
    data class Place(val square: Square) : ClickAction

    /**
     * 点了非法落点。
     *
     * 执行层要拿这对起终点去问规则层"为什么不行"，再翻成具体提示。
     */
    data class IllegalTarget(val from: Square, val to: Square) : ClickAction

    /** 点了对方的子且当前没选中，提示这不是你的子 */
    data class NotOwnPiece(val square: Square) : ClickAction

    /**
     * 还没轮到点击者行棋。
     *
     * 与 [NotOwnPiece] 分开：那条是"这子不是你的"，这条是"这轮不是你的"，
     * 两种情况玩家该看到的反馈不同（前者靠棋子颜色自明所以不出声，
     * 后者需要一句动作栏提示，否则玩家会以为插件卡了）。
     */
    data class NotYourTurn(val square: Square) : ClickAction

    /** 观战者点击，不做任何事 */
    data object Spectate : ClickAction

    /** 点了空处且没选中，什么都不做也不提示，避免刷屏 */
    data object Ignore : ClickAction
}
