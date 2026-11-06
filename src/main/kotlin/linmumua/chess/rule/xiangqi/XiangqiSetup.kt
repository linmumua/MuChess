package linmumua.chess.rule.xiangqi

import linmumua.chess.rule.core.Board
import linmumua.chess.rule.core.BoardSpec
import linmumua.chess.rule.core.Piece
import linmumua.chess.rule.core.PieceType
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.core.Square

/**
 * 象棋初始局面数据。
 *
 * 单独拎出来是给配置层引用的：`games/xiangqi.yml` 里的初始局面要能与代码默认值对照，
 * 配置层读到的布局若与 [PLACEMENTS] 不一致，应当以配置为准并记日志，
 * 但默认值必须只有这一处定义，避免代码与模板各写一份摆子表。
 */
object XiangqiSetup {

    /** 底线排布：車馬象士將士象馬車，rank 0 为黑方底线，rank 9 为红方底线 */
    private val BACK_RANK = listOf(
        PieceType.CHARIOT,
        PieceType.HORSE,
        PieceType.ELEPHANT,
        PieceType.ADVISOR,
        PieceType.KING,
        PieceType.ADVISOR,
        PieceType.ELEPHANT,
        PieceType.HORSE,
        PieceType.CHARIOT,
    )

    /** 炮所在纵线 */
    private val CANNON_FILES = listOf(1, 7)

    /** 兵/卒所在纵线 */
    private val PAWN_FILES = listOf(0, 2, 4, 6, 8)

    /** 初始摆子表：交叉点 → 棋子，共 32 枚 */
    val PLACEMENTS: Map<Square, Piece> = buildMap {
        for (side in Side.entries) {
            val backRank = if (side == Side.FIRST) 9 else 0
            val cannonRank = if (side == Side.FIRST) 7 else 2
            val pawnRank = if (side == Side.FIRST) 6 else 3
            for ((file, type) in BACK_RANK.withIndex()) {
                put(Square(file, backRank), Piece(type, side))
            }
            for (file in CANNON_FILES) {
                put(Square(file, cannonRank), Piece(PieceType.CANNON, side))
            }
            for (file in PAWN_FILES) {
                put(Square(file, pawnRank), Piece(PieceType.PAWN, side))
            }
        }
    }

    /** 摆出一副开局，先行方（红）先走 */
    fun initialBoard(): Board {
        val board = Board(BoardSpec.XIANGQI, Side.FIRST)
        for ((square, piece) in PLACEMENTS) {
            board[square] = piece
        }
        return board
    }
}
