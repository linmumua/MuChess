package linmumua.chess.rule.gomoku

import linmumua.chess.rule.core.Board
import linmumua.chess.rule.core.BoardSpec
import linmumua.chess.rule.core.GameRules
import linmumua.chess.rule.core.Move
import linmumua.chess.rule.core.Piece
import linmumua.chess.rule.core.PieceType
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.core.Square
import linmumua.chess.rule.xiangqi.GameOutcome
import linmumua.chess.rule.xiangqi.MoveRejection

/**
 * 五子棋规则门面。
 *
 * 与象棋最大的不同是**落子式**：棋子下到空点后不再移动，也没有吃子。
 * 所以 [Move] 在这里退化成"只有终点有意义"，[Move.from] 与 [Move.to] 相同 ——
 * 复用 Move 而不是另造一个 Place 类型，是为了让上层的对局流程、历史记录、
 * 撤销机制不必按棋种分叉。
 *
 * 全类不含任何 Bukkit 类型，可以脱离服务端跑单测。
 *
 * @param options 规则可调项，默认关闭禁手
 */
class GomokuRules(val options: GomokuRuleOptions = GomokuRuleOptions.DEFAULT) : GameRules {

    override val spec: BoardSpec get() = BoardSpec.GOMOKU

    /** 五子棋是落子式，交互层用单击直接落子 */
    override val isPlacement: Boolean get() = true

    /** 空盘开局，先行方（黑）先下 */
    override fun initialBoard(): Board = Board(BoardSpec.GOMOKU, Side.FIRST)

    /**
     * 某方全部合法着法 = 全部空点。
     *
     * 15×15 空盘有 225 个着法，机器人不能直接对这个列表做搜索 ——
     * 候选点筛选是机器人自己的事，规则层只如实给出"哪些点能下"。
     */
    override fun legalMoves(board: Board, side: Side): List<Move> =
        board.emptySquares()
            .filter { rejectionOf(board, it, it) == null }
            .map { placementAt(it, side) }

    /**
     * 落子式棋种没有"某枚子能走哪"这回事。
     *
     * 交互层靠 [isPlacement] 分流，不会走到这里；真被调到就返回空，
     * 不能编出一堆落点让白圈显示成象棋那样。
     */
    override fun legalMovesFrom(board: Board, square: Square): List<Move> = emptyList()

    /**
     * 判定一手落子。
     *
     * 落子式棋种的 from 与 to 必须是同一个点，规则只有两条：点在盘内、且是空的。
     */
    override fun rejectionOf(board: Board, from: Square, to: Square): MoveRejection? {
        if (!board.contains(to)) {
            return MoveRejection.OFF_BOARD
        }
        if (!board.isEmpty(to)) {
            return MoveRejection.SQUARE_OCCUPIED
        }
        return null
    }

    /**
     * 带行棋方的判定。
     *
     * 落子式没有"这不是你的子"这种情况（下的是自己的新子），
     * 所以只判轮次，再走一遍规则本身。
     */
    override fun rejectionOf(board: Board, from: Square, to: Square, mover: Side): MoveRejection? {
        if (mover != board.sideToMove) {
            return MoveRejection.WRONG_TURN
        }
        return rejectionOf(board, from, to)
    }

    /**
     * 按落点补出一手棋。
     *
     * 与象棋相反：象棋要求起点有子，五子棋要求落点**没有**子。
     * 落点已有子时返回 null，让调用方按"这手下不了"处理。
     */
    override fun moveAt(board: Board, from: Square, to: Square): Move? {
        if (!board.contains(to) || !board.isEmpty(to)) {
            return null
        }
        return placementAt(to, board.sideToMove)
    }

    override fun apply(board: Board, move: Move) = board.apply(move)

    override fun undo(board: Board, move: Move) = board.undo(move)

    /** 五子棋没有将军，恒为 false */
    override fun needsWarning(board: Board, side: Side): Boolean = false

    /**
     * 判定终局。
     *
     * 注意这里的判定对象是**刚下完那一手**，而不是"轮到走棋的一方" ——
     * 连五的一方下完就赢了，此时 sideToMove 已经切到对手。
     * 所以先看对手（即刚落子那方）有没有连五，再看满盘。
     */
    override fun outcome(board: Board, sideToMove: Side): GameOutcome {
        val justMoved = sideToMove.opposite
        if (hasLine(board, justMoved)) {
            return GameOutcome.LineWin(justMoved)
        }
        // 极少数情况下轮到走的一方也可能已成五（比如从残局摆起），一并兜住
        if (hasLine(board, sideToMove)) {
            return GameOutcome.LineWin(sideToMove)
        }
        if (board.emptySquares().isEmpty()) {
            return GameOutcome.DrawByFullBoard
        }
        return GameOutcome.Ongoing
    }

    override fun toFen(board: Board): String = board.toFen()

    override fun fromFen(fen: String): Board = Board.fromFen(fen, BoardSpec.GOMOKU)

    /**
     * 某方盘上是否已有五连。
     *
     * 扫全盘而不是只看最后一手，是因为 [outcome] 也要能判断从 FEN 还原出来的局面 ——
     * 那时没有"最后一手"可用。
     */
    fun hasLine(board: Board, side: Side): Boolean =
        board.squaresOf(side).any { GomokuLine.isWin(board, it, side, options.winLength) }

    /**
     * 一手落子后是否成五，机器人与交互层判断"这手是否直接取胜"时用。
     *
     * 只看这一手所在的四条线，比 [hasLine] 快得多。
     */
    fun isWinningPlacement(board: Board, square: Square, side: Side): Boolean =
        GomokuLine.isWin(board, square, side, options.winLength)

    companion object {
        /**
         * 构造一手落子。
         *
         * 落子式棋种没有起点，用 from == to 表达；[Move.captured] 恒为 null，
         * 因为五子棋不吃子。棋子种类用 [PieceType.STONE] ——
         * 五子棋的子没有种类之分，用 [PieceType.STONE] 让渲染层挑素色圆盘外观。
         */
        fun placementAt(square: Square, side: Side): Move =
            Move(square, square, Piece(PieceType.STONE, side), null)
    }
}

/**
 * 五子棋规则可调项。
 *
 * 禁手（三三、四四、长连）默认**关闭**：它是高水平对局才需要的平衡规则，
 * 默认开会让新手莫名其妙被判负。
 */
data class GomokuRuleOptions(
    /** 连成几子取胜 */
    val winLength: Int = GomokuLine.WIN_LENGTH,
    /** 是否启用禁手判负 */
    val forbiddenMovesEnabled: Boolean = false,
) {
    init {
        require(winLength >= 2) { "取胜连子数至少为 2，实际 $winLength" }
    }

    companion object {
        val DEFAULT = GomokuRuleOptions()
    }
}
