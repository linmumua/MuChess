package linmumua.chess.rule.xiangqi

import linmumua.chess.rule.core.Board
import linmumua.chess.rule.core.BoardSpec
import linmumua.chess.rule.core.GameRules
import linmumua.chess.rule.core.Move
import linmumua.chess.rule.core.Piece
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.core.Square

/**
 * 象棋规则门面。
 *
 * 这是规则层对外的**唯一入口**：交互层、对局流程层、渲染层都只认这个类，
 * 不直接调走法生成器或将军检测。这样规则内部怎么拆都不影响上层。
 *
 * 全类不含任何 Bukkit 类型，可以脱离服务端跑单测。
 *
 * 状态管理约定：[Board] 是**可变**的，走子用 [apply]、撤销用 [undo]，两者严格配对。
 * 需要不影响原局面的推演时先 [Board.copy] 拿快照，不要指望 [apply] 返回新盘。
 *
 * @param options 规则可调项，默认启用 60 回合自然限着
 */
class XiangqiRules(val options: XiangqiRuleOptions = XiangqiRuleOptions.DEFAULT) : GameRules {

    override val spec: BoardSpec get() = BoardSpec.XIANGQI

    /** 象棋是"选中再移动"，不是落子式 */
    override val isPlacement: Boolean get() = false

    /** 摆一副开局，先行方（红）先走 */
    override fun initialBoard(): Board = XiangqiSetup.initialBoard()

    /** 初始摆子表，配置层要与默认值对照时用 */
    val initialPlacements: Map<Square, Piece> get() = XiangqiSetup.PLACEMENTS

    /**
     * 某方全部合法着法。
     *
     * 流程是"生成伪合法 → 逐个试走 → 剔除走完己方被将军的"，
     * 也就是送将过滤。这一条同时覆盖了飞将对脸、不应将、走进敌子攻击范围三种情况。
     */
    override fun legalMoves(board: Board, side: Side): List<Move> =
        RuleValidator.filterLegal(board, MoveGenerator.pseudoLegalMoves(board, side), side)

    /**
     * 某个交叉点上那枚子的合法落点。
     *
     * 交互层选中棋子后用这个算落点高亮，所以只按棋子归属过滤，不校验轮次 ——
     * 轮次由对局流程层判，规则层给"这枚子理论上能走哪"。
     */
    override fun legalMovesFrom(board: Board, square: Square): List<Move> {
        val piece = board[square] ?: return emptyList()
        return RuleValidator.filterLegal(
            board,
            MoveGenerator.pseudoLegalMovesFrom(board, square),
            piece.side,
        )
    }

    /** 一步棋是否合法，不关心为什么不合法时用这个 */
    fun isLegal(board: Board, move: Move): Boolean = rejectionOf(board, move) == null

    /** 起终点形式的合法性判断 */
    fun isLegal(board: Board, from: Square, to: Square): Boolean =
        rejectionOf(board, from, to) == null

    /**
     * 判定一步棋并给出否决原因，返回 null 表示合法。
     *
     * 交互层拿这个原因去挑消息键：蹩马腿和送将必须提示不同的话。
     *
     * 这个重载**只判规则本身**，按起点那枚子的归属来算，不校验轮次 ——
     * 轮次是对局流程的状态，混进来会盖掉真正的规则原因（比如本该报蹩马腿却报了不该你走）。
     * 要连轮次一起判，用带 `mover` 的重载。
     */
    override fun rejectionOf(board: Board, from: Square, to: Square): MoveRejection? {
        val piece = board[from] ?: return MoveRejection.EMPTY_ORIGIN
        val geometry = RuleValidator.rejectionFor(board, from, to)
        if (geometry != null) {
            return geometry
        }
        if (board.kingSquare(piece.side) == null) {
            return MoveRejection.MISSING_KING
        }
        val move = Move(from, to, piece, board[to])
        return if (RuleValidator.leavesKingAttacked(board, move)) MoveRejection.SELF_CHECK else null
    }

    /**
     * 带行棋方的判定，供"玩家真的落子"这条路径用。
     *
     * 判定顺序是先归属、再轮次、最后规则：玩家点了对方的子要给"这不是你的子"，
     * 点了自己的子但没轮到才给"不该你走"，两者的提示不一样。
     */
    override fun rejectionOf(board: Board, from: Square, to: Square, mover: Side): MoveRejection? {
        val piece = board[from] ?: return MoveRejection.EMPTY_ORIGIN
        if (piece.side != mover) {
            return MoveRejection.NOT_OWN_PIECE
        }
        if (mover != board.sideToMove) {
            return MoveRejection.WRONG_TURN
        }
        return rejectionOf(board, from, to)
    }

    /** 同上，只判规则不判轮次，接受已构造好的着法 */
    fun rejectionOf(board: Board, move: Move): MoveRejection? =
        rejectionOf(board, move.from, move.to)

    /** 走一步棋，不做校验；请先用 [isLegal] 或 [rejectionOf] 过一遍 */
    override fun apply(board: Board, move: Move) = board.apply(move)

    /** 撤销最近一步棋，必须与 [apply] 配对 */
    override fun undo(board: Board, move: Move) = board.undo(move)

    /** 在快照上试走一步，原局面不动。悔棋预览、AI 推演用 */
    fun applyToCopy(board: Board, move: Move): Board = board.copy().also { it.apply(move) }

    /** 按起终点补全着法，起点没子返回 null */
    override fun moveAt(board: Board, from: Square, to: Square): Move? = board.moveAt(from, to)

    /** 某方是否正被将军 */
    fun isInCheck(board: Board, side: Side): Boolean = CheckDetector.isInCheck(board, side)

    /** 象棋的"要被提醒"就是被将军 */
    override fun needsWarning(board: Board, side: Side): Boolean = isInCheck(board, side)

    /**
     * 判定终局。
     *
     * [sideToMove] 是轮到走棋的一方，判定顺序有讲究：
     * 先看有没有合法着法，无着法时按"是否被将军"分将死与困毙，两者**都判负**；
     * 只有还有着法可走时才去看自然限着，否则会把已经将死的局面误判成和棋。
     */
    override fun outcome(board: Board, sideToMove: Side): GameOutcome {
        if (legalMoves(board, sideToMove).isEmpty()) {
            return if (isInCheck(board, sideToMove)) {
                GameOutcome.Checkmate(sideToMove)
            } else {
                // 困毙同样判负，这与国际象棋的和棋处理不同
                GameOutcome.Stalemate(sideToMove)
            }
        }
        if (options.moveLimitDrawEnabled &&
            board.halfMovesSinceCapture >= options.moveLimitHalfMoves
        ) {
            return GameOutcome.DrawByMoveLimit(board.halfMovesSinceCapture / 2)
        }
        return GameOutcome.Ongoing
    }

    /** 按棋盘自己记录的行棋方判定终局 */
    fun outcome(board: Board): GameOutcome = outcome(board, board.sideToMove)

    /** 局面序列化，落盘用 */
    override fun toFen(board: Board): String = board.toFen()

    /** 局面反序列化，串不合法时抛 [IllegalArgumentException] */
    override fun fromFen(fen: String): Board = Board.fromFen(fen, BoardSpec.XIANGQI)

}
