package linmumua.chess.rule.xiangqi

import linmumua.chess.rule.core.Board
import linmumua.chess.rule.core.Piece
import linmumua.chess.rule.core.PieceType
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.core.Square

/**
 * 测试用摆局助手。
 *
 * 所有用例都靠显式摆子构造局面，不靠一长串着法推到目标局面 ——
 * 那样一旦失败根本看不出是哪条规则错了。
 */
object BoardFixture {

    /** 红将默认位置：九宫中路底线 */
    val RED_KING_HOME = Square(4, 9)

    /**
     * 黑将默认位置，故意错开一条纵线。
     *
     * 稀疏局面里若两将同处 file 4，中间常常空无一子，那就是飞将局面，
     * 所有用例都会被送将过滤连带影响。默认错开纵线，飞将由专门的用例显式摆。
     */
    val BLACK_KING_HOME = Square(3, 0)

    /**
     * 摆一个只有指定棋子的局面。
     *
     * 双方的将默认都摆上，否则送将过滤没有参照物；要测缺将的边界时显式传 null。
     */
    fun board(
        sideToMove: Side = Side.FIRST,
        redKing: Square? = RED_KING_HOME,
        blackKing: Square? = BLACK_KING_HOME,
        vararg pieces: Pair<Square, Piece>,
    ): Board {
        val board = Board(sideToMove = sideToMove)
        redKing?.let { board[it] = Piece(PieceType.KING, Side.FIRST) }
        blackKing?.let { board[it] = Piece(PieceType.KING, Side.SECOND) }
        for ((square, piece) in pieces) {
            board[square] = piece
        }
        return board
    }

    /** 语法糖：`Square(1, 2) has red(PieceType.HORSE)` 读起来接近棋谱 */
    infix fun Square.has(piece: Piece): Pair<Square, Piece> = this to piece

    fun red(type: PieceType): Piece = Piece(type, Side.FIRST)

    fun black(type: PieceType): Piece = Piece(type, Side.SECOND)

    /** 某枚子的合法落点集合，方便断言 */
    fun XiangqiRules.destinationsFrom(board: Board, from: Square): Set<Square> =
        legalMovesFrom(board, from).map { it.to }.toSet()
}
