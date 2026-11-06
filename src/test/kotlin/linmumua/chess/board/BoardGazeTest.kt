package linmumua.chess.board

import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 「对着哪副棋盘」的判定。
 *
 * 这套判定专门给管理员强拆用，而强拆**能拆正在下的棋**，所以选错棋盘就是毁掉别人一局。
 * 用例按「选错会怎样」组织：
 *
 * - 身后那副更近 → 按距离找会拆错，必须只认视线
 * - 斜着贴边掠过 → 采样步进会漏判，表现成「明明对着却说没对着」
 * - 背对棋盘 → 反方向不能算命中，否则转个身就误拆
 * - 超出距离 → 隔着半个服务器不该拆得到
 */
class BoardGazeTest {

    /** 2x2 棋盘，占位最小角在 (10,64,20)，棋盘层 y=64 */
    private val originX = 10
    private val originY = 64
    private val originZ = 20
    private val size = 2

    private fun hit(
        eyeX: Double,
        eyeY: Double,
        eyeZ: Double,
        dirX: Double,
        dirY: Double,
        dirZ: Double,
        maxDistance: Double = 16.0,
    ): Double? = BoardGaze.hitDistance(
        originX, originY, originZ, size,
        eyeX, eyeY, eyeZ,
        dirX, dirY, dirZ,
        maxDistance,
    )

    /**
     * 正对棋盘要命中，距离约等于实际间隔。
     *
     * 这是基准用例：站在 z 轴负方向 5 格外平视，射线沿 +z 穿进棋盘层。
     */
    @Test
    @DisplayName("正对棋盘命中，返回的距离是真实格数")
    fun `looking straight at the board hits`() {
        // 眼睛在 (10.5, 64.5, 15)，正对 +z；棋盘 z 从 20 起，所以约 5 格
        val distance = hit(10.5, 64.5, 15.0, 0.0, 0.0, 1.0)
        assertNotNull(distance, "正对着棋盘必须命中")
        assertTrue(distance in 4.9..5.1, "命中距离应约 5 格，实际 $distance")
    }

    /**
     * 背对棋盘不能命中。
     *
     * 少了这条，管理员转个身打命令就会拆掉背后那副棋盘。slab 法里把 near 的下界钉在 0
     * 就是为了这个：负方向的交点参数为负，不算命中。
     */
    @Test
    @DisplayName("背对棋盘不算命中，否则转身就会误拆背后那副")
    fun `looking away misses`() {
        assertNull(hit(10.5, 64.5, 15.0, 0.0, 0.0, -1.0), "背对棋盘不该命中")
    }

    /**
     * 视线擦过棋盘旁边不算命中。
     *
     * 这条防的是判定过宽：只要沿着某个轴对上就算命中的话，站在棋盘侧面很远处平视
     * 也会被判成「对着棋盘」，管理员就会拆到视野里根本没有的那副。
     */
    @Test
    @DisplayName("视线从棋盘旁边过去不算命中")
    fun `looking beside the board misses`() {
        // 眼睛在 x=10.5 沿 +z，但 z 抬到 70：从棋盘正上方掠过，不穿过它
        assertNull(hit(10.5, 70.0, 15.0, 0.0, 0.0, 1.0), "从上方掠过不该命中")
        // x 偏到 20：横向完全错开
        assertNull(hit(20.0, 64.5, 15.0, 0.0, 0.0, 1.0), "横向错开不该命中")
    }

    /**
     * 斜着贴边掠过要能命中。
     *
     * 这条是选 slab 法而不是采样步进的**理由**：射线从棋盘一角斜穿时，穿越长度可以任意短，
     * 按固定步长采样会整段跳过去，表现成「准星明明在棋盘上却说没对着」。
     * 精确求交没有这个盲区。
     */
    @Test
    @DisplayName("斜着贴角穿过也要命中，采样步进会在这里漏判")
    fun `grazing the corner still hits`() {
        // 从棋盘一角外侧斜向下看，射线只穿过角上很薄的一块
        val distance = hit(9.0, 66.0, 19.0, 1.0, -1.0, 1.0)
        assertNotNull(distance, "斜着贴角穿过必须命中")
    }

    /**
     * 超出最远距离不算命中。
     *
     * 强拆是个远程生效的操作，没有距离上限的话，管理员在很远处随手一个命令就可能
     * 拆掉视线尽头某副正在下的棋 —— 那时他根本看不见那副棋盘上有没有人。
     */
    @Test
    @DisplayName("超出最远距离不算命中")
    fun `beyond max distance misses`() {
        // 100 格外正对：几何上在视线上，但超过上限
        assertNull(hit(10.5, 64.5, -80.0, 0.0, 0.0, 1.0, maxDistance = 16.0), "超出上限不该命中")
        // 同一条射线放宽上限就该命中，证明上面那条是被距离挡的而不是几何算错
        assertNotNull(
            hit(10.5, 64.5, -80.0, 0.0, 0.0, 1.0, maxDistance = 200.0),
            "放宽上限后同一条射线必须命中，否则说明是几何算错而不是距离挡的",
        )
    }

    /**
     * 承托层也算棋盘。
     *
     * 棋盘本体是 CE 家具实体，它那一格是空气；玩家平视时准星常落在下面那块承托方块的侧面。
     * 不认这一层，会出现「明明戳着棋盘边缘却说没对着」。
     */
    @Test
    @DisplayName("承托层算在棋盘范围内，平视戳到侧面也算对着")
    fun `support layer counts as the board`() {
        // y=63.5 落在承托层（originY - 1 = 63）
        assertNotNull(hit(10.5, 63.5, 15.0, 0.0, 0.0, 1.0), "承托层该算命中")
    }

    /**
     * 零向量不算命中。
     *
     * 方向向量长度为 0 时归一化会产生 NaN，而 NaN 参与的比较全是 false，
     * 会安静地穿过所有判断返回一个假命中。这条钉住必须提前拒绝。
     */
    @Test
    @DisplayName("零方向向量直接判不命中，不能让 NaN 混成假命中")
    fun `zero direction misses`() {
        assertNull(hit(10.5, 64.5, 15.0, 0.0, 0.0, 0.0), "零向量不是射线，不该命中")
    }

    /**
     * 视点就在棋盘里时算命中，距离为 0。
     *
     * 管理员站在棋盘上打命令是常见操作，这时射线起点已经在包围盒内。
     * 若判成不命中，就会出现「站在棋盘上说没对着棋盘」。
     */
    @Test
    @DisplayName("站在棋盘上也算对着，距离为 0")
    fun `eye inside the box hits at zero`() {
        val distance = hit(10.5, 64.5, 20.5, 0.0, 1.0, 0.0)
        assertNotNull(distance, "视点在盒子里必须命中")
        assertTrue(distance <= 0.001, "视点已在盒内，距离应为 0，实际 $distance")
    }

    /**
     * 围棋的 3x3 占位要按实际边长判。
     *
     * 写死 2 会让围棋盘最外那一圈点不到 —— 那圈正是星位与角上最常用的落点区域。
     */
    @Test
    @DisplayName("3x3 占位按实际边长判，写死 2 会漏掉围棋盘最外一圈")
    fun `size parameter covers the go board`() {
        // x=12.5 落在第三格（originX + 2），只有 size=3 才包含
        val asGo = BoardGaze.hitDistance(
            originX, originY, originZ, 3,
            12.5, 64.5, 15.0, 0.0, 0.0, 1.0, 16.0,
        )
        assertNotNull(asGo, "3x3 占位应包含第三格")
        val asXiangqi = BoardGaze.hitDistance(
            originX, originY, originZ, 2,
            12.5, 64.5, 15.0, 0.0, 0.0, 1.0, 16.0,
        )
        assertNull(asXiangqi, "2x2 占位不该包含第三格，否则边界判过宽")
    }
}
