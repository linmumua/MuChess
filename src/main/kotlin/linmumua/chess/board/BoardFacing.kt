package linmumua.chess.board

/**
 * 棋盘朝向。
 *
 * 决定"先行方底线朝哪个世界方向"。四个朝向对应四种旋转。
 * 归一化坐标 (u, v) 的语义固定：u 是先行方视角自左向右的纵线方向，
 * v 从后行方底线（0.0）到先行方底线。各朝向只负责把 (u, v) 转成
 * 占位区域内的局部 (x, z) 偏移，反之亦然。
 *
 * 这样做的好处是格点换算逻辑只写一份，旋转不会污染坐标计算。
 *
 * 旋转要绕占位中心翻折，所以 [span] 必须由调用方传进来 ——
 * 象棋与五子棋占 2x2（span=2.0），围棋占 3x3（span=3.0）。
 * 早先这里写死 [BoardAnchor.BOARD_SPAN]，围棋盘一旦不是朝北就会整盘错位到占位外。
 */
enum class BoardFacing(val yaw: Float) {

    /** 先行方底线在南（+Z 方向） */
    NORTH(0f) {
        override fun toWorldOffset(u: Double, v: Double, span: Double): Pair<Double, Double> = u to v
        override fun fromWorldOffset(x: Double, z: Double, span: Double): Pair<Double, Double> = x to z
    },

    /** 先行方底线在西（-X 方向） */
    EAST(90f) {
        override fun toWorldOffset(u: Double, v: Double, span: Double): Pair<Double, Double> =
            (span - v) to u

        override fun fromWorldOffset(x: Double, z: Double, span: Double): Pair<Double, Double> =
            z to (span - x)
    },

    /** 先行方底线在北（-Z 方向） */
    SOUTH(180f) {
        override fun toWorldOffset(u: Double, v: Double, span: Double): Pair<Double, Double> =
            (span - u) to (span - v)

        override fun fromWorldOffset(x: Double, z: Double, span: Double): Pair<Double, Double> =
            (span - x) to (span - z)
    },

    /** 先行方底线在东（+X 方向） */
    WEST(270f) {
        override fun toWorldOffset(u: Double, v: Double, span: Double): Pair<Double, Double> =
            v to (span - u)

        override fun fromWorldOffset(x: Double, z: Double, span: Double): Pair<Double, Double> =
            (span - z) to x
    },
    ;

    /** 归一化棋盘坐标 → 占位区域内的局部世界偏移 */
    abstract fun toWorldOffset(u: Double, v: Double, span: Double): Pair<Double, Double>

    /** 占位区域内的局部世界偏移 → 归一化棋盘坐标 */
    abstract fun fromWorldOffset(x: Double, z: Double, span: Double): Pair<Double, Double>

    /**
     * CE 家具 base entity 应使用的 yaw。
     *
     * `FurnitureItemBehavior.place()` 会以玩家 yaw + 180° 建 base entity；资源里的
     * item_display 再本地转 180° 抵消，所以程序化放置也要给 base entity 同样的半圈偏移。
     */
    val furnitureYaw: Float get() = normalizeYaw(yaw + 180f)

    companion object {
        /** 按玩家视线 yaw 取最接近的朝向，用于放置棋盘时自动对齐 */
        fun fromYaw(yaw: Float): BoardFacing {
            val normalized = normalizeYaw(yaw)
            return when {
                normalized < 45 || normalized >= 315 -> NORTH
                normalized < 135 -> EAST
                normalized < 225 -> SOUTH
                else -> WEST
            }
        }

        /** CE base entity yaw → 棋盘视觉朝向 */
        fun fromFurnitureYaw(yaw: Float): BoardFacing = fromYaw(yaw - 180f)

        /** 把 yaw 收进 [0, 360) */
        fun normalizeYaw(yaw: Float): Float = ((yaw % 360f) + 360f) % 360f
    }
}
