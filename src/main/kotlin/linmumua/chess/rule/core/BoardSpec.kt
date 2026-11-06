package linmumua.chess.rule.core

/**
 * 棋盘尺寸。
 *
 * 三个棋种的盘面尺寸不同，规则层不能再把尺寸写成编译期常量：
 * 象棋 9×10、五子棋 15×15、围棋 19×19。
 *
 * [Square] 的编码用固定步长 [Square.STRIDE] 而不是 [files]，
 * 因此同一个 packed 值在不同尺寸下解出的 file/rank 一致，
 * 尺寸只决定"哪些点在盘内"。这样 Square 能继续当 value class 用，
 * 也不必把 spec 传进每一次坐标解码。
 */
data class BoardSpec(
    /** 纵线数量 */
    val files: Int,
    /** 横线数量 */
    val ranks: Int,
) {

    init {
        require(files in 1..Square.STRIDE) { "纵线数应在 1..${Square.STRIDE}，实际 $files" }
        require(ranks in 1..Square.STRIDE) { "横线数应在 1..${Square.STRIDE}，实际 $ranks" }
    }

    /** 交叉点总数 */
    val squareCount: Int get() = files * ranks

    /** 该点是否落在盘内 */
    fun contains(square: Square): Boolean =
        square.file in 0 until files && square.rank in 0 until ranks

    /** 遍历全盘交叉点，按 rank 后 file 的顺序 */
    fun squares(): Sequence<Square> = sequence {
        for (rank in 0 until ranks) {
            for (file in 0 until files) {
                yield(Square(file, rank))
            }
        }
    }

    /**
     * 把交叉点压成 0 until [squareCount] 的连续序号。
     *
     * packed 带空洞（步长固定 32），需要连续下标的地方（如数组存局面）用这个。
     */
    fun indexOf(square: Square): Int = square.rank * files + square.file

    /** [indexOf] 的逆运算 */
    fun squareAt(index: Int): Square = Square(index % files, index / files)

    companion object {
        /** 象棋：9 纵线 × 10 横线，共 90 点 */
        val XIANGQI = BoardSpec(files = 9, ranks = 10)

        /** 五子棋：15×15 */
        val GOMOKU = BoardSpec(files = 15, ranks = 15)

        /** 围棋：19×19 标准盘 */
        val GO = BoardSpec(files = 19, ranks = 19)
    }
}
