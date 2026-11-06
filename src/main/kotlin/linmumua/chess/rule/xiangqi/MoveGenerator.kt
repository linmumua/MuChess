package linmumua.chess.rule.xiangqi

import linmumua.chess.rule.core.Board
import linmumua.chess.rule.core.Move
import linmumua.chess.rule.core.Piece
import linmumua.chess.rule.core.PieceType
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.core.Square

/**
 * 伪合法走法生成。
 *
 * "伪合法"= 满足该子的走法几何与阻挡规则，但**没有排除送将**。
 * 合法着法 = 伪合法着法逐个试走后剔除使己方被将军的，那一步在 [XiangqiRules] 里做。
 *
 * 生成方式是"按棋子类型枚举候选落点，再交给 [RuleValidator] 复核"，
 * 而不是直接在这里写第二套阻挡判定 —— 两套判定必然走偏。
 */
object MoveGenerator {

    /** 生成某方全部伪合法着法 */
    fun pseudoLegalMoves(board: Board, side: Side): List<Move> {
        val result = ArrayList<Move>(64)
        for (square in board.squaresOf(side)) {
            collect(board, square, result)
        }
        return result
    }

    /** 生成某个交叉点上那枚子的伪合法着法，空点或不该动的子返回空表 */
    fun pseudoLegalMovesFrom(board: Board, from: Square): List<Move> {
        if (board[from] == null) {
            return emptyList()
        }
        val result = ArrayList<Move>(17)
        collect(board, from, result)
        return result
    }

    private fun collect(board: Board, from: Square, sink: MutableList<Move>) {
        val piece = board[from] ?: return
        for (to in candidates(board, from, piece)) {
            if (RuleValidator.rejectionFor(board, from, to) == null) {
                sink += Move(from, to, piece, board[to])
            }
        }
    }

    /**
     * 候选落点。
     *
     * 短程子直接列偏移；車和炮沿四个方向铺到底，越界即停。
     * 这里故意放宽，把明显走不通的点也列进来，由几何复核统一否掉。
     */
    private fun candidates(board: Board, from: Square, piece: Piece): List<Square> {
        val result = ArrayList<Square>(17)
        when (piece.type) {
            PieceType.KING -> addOffsets(from, XiangqiGeometry.ORTHOGONALS, result)
            PieceType.ADVISOR -> addOffsets(from, XiangqiGeometry.DIAGONALS, result)
            PieceType.ELEPHANT -> addOffsets(from, XiangqiGeometry.ELEPHANT_STEPS, result)
            PieceType.HORSE -> addOffsets(from, XiangqiGeometry.HORSE_STEPS, result)
            PieceType.CHARIOT, PieceType.CANNON -> addRays(board, from, result)
            PieceType.PAWN -> {
                val forward = XiangqiGeometry.pawnForward(piece.side)
                // 前、左、右三个方向都列出来，过河与否由几何复核裁掉
                addOffsets(from, listOf(0 to forward, 1 to 0, -1 to 0), result)
            }
            // 素色子属于五子棋与围棋，象棋盘上不该出现；出现了也不给任何落点，
            // 让它保持不能动，而不是当成某种象棋子乱走。
            PieceType.STONE -> Unit
        }
        return result
    }

    private fun addOffsets(from: Square, offsets: List<Pair<Int, Int>>, sink: MutableList<Square>) {
        for ((deltaFile, deltaRank) in offsets) {
            from.offset(deltaFile, deltaRank)?.let { sink += it }
        }
    }

    /**
     * 沿四个正交方向铺出候选点。
     *
     * 車要在第一枚子处停（含吃它），炮要越过第一枚子继续找落点，
     * 两者的差别放在几何复核里判，这里统一铺到"第一枚子之后再遇到的第一枚子"为止：
     * 炮最远只能吃到炮架后的第一枚子，再往后不可能有合法落点。
     */
    private fun addRays(board: Board, from: Square, sink: MutableList<Square>) {
        for ((deltaFile, deltaRank) in XiangqiGeometry.ORTHOGONALS) {
            var cursor = from.offset(deltaFile, deltaRank)
            var met = 0
            while (cursor != null) {
                sink += cursor
                if (!board.isEmpty(cursor)) {
                    met++
                    if (met == 2) {
                        break
                    }
                }
                cursor = cursor.offset(deltaFile, deltaRank)
            }
        }
    }
}
