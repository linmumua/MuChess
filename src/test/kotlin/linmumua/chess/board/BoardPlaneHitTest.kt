package linmumua.chess.board

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 原版兜底命中只认棋盘平面，不能把整根 2x2 立柱都算成棋盘。
 *
 * 为什么这条重要：命中解析用的 `BoardAnchor.squareAt` 只比对 x/z，**完全不看 Y**。
 * 少了这道高度限制，棋盘正下方（以及正上方）任意高度的右键都会解析出合法格点，
 * 监听器随即 `event.isCancelled = true`，玩家就再也放不下方块 ——
 * 而 shift+右键因为另有"持方块时放过"的例外反而能放，正是被反馈的那个症状。
 */
class BoardPlaneHitTest {

    private val originX = 10
    private val originY = 64
    private val originZ = -20

    private fun hit(blockX: Int, blockY: Int, blockZ: Int): Boolean =
        BoardFootprint.containsBoardPlane(originX, originY, originZ, blockX, blockY, blockZ)

    /**
     * 棋盘正下方的立柱不算棋盘。
     *
     * 这就是"棋盘下方放不了方块"的直接成因。承托层（originY-1）之下的每一格都必须放过，
     * 否则右键被取消，方块放不出去。
     */
    @Test
    @DisplayName("棋盘正下方的立柱不算命中，方块放得下去")
    fun `column below the board is not a board hit`() {
        // 承托层再往下的每一格都要放过
        for (depth in 2..6) {
            assertFalse(
                hit(originX, originY - depth, originZ),
                "棋盘下方第 $depth 格被当成了棋盘，右键会被取消、方块放不下",
            )
        }
    }

    /**
     * 棋盘正上方同样不算。
     *
     * 只看 x/z 的话头顶放方块也会被吞。这一侧没被反馈到，但成因完全相同。
     */
    @Test
    @DisplayName("棋盘正上方也不算命中")
    fun `column above the board is not a board hit`() {
        for (height in 1..6) {
            assertFalse(
                hit(originX, originY + height, originZ),
                "棋盘上方第 $height 格被当成了棋盘，头顶放方块会被吞",
            )
        }
    }

    /**
     * 棋盘那一层与承托层都要认。
     *
     * CE 缺失时棋盘格是空气（棋盘本体是家具实体），玩家点到的是下面那块承托方块，
     * 它的顶面正好是棋盘平面。两层都不认的话原版兜底就完全失效，CE 缺失时没法下棋。
     */
    @Test
    @DisplayName("棋盘层与承托层都算命中")
    fun `board layer and its support are hits`() {
        assertTrue(hit(originX, originY, originZ), "棋盘所在层必须算命中")
        assertTrue(hit(originX, originY - 1, originZ), "承托层顶面就是棋盘平面，必须算命中")
    }

    /**
     * 2x2 之外的水平位置不算，四格之内都要算。
     *
     * 水平范围本来就由 SIZE 决定，这条防的是有人把高度限制加进来时顺手改坏了 x/z 判断。
     */
    @Test
    @DisplayName("水平范围仍是完整 2x2")
    fun `horizontal range stays the full two by two`() {
        for (dx in 0 until BoardFootprint.SIZE) {
            for (dz in 0 until BoardFootprint.SIZE) {
                assertTrue(
                    hit(originX + dx, originY, originZ + dz),
                    "2x2 内的 ($dx,$dz) 应算命中",
                )
            }
        }
        assertFalse(hit(originX - 1, originY, originZ), "2x2 西侧之外不算")
        assertFalse(hit(originX + BoardFootprint.SIZE, originY, originZ), "2x2 东侧之外不算")
        assertFalse(hit(originX, originY, originZ - 1), "2x2 北侧之外不算")
        assertFalse(hit(originX, originY, originZ + BoardFootprint.SIZE), "2x2 南侧之外不算")
    }
}
