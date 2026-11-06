package linmumua.chess.board

import linmumua.chess.rule.core.BoardSpec
import linmumua.chess.rule.core.Square
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.util.Vector

/**
 * 棋盘的世界坐标标定。
 *
 * 负责"棋盘交叉点 ↔ 世界坐标"的双向换算，是渲染层与交互层共用的唯一坐标真源。
 * 规则层不认识世界坐标，世界坐标的事全在这一侧。
 *
 * 棋盘占位 2x2 方块，face 决定棋盘朝向（红方在哪一侧）。
 * [origin] 是 2x2 区域西北角方块的最小角坐标，也就是 2x2 的左上角格点。
 *
 * @param world 棋盘所在世界
 * @param origin 2x2 占位区域的最小角（西北角方块的 x/z 最小值）
 * @param surfaceY 棋盘面的世界 Y 高度，棋子贴在这个高度上
 * @param facing 棋盘朝向，决定红方底线在哪一边
 */
class BoardAnchor(
    val world: World,
    val origin: Vector,
    val surfaceY: Double,
    val facing: BoardFacing,
    /**
     * 格点网格尺寸。
     *
     * 默认象棋 9×10。五子棋 15×15、围棋 19×19 必须显式传 ——
     * 用错尺寸不会报错，只会让棋子按另一套网格铺开，整盘位置全错。
     */
    val spec: BoardSpec = BoardSpec.XIANGQI,
    /** 占位边长（方块数）。象棋与五子棋 2.0，围棋 3.0 */
    val span: Double = BOARD_SPAN,
) {

    /** 纯几何换算，坐标数学全在这里，便于单测 */
    private val geometry = BoardGeometry(facing, spec, span)

    /**
     * 交叉点 → 世界坐标。
     *
     * 按本棋盘的网格尺寸与占位边长铺开，不假定 9×10 或 2x2。
     */
    fun locationOf(square: Square): Location {
        val (offsetX, offsetZ) = geometry.localOffsetOf(square)
        return Location(
            world,
            origin.x + offsetX,
            surfaceY,
            origin.z + offsetZ,
        )
    }

    /**
     * 世界坐标 → 最近的交叉点。
     *
     * 用于把 CraftEngine `FurnitureInteractEvent.interactionPoint()` 的落点
     * 反算成玩家点的是哪个格点。超出容差返回 null，避免玩家点在棋盘边框外
     * 也被吸附到最近格点上。
     */
    fun squareAt(location: Location): Square? {
        if (location.world != world) {
            return null
        }
        return geometry.squareAtLocal(location.x - origin.x, location.z - origin.z)
    }

    /**
     * 棋盘面中心，用于播报粒子、朝向计算等。
     *
     * 必须用本盘的 [span] 而不是常量 [BOARD_SPAN]：围棋占位 3x3，
     * 用写死的 2.0 算出来的"中心"会偏到棋盘的 1/3 处。表现是粒子、
     * 悬浮字与区块判定全部偏向棋盘一角，而且不会报任何错。
     */
    fun centerLocation(): Location = Location(
        world,
        origin.x + span / 2,
        surfaceY,
        origin.z + span / 2,
    )

    /** 相邻纵线的间距，交互层用来算落点标记间距与吸附范围 */
    val fileSpacing: Double get() = geometry.fileSpacing

    /** 相邻横线的间距，交互层用来算落点标记间距与吸附范围 */
    val rankSpacing: Double get() = geometry.rankSpacing

    companion object {
        /** 棋盘占位边长，2x2 方块 */
        const val BOARD_SPAN = 2.0

        /**
         * 完整棋盘薄板的厚度，单位方块。
         *
         * 资源模型 Y 范围是 0..2 模型单位，即 2/16 = 0.125 格；家具 pivot 在薄板底面。
         * 棋子与粒子都以顶面为 Y 基准。这个值与构建期完整模型的高度必须同步修改。
         */
        const val BOARD_THICKNESS = 0.125

        /** 格点区域相对棋盘边缘的内缩量，留出边框 */
        const val EDGE_INSET = 0.12

        /** 吸附容差占单格间距的比例，超过这个距离视为点空 */
        const val SNAP_TOLERANCE_RATIO = 0.6
    }
}
