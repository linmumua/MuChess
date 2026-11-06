package linmumua.chess.board

/**
 * 「玩家朝向哪副棋盘」的纯几何判定。
 *
 * 为什么不用按距离找（[linmumua.chess.game.GameManager.nearestSession]）：那套在棋盘挨着
 * 摆的场合会选错。两副棋盘间隔三四格时，站位稍偏就会命中身后那副 —— 管理员要拆的是
 * **看着的那副**，拆错一副正在下的棋是不可逆的。
 *
 * 为什么不用 `Player.getTargetBlock` / `World.rayTraceBlocks`：棋盘本体是 CraftEngine 家具
 * 实体，它占的那一格是**空气**，原版射线打不到；下面那块承托方块又可能被服主埋在地里，
 * 也可能压根不存在（浮空摆盘）。所以这里自己对棋盘占位的包围盒做射线求交，不问世界方块。
 *
 * 判定用 slab 法（逐轴求进出参数取交集），是精确解而不是采样步进：采样会漏掉贴边斜穿的
 * 情形 —— 射线从棋盘一角斜着掠过时，穿越长度可以任意短，步长再小也可能跳过去，
 * 表现成「明明对着棋盘却说找不到」。
 *
 * 纯逻辑，不碰 Bukkit，可单测。世界对象与 sessions 遍历留在调用方。
 */
object BoardGaze {

    /**
     * 射线与棋盘占位包围盒求交，返回命中距离。
     *
     * 包围盒取 [BoardFootprint.containsBoardPlane] 认的那两层：棋盘层 originY，
     * 加下面的承托层 originY-1。两层都算是有意的 —— 玩家俯视棋盘时准星大多落在棋盘面，
     * 平视时可能扫到承托层的侧面，两者都该算「对着这副棋盘」。
     *
     * @param originX 占位最小角的方块 X
     * @param originY 棋盘层的方块 Y
     * @param originZ 占位最小角的方块 Z
     * @param size 占位边长，象棋与五子棋 2，围棋 3
     * @param eyeX 视点 X，用玩家眼睛位置
     * @param eyeY 视点 Y
     * @param eyeZ 视点 Z
     * @param dirX 视线方向 X，不要求已归一化
     * @param dirY 视线方向 Y
     * @param dirZ 视线方向 Z
     * @param maxDistance 最远认到多少格，超过就算没看到
     * @return 命中点到视点的距离；没命中返回 null
     */
    fun hitDistance(
        originX: Int,
        originY: Int,
        originZ: Int,
        size: Int,
        eyeX: Double,
        eyeY: Double,
        eyeZ: Double,
        dirX: Double,
        dirY: Double,
        dirZ: Double,
        maxDistance: Double,
    ): Double? {
        // 方向向量归一化，返回值才是真正的「格数」。零向量直接判不命中：
        // 那不是一条射线，硬算会得出 NaN 再被下面的比较悄悄吞掉。
        val length = kotlin.math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ)
        if (length <= 0.0 || !length.isFinite()) {
            return null
        }
        val nx = dirX / length
        val ny = dirY / length
        val nz = dirZ / length

        // 包围盒：x/z 是 origin 起 size 格，y 是承托层底到棋盘层顶（共两格）
        var near = 0.0
        var far = maxDistance
        val bounds = arrayOf(
            Triple(eyeX, nx, originX.toDouble() to (originX + size).toDouble()),
            Triple(eyeY, ny, (originY - 1).toDouble() to (originY + 1).toDouble()),
            Triple(eyeZ, nz, originZ.toDouble() to (originZ + size).toDouble()),
        )
        for ((origin, direction, range) in bounds) {
            val (min, max) = range
            if (kotlin.math.abs(direction) < EPSILON) {
                // 该轴上射线是平行的：视点不在这一层的范围内就永远进不来
                if (origin < min || origin > max) {
                    return null
                }
                continue
            }
            // 该轴的进入与离开参数，方向为负时两者互换
            val t1 = (min - origin) / direction
            val t2 = (max - origin) / direction
            near = kotlin.math.max(near, kotlin.math.min(t1, t2))
            far = kotlin.math.min(far, kotlin.math.max(t1, t2))
            if (near > far) {
                // 各轴的进入区间没有交集，说明射线擦过盒子而不穿过它
                return null
            }
        }
        return near
    }

    /** 方向分量小于这个值就当成与该轴平行，避免除零得出无穷大 */
    private const val EPSILON = 1e-9
}
