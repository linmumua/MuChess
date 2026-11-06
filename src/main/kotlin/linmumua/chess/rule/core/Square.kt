package linmumua.chess.rule.core

/**
 * 棋盘交叉点坐标。
 *
 * [file] 为纵线序号，从先行方视角自左向右递增。
 * [rank] 为横线序号，后行方底线为 0。
 *
 * 尺寸不再写死在这里 —— 三个棋种盘面不同（象棋 9×10、五子棋 15×15、
 * 围棋 19×19），"哪些点在盘内"由 [BoardSpec] 判定。
 *
 * 编码用固定步长 [STRIDE]（32，2 的幂，位运算可解）而不是纵线数，
 * 好处是同一个 packed 值在任何尺寸下解出的 file/rank 都一致，
 * 解码不需要 spec，Square 也能继续当 value class 避免装箱。
 * 代价是 packed 值带空洞，需要连续下标时走 [BoardSpec.indexOf]。
 */
@JvmInline
value class Square(val packed: Int) {

    constructor(file: Int, rank: Int) : this((rank shl SHIFT) or file)

    val file: Int get() = packed and MASK

    val rank: Int get() = packed ushr SHIFT

    /**
     * 偏移到相邻点。
     *
     * 只做非负与步长上限的粗校验，是否真在盘内由 [BoardSpec.contains] 判定 ——
     * 这里不知道棋盘多大。
     */
    fun offset(deltaFile: Int, deltaRank: Int): Square? {
        val nextFile = file + deltaFile
        val nextRank = rank + deltaRank
        if (nextFile !in 0 until STRIDE || nextRank !in 0 until STRIDE) {
            return null
        }
        return Square(nextFile, nextRank)
    }

    override fun toString(): String = "($file,$rank)"

    companion object {
        /**
         * 编码步长。
         *
         * 取 32 是因为它是 2 的幂（位运算解码），且大于最大棋种的 19 路。
         * 想支持更大的盘要同时改这里和 [BoardSpec] 的上限校验。
         */
        const val STRIDE = 32

        /** [STRIDE] 对应的位移量 */
        const val SHIFT = 5

        /** [STRIDE] 对应的低位掩码 */
        const val MASK = STRIDE - 1
    }
}
