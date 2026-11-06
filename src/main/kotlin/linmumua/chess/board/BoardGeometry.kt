package linmumua.chess.board

import linmumua.chess.rule.core.BoardSpec
import linmumua.chess.rule.core.Square

/**
 * 棋盘几何换算，纯数学，不依赖 Bukkit。
 *
 * 把格点网格铺到方块占位的正方形面上，并提供反查。
 * 单独拆出来是为了能单测：坐标算错不会抛异常，只会让棋子悄悄错位，
 * 必须靠往返测试兜住。
 *
 * 网格尺寸与占位边长都不能写死 —— 象棋 9×10 铺 2x2、五子棋 15×15 铺 2x2、
 * 围棋 19×19 铺 3x3。局部坐标以占位区域最小角为原点，范围 0.0..[span]。
 */
class BoardGeometry(
    val facing: BoardFacing,
    /** 网格尺寸 */
    val spec: BoardSpec = BoardSpec.XIANGQI,
    /** 占位边长（方块数），象棋与五子棋 2.0，围棋 3.0 */
    val span: Double = BoardAnchor.BOARD_SPAN,
) {

    /** 格点区域可用宽度，两侧各留 [BoardAnchor.EDGE_INSET] 做边框 */
    private val usable = span - 2 * BoardAnchor.EDGE_INSET

    /** 相邻纵线的间距 */
    val fileSpacing: Double = usable / (spec.files - 1)

    /** 相邻横线的间距 */
    val rankSpacing: Double = usable / (spec.ranks - 1)

    /** 交叉点 → 占位区域内的局部偏移 (x, z) */
    fun localOffsetOf(square: Square): Pair<Double, Double> {
        val u = BoardAnchor.EDGE_INSET + square.file.toDouble() / (spec.files - 1) * usable
        val v = BoardAnchor.EDGE_INSET + square.rank.toDouble() / (spec.ranks - 1) * usable
        return facing.toWorldOffset(u, v, span)
    }

    /**
     * 局部偏移 → 最近的交叉点。
     *
     * 先四舍五入到最近格点，再复核实际距离。超过容差返回 null，
     * 这样玩家点在棋盘边框上不会被硬吸到最外圈格点。
     */
    fun squareAtLocal(x: Double, z: Double): Square? {
        val (u, v) = facing.fromWorldOffset(x, z, span)
        val ratioU = (u - BoardAnchor.EDGE_INSET) / usable
        val ratioV = (v - BoardAnchor.EDGE_INSET) / usable

        val file = Math.round(ratioU * (spec.files - 1)).toInt()
        val rank = Math.round(ratioV * (spec.ranks - 1)).toInt()
        if (file !in 0 until spec.files || rank !in 0 until spec.ranks) {
            return null
        }

        val square = Square(file, rank)
        val (targetX, targetZ) = localOffsetOf(square)
        val dx = targetX - x
        val dz = targetZ - z
        val tolerance = maxOf(fileSpacing, rankSpacing) * BoardAnchor.SNAP_TOLERANCE_RATIO
        return if (dx * dx + dz * dz <= tolerance * tolerance) square else null
    }
}
