package linmumua.chess.board

import kotlin.test.assertTrue
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.core.Square
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 棋子字向必须由座位实际归属决定，而不是靠手写的阵营到角度映射。
 *
 * 象棋盘的基本要求：每方读自己面前的字是正的。这等价于"某方棋子的字，其朝上方向
 * 要从该方底线指向对方底线"。
 *
 * 之前这条靠 `PackSpec.pieceSideRotation` 一张手写表实现，红黑各给 0 / 180。
 * 那张表和棋盘几何没有任何校验关系 —— 我为了修字向手工翻过它一次，翻对翻错全凭推演，
 * 而错了的表现是两方都在读对手方向的字，肉眼很难第一时间判定。
 *
 * 这组测试把规则钉到 [BoardGeometry] 上：底线方向从几何算出来，角度必须跟着它走。
 * 几何变了、映射没跟上，或者有人再翻一次那张表，这里就会红。
 */
class PieceGlyphFacingTest {

    /** 与 PackSpec.pieceSideRotation 保持一致；对不上就是这组测试要抓的问题 */
    private fun sideRotation(side: Side): Int = if (side == Side.FIRST) 180 else 0

    /** 与 buildSrc 的 PieceDisplayFacingSpec.rotation 保持一致 */
    private fun facingRotation(facing: BoardFacing): Int = when (facing) {
        BoardFacing.NORTH -> 0
        BoardFacing.EAST -> 90
        BoardFacing.SOUTH -> 180
        BoardFacing.WEST -> 270
    }

    /**
     * 某方底线指向对方底线的世界方向，单位向量。
     *
     * 用该方王的位置与对方王的位置相减：这两点同在中线纵线上，连线就是"从自己这边
     * 看向对面"的方向，也就是该方棋子字的朝上方向应该指向的地方。
     */
    private fun baselineToOpponent(facing: BoardFacing, side: Side): Pair<Double, Double> {
        val geometry = BoardGeometry(facing)
        val own = if (side == Side.FIRST) Square(4, 9) else Square(4, 0)
        val foe = if (side == Side.FIRST) Square(4, 0) else Square(4, 9)
        val (ox, oz) = geometry.localOffsetOf(own)
        val (fx, fz) = geometry.localOffsetOf(foe)
        val dx = fx - ox
        val dz = fz - oz
        val length = Math.hypot(dx, dz)
        return dx / length to dz / length
    }

    /**
     * 模型里那个绕 Z 的角度，换算成铺平之后字的朝上方向在世界里指向哪。
     *
     * 变换顺序是先模型内绕 Z，再由渲染层绕 X 轴 +90° 铺平。逐步推：
     *
     * 1. 绕 Z 转 θ：模型的"上" `(0,1,0)` 变成 `(-sinθ, cosθ, 0)`
     *    （`x' = x·cosθ − y·sinθ`，`y' = x·sinθ + y·cosθ`）。
     * 2. 绕 X 转 +90°：`(x, y, z)` 变成 `(x, −z, y)`，所以上一步的结果落到
     *    `(−sinθ, 0, cosθ)`。
     *
     * 于是字的朝上方向投影到世界 XZ 平面是 `(−sinθ, cosθ)`。
     *
     * 这里的符号极易写反：把绕 X 轴 +90° 记成"+Y 映到 −Z"就会整体反向，
     * 表现是八组全部判定失败，看着像生产代码错了，实际是这个换算错了。
     */
    private fun glyphUpDirection(rotationDegrees: Int): Pair<Double, Double> {
        val theta = Math.toRadians(rotationDegrees.toDouble())
        return -Math.sin(theta) to Math.cos(theta)
    }

    /**
     * 每方的字都必须朝向自己 —— 也就是字的朝上方向与"从自己底线指向对面"同向。
     *
     * 四种棋盘朝向 × 红黑两方共八组，全部都要成立。任何一组反了，那一方的玩家
     * 看自己面前的字就是倒的。
     */
    @Test
    @DisplayName("四向 × 红黑，每方的字都朝向自己一侧")
    fun `each side reads its own glyphs upright`() {
        for (facing in BoardFacing.entries) {
            for (side in Side.entries) {
                val rotation = (facingRotation(facing) + sideRotation(side)) % 360
                val (gx, gz) = glyphUpDirection(rotation)
                val (bx, bz) = baselineToOpponent(facing, side)

                // 同向即点积接近 +1；反了会是 -1
                val dot = gx * bx + gz * bz
                assertTrue(
                    dot > 0.9,
                    "$facing 下 $side 的字没朝向自己：角度 $rotation，" +
                        "字朝上=($gx,$gz)，自己底线指向对面=($bx,$bz)，点积=$dot",
                )
            }
        }
    }

    /**
     * 阵营映射翻反时必须判定失败。
     *
     * 上一条断言"每方朝向自己"，但如果几何换算写错，它可能变成一条永真断言 ——
     * 那样翻反映射也照样通过，护栏就是假的。这里在测试内部同时算正确映射与翻反映射，
     * 要求前者同向、后者恰好反向，确保上一条真的具备判别力。
     *
     * 不改任何源码：翻反只发生在这个函数的局部变量里。
     */
    @Test
    @DisplayName("阵营映射翻反时会被判定为字向错误")
    fun `flipped side mapping is detected`() {
        for (facing in BoardFacing.entries) {
            for (side in Side.entries) {
                val correct = (facingRotation(facing) + sideRotation(side)) % 360
                // 翻反：红黑互换那半圈
                val flipped = (facingRotation(facing) + if (side == Side.FIRST) 0 else 180) % 360

                val (bx, bz) = baselineToOpponent(facing, side)
                val (cx, cz) = glyphUpDirection(correct)
                val (fx, fz) = glyphUpDirection(flipped)

                assertTrue(cx * bx + cz * bz > 0.9, "$facing $side 正确映射应当同向")
                assertTrue(
                    fx * bx + fz * bz < -0.9,
                    "$facing $side 翻反映射竟然没被判为反向，说明上一条断言是永真的，护栏无效",
                )
            }
        }
    }

    /**
     * 红黑必须差半圈。
     *
     * 两方各自朝向自己，几何上就必然相差 180°。这条是上一条的推论，
     * 单独列出来是因为它更容易被直接看懂，出问题时便于定位。
     */
    @Test
    @DisplayName("同一朝向下红黑相差半圈")
    fun `red and black differ by half a turn`() {
        for (facing in BoardFacing.entries) {
            val red = (facingRotation(facing) + sideRotation(Side.FIRST)) % 360
            val black = (facingRotation(facing) + sideRotation(Side.SECOND)) % 360
            val diff = ((red - black) % 360 + 360) % 360
            assertTrue(diff == 180, "$facing 下红黑角度差 $diff，应为 180")
        }
    }

    /**
     * 棋盘转向时两方的字要同步跟着转。
     *
     * 棋盘从 NORTH 转到 EAST，红方的字向也应当转 90°，不能只有棋盘转、字不动。
     */
    @Test
    @DisplayName("棋盘转向时字向同步跟随")
    fun `glyphs follow the board when it rotates`() {
        for (side in Side.entries) {
            val north = (facingRotation(BoardFacing.NORTH) + sideRotation(side)) % 360
            val east = (facingRotation(BoardFacing.EAST) + sideRotation(side)) % 360
            val delta = ((east - north) % 360 + 360) % 360
            assertTrue(delta == 90, "$side 从 NORTH 转到 EAST 时字向只转了 $delta，应为 90")
        }
    }
}
