package linmumua.chess.board

import linmumua.chess.game.GameType
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 混合边长占位的测试。
 *
 * 围棋是第一副 3x3 棋盘，之前 [BoardFootprint] 全部按 2 写死。
 * 这些判定错了都不抛异常：重叠漏判的表现是两副棋盘家具 hitbox 串道，
 * 点一副的边缘会走进另一副的棋局；命中范围算小了则是围棋盘最外一圈点不动。
 * 所以必须专门钉住"尺寸真的被用上了"。
 */
class BoardFootprintSizeTest {

    @Test
    @DisplayName("围棋棋种的占位边长是 3，另两个是 2")
    fun `game types declare their footprint size`() {
        assertEquals(2, GameType.XIANGQI.footprintSize)
        assertEquals(2, GameType.GOMOKU.footprintSize)
        assertEquals(3, GameType.GO.footprintSize, "围棋 19 路铺 3x3")
        assertEquals(3.0, GameType.GO.span, "span 是 footprintSize 的浮点形式")
    }

    @Test
    @DisplayName("3x3 占位覆盖 9 格，2x2 仍是 4 格")
    fun `block count follows the size`() {
        assertEquals(4, BoardFootprint.blocksOf(0, 64, 0).size, "默认仍是 2x2 四格")
        assertEquals(4, BoardFootprint.blocksOf(0, 64, 0, size = 2).size)
        assertEquals(9, BoardFootprint.blocksOf(0, 64, 0, size = 3).size, "3x3 应有九格")
    }

    @Test
    @DisplayName("2x2 的格子顺序没被改动 —— selectChecked 依赖首格是 origin")
    fun `two by two ordering is unchanged`() {
        // require-flat-area 关闭时 BoardPlacement.selectChecked 会对这个列表 take(1)，
        // 首格必须仍是 origin 本身，否则"只检查一格"会检查错格子
        val blocks = BoardFootprint.blocksOf(10, 64, 20)
        assertEquals(Triple(10, 64, 20), blocks.first(), "首格必须是 origin")
        assertEquals(
            listOf(
                Triple(10, 64, 20),
                Triple(11, 64, 20),
                Triple(10, 64, 21),
                Triple(11, 64, 21),
            ),
            blocks,
            "2x2 的顺序与改动前保持一致",
        )
    }

    @Test
    @DisplayName("3x3 的九格不重不漏")
    fun `three by three covers every cell once`() {
        val blocks = BoardFootprint.blocksOf(0, 64, 0, size = 3)
        assertEquals(9, blocks.size)
        assertEquals(9, blocks.toSet().size, "九格不能有重复")
        for (x in 0..2) {
            for (z in 0..2) {
                assertTrue(Triple(x, 64, z) in blocks, "($x,$z) 应在 3x3 占位内")
            }
        }
    }

    @Test
    @DisplayName("3x3 的 pivot 落在正中那一格，往返换算一致")
    fun `three by three pivot is the centre cell`() {
        val (pivotX, pivotY, pivotZ) = BoardFootprint.pivotOf(0, 64, 0, size = 3)
        // 3/2 = 1，所以正中格是 (1,64,1)，正是 CE alignment: center 绕的那一点
        assertEquals(Triple(1, 64, 1), Triple(pivotX, pivotY, pivotZ))

        val (originX, originY, originZ) =
            BoardFootprint.originOfPivot(pivotX, pivotY, pivotZ, size = 3)
        assertEquals(Triple(0, 64, 0), Triple(originX, originY, originZ), "往返必须回到原点")
    }

    @Test
    @DisplayName("2x2 的 pivot 换算不受影响")
    fun `two by two pivot is unchanged`() {
        assertEquals(Triple(1, 64, 1), BoardFootprint.pivotOf(0, 64, 0))
        assertEquals(Triple(0, 64, 0), BoardFootprint.originOfPivot(1, 64, 1))
    }

    @Test
    @DisplayName("3x3 家具中心在格心，取整必须用 floor 而不是 round")
    fun `odd footprint pivot floors the furniture centre`() {
        // CE alignment: center 把 3x3 家具中心吸附到正中那一格的格心，
        // 世界坐标形如「整数 + 0.5」。pivot 要的是那一格的方块坐标，也就是 10/20。
        val (pivotX, pivotZ) = BoardFootprint.pivotFromFurnitureCenter(10.5, 20.5, size = 3)
        assertEquals(10 to 20, pivotX to pivotZ, "格心 (10.5,20.5) 所在的方块是 (10,20)")

        val (originX, _, originZ) = BoardFootprint.originOfPivot(pivotX, 64, pivotZ, size = 3)
        assertEquals(9 to 19, originX to originZ, "3x3 的最小角是 pivot - 1")

        // 这条是这个 bug 的核心：round 会把 .5 朝上多跳一整格，pivot 差 1 就是差 1 格。
        assertEquals(
            11,
            Math.round(10.5),
            "前提确认：Math.round(10.5) 是 11，所以旧写法必然把 pivot 顶到 11",
        )
        assertTrue(
            pivotX != Math.round(10.5).toInt(),
            "3x3 一旦退回 round，整盘就沿 +X 偏一整格（≈6.5 个围棋格间距）",
        )
    }

    @Test
    @DisplayName("3x3 在负坐标同样偏 +1，floor 才能取到所在方块")
    fun `odd footprint pivot handles negative coordinates`() {
        // Java Math.round 对 .5 一律朝 +∞ 取，所以负坐标不会把错误抵消掉，仍旧偏 +1。
        val (pivotX, pivotZ) = BoardFootprint.pivotFromFurnitureCenter(15.5, -47.5, size = 3)
        assertEquals(15 to -48, pivotX to pivotZ, "格心 (15.5,-47.5) 所在的方块是 (15,-48)")

        val (originX, _, originZ) = BoardFootprint.originOfPivot(pivotX, 63, pivotZ, size = 3)
        assertEquals(14 to -49, originX to originZ)

        // 旧写法在同一个家具上会算出 origin (15,-48) —— 正是测试服 boards.yml 里
        // 那副围棋盘存下的偏移值，棋子因此整体离开贴图交叉点、悬浮字飘出盘心。
        val staleOriginX = Math.round(15.5).toInt() - BoardFootprint.pivotOffsetOf(3)
        val staleOriginZ = Math.round(-47.5).toInt() - BoardFootprint.pivotOffsetOf(3)
        assertEquals(15 to -48, staleOriginX to staleOriginZ, "复现旧写法存下的错值")
        assertTrue(
            originX != staleOriginX && originZ != staleOriginZ,
            "修好后必须与旧存档值不同，否则等于没修",
        )
    }

    @Test
    @DisplayName("2x2 家具中心在整数交界，round 兼做浮点尾差防御")
    fun `even footprint pivot rounds the furniture centre`() {
        // alignment: corner 的家具中心落在四格交界上，本身就是整数，取整方式不能变。
        assertEquals(10 to 20, BoardFootprint.pivotFromFurnitureCenter(10.0, 20.0))
        assertEquals(-4 to -8, BoardFootprint.pivotFromFurnitureCenter(-4.0, -8.0))

        // 浮点尾差不能把棋盘甩到相邻方块：floor(9.999999) 会掉到 9，round 才是对的。
        assertEquals(
            10 to 20,
            BoardFootprint.pivotFromFurnitureCenter(9.999999, 20.000001),
            "2x2 必须留着 round，否则尾差会让 origin 偏 1 格",
        )
    }

    @Test
    @DisplayName("3x3 与 2x2 混排：错开 2 格重叠，错开 3 格不重叠")
    fun `mixed sizes overlap correctly`() {
        // 围棋盘在 origin (0,64,0) 占 x∈[0,3)；象棋盘在 (2,64,0) 占 x∈[2,4)，共享 x=2
        assertTrue(
            BoardFootprint.overlaps(
                0, 64, 0,
                2, 64, 0,
                sizeA = 3,
                sizeB = 2,
            ),
            "3x3 与错开 2 格的 2x2 共享一格，必须判重叠",
        )
        // 错开 3 格：x∈[0,3) 与 x∈[3,5) 正好相接不相交
        assertFalse(
            BoardFootprint.overlaps(
                0, 64, 0,
                3, 64, 0,
                sizeA = 3,
                sizeB = 2,
            ),
            "错开 3 格恰好相邻，不算重叠",
        )
    }

    @Test
    @DisplayName("大盘压小盘的方向也要判出来 —— 单尺寸算法会在这里漏判")
    fun `overlap detects the big over small direction`() {
        // 象棋盘在 (2,64,2)，围棋盘在 (0,64,0) 占 [0,3)×[0,3)，把象棋盘的角包住
        // 若两边都按 size=2 算，A 的范围会被算成 [0,2)，与 [2,4) 不相交 → 漏判
        assertTrue(
            BoardFootprint.overlaps(
                0, 64, 0,
                2, 64, 2,
                sizeA = 3,
                sizeB = 2,
            ),
            "3x3 的最外圈压住 2x2 的角，必须判重叠",
        )
        // 反过来传也必须一致：重叠是对称关系
        assertTrue(
            BoardFootprint.overlaps(
                2, 64, 2,
                0, 64, 0,
                sizeA = 2,
                sizeB = 3,
            ),
            "交换两副棋盘的顺序，重叠判定结果必须相同",
        )
    }

    @Test
    @DisplayName("不同 Y 层的棋盘仍不算重叠，与边长无关")
    fun `different y layers never overlap`() {
        assertFalse(
            BoardFootprint.overlaps(
                0, 64, 0,
                0, 65, 0,
                sizeA = 3,
                sizeB = 3,
            ),
            "上下楼各放一副 3x3 不算重叠",
        )
    }

    @Test
    @DisplayName("3x3 的命中范围覆盖最外一圈，范围外不误命中")
    fun `three by three hit test covers the outer ring`() {
        // 最外圈的角
        assertTrue(BoardFootprint.containsBlock(0, 64, 0, 2, 64, 2, size = 3))
        // 正中
        assertTrue(BoardFootprint.containsBlock(0, 64, 0, 1, 64, 1, size = 3))
        // 刚出界
        assertFalse(BoardFootprint.containsBlock(0, 64, 0, 3, 64, 0, size = 3))
        assertFalse(BoardFootprint.containsBlock(0, 64, 0, 0, 64, 3, size = 3))
        // 用默认 size=2 判同一个点会漏掉最外圈 —— 这正是必须传尺寸的原因
        assertFalse(
            BoardFootprint.containsBlock(0, 64, 0, 2, 64, 2),
            "按 2x2 判时 (2,2) 在范围外，说明尺寸参数确实起作用",
        )
    }

    @Test
    @DisplayName("3x3 的棋盘平面命中含承托层")
    fun `three by three board plane includes the support layer`() {
        assertTrue(
            BoardFootprint.containsBoardPlane(0, 64, 0, 2, 64, 2, size = 3),
            "占位层最外角应命中",
        )
        assertTrue(
            BoardFootprint.containsBoardPlane(0, 64, 0, 2, 63, 2, size = 3),
            "承托层（Y-1）同样命中",
        )
        assertFalse(
            BoardFootprint.containsBoardPlane(0, 64, 0, 2, 62, 2, size = 3),
            "再往下一层不该命中，否则棋盘下方无法建造",
        )
    }

    @Test
    @DisplayName("3x3 的家具扫描范围是 18 格：占位层 9 + 承托层 9")
    fun `three by three support scan covers eighteen blocks`() {
        val blocks = BoardFootprint.blocksWithSupport(0, 64, 0, size = 3)
        assertEquals(18, blocks.size)
        assertEquals(9, blocks.count { it.second == 64 }, "占位层九格")
        assertEquals(9, blocks.count { it.second == 63 }, "承托层九格")
        // 默认 2x2 仍是 8 格
        assertEquals(8, BoardFootprint.blocksWithSupport(0, 64, 0).size)
    }
}
