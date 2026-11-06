package linmumua.chess.rule.xiangqi

import linmumua.chess.rule.core.Board
import linmumua.chess.rule.core.Move
import linmumua.chess.rule.core.PieceType
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.core.Square
import kotlin.math.abs

/**
 * 合法性裁定。
 *
 * 负责蹩马腿、塞象眼、飞将、送将这四类过滤 —— 前两类在 [rejectionFor] 里按棋子形状判，
 * 后两类靠 [leavesKingAttacked] 试走后回查将军统一收口。
 *
 * 走法生成与门面共用这里：生成器只关心"能不能走"，门面要拿到"为什么不能走"。
 * 两者若各写一份判定必然走偏，所以判定只有 [rejectionFor] 一个出口，
 * 返回 null 表示这一步在几何与阻挡层面成立。
 *
 * 阻挡点的具体算法在 [XiangqiGeometry]，这里只做裁定不重复推导坐标。
 */
internal object RuleValidator {

    /**
     * 判定一步棋在几何与阻挡层面是否成立。
     *
     * 返回 null 表示成立（伪合法）；否则返回具体的否决原因。
     * **不校验轮次，也不校验送将** —— 送将要试走后回查，见 [leavesKingAttacked]。
     */
    fun rejectionFor(board: Board, from: Square, to: Square): MoveRejection? {
        val piece = board[from] ?: return MoveRejection.EMPTY_ORIGIN
        if (!board.contains(to)) {
            return MoveRejection.OFF_BOARD
        }
        if (from == to) {
            return MoveRejection.SAME_SQUARE
        }
        if (board.isOccupiedBy(to, piece.side)) {
            return MoveRejection.CAPTURE_OWN_PIECE
        }

        val deltaFile = to.file - from.file
        val deltaRank = to.rank - from.rank
        val absFile = abs(deltaFile)
        val absRank = abs(deltaRank)

        return when (piece.type) {
            PieceType.KING -> when {
                absFile + absRank != 1 -> MoveRejection.INVALID_PATTERN
                !XiangqiGeometry.isInPalace(to, piece.side) -> MoveRejection.KING_OUT_OF_PALACE
                else -> null
            }

            PieceType.ADVISOR -> when {
                absFile != 1 || absRank != 1 -> MoveRejection.INVALID_PATTERN
                !XiangqiGeometry.isInPalace(to, piece.side) -> MoveRejection.ADVISOR_OUT_OF_PALACE
                else -> null
            }

            PieceType.ELEPHANT -> when {
                absFile != 2 || absRank != 2 -> MoveRejection.INVALID_PATTERN
                // 象不可过河：落点必须还在自己半场
                !XiangqiGeometry.isHomeSide(to, piece.side) -> MoveRejection.ELEPHANT_CROSSED_RIVER
                XiangqiGeometry.elephantEye(from, deltaFile, deltaRank)
                    ?.let { !board.isEmpty(it) } == true -> MoveRejection.ELEPHANT_EYE_BLOCKED
                else -> null
            }

            PieceType.HORSE -> when {
                !((absFile == 1 && absRank == 2) || (absFile == 2 && absRank == 1)) ->
                    MoveRejection.INVALID_PATTERN
                // 马腿不分敌我，任何子挡住都不能走
                XiangqiGeometry.horseLeg(from, deltaFile, deltaRank)
                    ?.let { !board.isEmpty(it) } == true -> MoveRejection.HORSE_LEG_BLOCKED
                else -> null
            }

            PieceType.CHARIOT -> when {
                deltaFile != 0 && deltaRank != 0 -> MoveRejection.INVALID_PATTERN
                XiangqiGeometry.countBetween(board, from, to) != 0 -> MoveRejection.PATH_BLOCKED
                else -> null
            }

            PieceType.CANNON -> {
                if (deltaFile != 0 && deltaRank != 0) {
                    return MoveRejection.INVALID_PATTERN
                }
                val screens = XiangqiGeometry.countBetween(board, from, to)
                if (board.isEmpty(to)) {
                    // 不吃子时炮同車，路上不能有子
                    if (screens != 0) MoveRejection.PATH_BLOCKED else null
                } else {
                    // 吃子必须跨过恰好一枚炮架，炮架不分敌我
                    when {
                        screens == 0 -> MoveRejection.CANNON_NO_SCREEN
                        screens > 1 -> MoveRejection.CANNON_TOO_MANY_SCREENS
                        else -> null
                    }
                }
            }

            PieceType.PAWN -> {
                val forward = XiangqiGeometry.pawnForward(piece.side)
                when {
                    absFile + absRank != 1 -> MoveRejection.INVALID_PATTERN
                    // 兵卒永不后退
                    deltaRank == -forward -> MoveRejection.PAWN_BACKWARD
                    // 横走只有过河之后才允许
                    deltaFile != 0 && XiangqiGeometry.isHomeSide(from, piece.side) ->
                        MoveRejection.PAWN_SIDEWAYS_BEFORE_RIVER
                    else -> null
                }
            }

            // 素色子是五子棋与围棋的子，象棋规则不认它：一律否掉，
            // 不能靠"落到某个 else 分支"当成合法着法放过去。
            PieceType.STONE -> MoveRejection.INVALID_PATTERN
        }
    }

    /**
     * 送将过滤：试走一步，看己方将是否处于被攻击状态。
     *
     * 这一条同时覆盖飞将对脸、被将军时不应将、走进敌子攻击范围三种情形 ——
     * 因为 [CheckDetector] 把将当車看，对脸本身就算被将军。
     *
     * 走完就撤，不留痕迹。将军检测是反向探测，这里的开销是常数级的。
     */
    fun leavesKingAttacked(board: Board, move: Move): Boolean {
        board.apply(move)
        val attacked = try {
            CheckDetector.isInCheck(board, move.piece.side)
        } finally {
            // 无论探测是否抛错都必须还原局面，否则整盘状态就脏了
            board.undo(move)
        }
        return attacked
    }

    /** 从伪合法着法里剔除送将的，得到合法着法 */
    fun filterLegal(board: Board, moves: List<Move>, side: Side): List<Move> {
        // 局面里没有该方的将时无从判送将，直接视为无着法，由上层按摆局面出错处理
        if (moves.isEmpty() || board.kingSquare(side) == null) {
            return emptyList()
        }
        return moves.filterNot { leavesKingAttacked(board, it) }
    }
}
