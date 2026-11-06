package linmumua.chess.rule.core

/**
 * 棋盘状态。
 *
 * 只负责"哪个交叉点上有什么子"，以及走子/撤销这两个原子操作。
 * 不知道世界坐标，不知道玩家，不知道渲染。
 *
 * 这里选**可变棋盘 + apply/undo** 而不是不可变拷贝，理由是：
 * 合法着法生成要对每个伪合法着法试走一次再撤销，一步棋平均 40 余个伪合法着法，
 * 每次都整盘拷贝 90 格会在选中棋子这种高频交互上产生无谓开销。
 * 需要快照时用 [copy] 显式拿一份。
 */
class Board private constructor(
    /** 盘面尺寸，决定哪些交叉点在盘内 */
    val spec: BoardSpec,
    private val squares: Array<Piece?>,
    /** 该走棋的一方 */
    var sideToMove: Side,
    /** 距上一次吃子已走的**半回合**数，用于自然限着判和 */
    var halfMovesSinceCapture: Int,
) {

    /** 撤销时用来还原 [halfMovesSinceCapture]，与 apply 一一对应 */
    private val clockHistory = ArrayDeque<Int>()

    constructor(
        spec: BoardSpec = BoardSpec.XIANGQI,
        sideToMove: Side = Side.FIRST,
    ) : this(spec, arrayOfNulls(slotsFor(spec)), sideToMove, 0)

    /** 该点是否落在盘内 */
    fun contains(square: Square): Boolean = spec.contains(square)

    /**
     * 取某点上的棋子，空点或盘外返回 null。
     *
     * 盘外返回 null 而不是抛异常，是因为車/炮的射线扫描本来就靠"走到盘外就停"收尾，
     * 每次探测前都先判一次边界会让调用侧啰嗦。
     */
    operator fun get(square: Square): Piece? =
        if (spec.contains(square)) squares[square.packed] else null

    /** 直接摆子，仅用于摆局面与序列化，不校验走法合法性 */
    operator fun set(square: Square, piece: Piece?) {
        require(spec.contains(square)) { "交叉点 $square 不在 ${spec.files}×${spec.ranks} 盘内" }
        squares[square.packed] = piece
    }

    /** 该点是否为空（盘外视为非空，避免误当落点） */
    fun isEmpty(square: Square): Boolean = spec.contains(square) && squares[square.packed] == null

    /** 该点是否被指定一方占据 */
    fun isOccupiedBy(square: Square, side: Side): Boolean = get(square)?.side == side

    /** 找出某方的将/帥所在点，找不到返回 null（残局摆子出错时不抛异常，由上层判定） */
    fun kingSquare(side: Side): Square? {
        // 将只可能在九宫 9 个点内，没必要扫全盘
        val ranks = if (side == Side.FIRST) 7..9 else 0..2
        for (rank in ranks) {
            for (file in 3..5) {
                val square = Square(file, rank)
                val piece = squares[square.packed]
                if (piece != null && piece.type == PieceType.KING && piece.side == side) {
                    return square
                }
            }
        }
        return null
    }

    /** 列出某方全部棋子所在点 */
    fun squaresOf(side: Side): List<Square> {
        val result = ArrayList<Square>(16)
        for (index in squares.indices) {
            if (squares[index]?.side == side) {
                result += Square(index)
            }
        }
        return result
    }

    /** 列出全盘空点，五子棋与围棋的着法生成靠这个 */
    fun emptySquares(): List<Square> {
        val result = ArrayList<Square>(spec.squareCount)
        for (square in spec.squares()) {
            if (squares[square.packed] == null) {
                result += square
            }
        }
        return result
    }

    /** 全盘棋子数量 */
    fun pieceCount(): Int = squares.count { it != null }

    /**
     * 走一步棋。
     *
     * [move] 的 `captured` 必须与当前局面一致，调用方应通过走法生成器拿着法，
     * 不要自行伪造被吃子。走完自动切换 [sideToMove]。
     */
    fun apply(move: Move) {
        clockHistory.addLast(halfMovesSinceCapture)
        squares[move.from.packed] = null
        squares[move.to.packed] = move.piece
        halfMovesSinceCapture = if (move.isCapture) 0 else halfMovesSinceCapture + 1
        sideToMove = sideToMove.opposite
    }

    /** 撤销最近一步棋，必须与 [apply] 严格配对 */
    fun undo(move: Move) {
        squares[move.from.packed] = move.piece
        squares[move.to.packed] = move.captured
        halfMovesSinceCapture = clockHistory.removeLastOrNull() ?: 0
        sideToMove = sideToMove.opposite
    }

    /** 按当前局面补全被吃子信息，交互层只知道起终点时用这个构造着法 */
    fun moveAt(from: Square, to: Square): Move? {
        val piece = squares[from.packed] ?: return null
        return Move(from, to, piece, squares[to.packed])
    }

    /** 深拷贝一份快照，历史时钟不带过去 */
    fun copy(): Board = Board(spec, squares.copyOf(), sideToMove, halfMovesSinceCapture)

    /**
     * 紧凑局面串，形如 `rheakaehr/........./.c.....c./... r 0`。
     *
     * 借用国际象棋 FEN 的思路但用本项目自己的棋子字符（见 [Piece.code]），
     * 段与段之间用空格分隔：局面 / 走棋方 / 距上次吃子的半回合数。
     * 只服务持久化与测试可读性，不追求与外部棋谱工具互通。
     */
    fun toFen(): String {
        val builder = StringBuilder()
        for (rank in 0 until spec.ranks) {
            if (rank > 0) {
                builder.append('/')
            }
            var empty = 0
            for (file in 0 until spec.files) {
                val piece = squares[Square(file, rank).packed]
                if (piece == null) {
                    empty++
                } else {
                    if (empty > 0) {
                        builder.append(empty)
                        empty = 0
                    }
                    builder.append(piece.code)
                }
            }
            if (empty > 0) {
                builder.append(empty)
            }
        }
        builder.append(' ').append(if (sideToMove == Side.FIRST) 'r' else 'b')
        builder.append(' ').append(halfMovesSinceCapture)
        return builder.toString()
    }

    override fun toString(): String = toFen()

    companion object {

        /** 棋子字符到种类的反查表，由 [PieceType.code] 推出，避免两处各写一份映射 */
        private val TYPE_BY_CODE: Map<Char, PieceType> =
            PieceType.entries.associateBy { it.code }

        /**
         * 从 [toFen] 产出的串还原局面。
         *
         * 串不合法时抛 [IllegalArgumentException]：持久化数据坏掉必须炸出来，
         * 不能静默摆出一个错局面继续下。
         */
        /** [Board] 内部数组要开多大：packed 编码带空洞，按最大 packed 值算 */
        internal fun slotsFor(spec: BoardSpec): Int =
            Square(spec.files - 1, spec.ranks - 1).packed + 1

        fun fromFen(fen: String, spec: BoardSpec = BoardSpec.XIANGQI): Board {
            val parts = fen.trim().split(' ').filter { it.isNotEmpty() }
            require(parts.isNotEmpty()) { "局面串为空" }
            val rows = parts[0].split('/')
            require(rows.size == spec.ranks) { "局面串横线数应为 ${spec.ranks}，实际 ${rows.size}" }

            val side = when (parts.getOrNull(1)) {
                null, "r" -> Side.FIRST
                "b" -> Side.SECOND
                else -> throw IllegalArgumentException("走棋方标记不合法: ${parts[1]}")
            }
            val board = Board(spec, side)
            for ((rank, row) in rows.withIndex()) {
                var file = 0
                var cursor = 0
                while (cursor < row.length) {
                    val char = row[cursor]
                    if (char.isDigit()) {
                        // 连续数字要整段读，不能一位一位读：
                        // 五子棋一行 15 格、围棋 19 格，空行会写成 "15"/"19"，
                        // 按单字符读会解析成 1 和 5 两段，只前进 6 格，整盘错位。
                        var digits = 0
                        while (cursor < row.length && row[cursor].isDigit()) {
                            digits = digits * 10 + (row[cursor] - '0')
                            cursor++
                        }
                        require(digits > 0) { "第 $rank 行出现 0 长度空段" }
                        file += digits
                        continue
                    }
                    cursor++
                    require(file < spec.files) { "第 $rank 行棋子数超出 ${spec.files} 纵线" }
                    val type = TYPE_BY_CODE[char.lowercaseChar()]
                        ?: throw IllegalArgumentException("未知棋子字符: $char")
                    val pieceSide = if (char.isUpperCase()) Side.FIRST else Side.SECOND
                    board[Square(file, rank)] = Piece(type, pieceSide)
                    file++
                }
                require(file == spec.files) { "第 $rank 行宽度应为 ${spec.files}，实际 $file" }
            }
            board.halfMovesSinceCapture = parts.getOrNull(2)?.toIntOrNull() ?: 0
            return board
        }
    }
}
