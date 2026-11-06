package linmumua.chess.game

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import linmumua.chess.rule.core.BoardSpec
import org.junit.jupiter.api.Test

/**
 * 棋种注册表测试。
 *
 * 棋种是"家具 id、规则、盘面尺寸、占位边长"四样绑在一起的东西，任意一项对不上
 * 都不会报错，只会让棋盘看着对但行为错：家具 id 对不上就没法开局，
 * 尺寸对不上就棋子全错位，规则对不上就走法判定用错棋种。
 * 这些都是静默故障，只能靠一致性断言兜住。
 */
class GameTypeTest {

    /**
     * 每个棋种的家具 id 必须唯一。
     *
     * 两个棋种共用一个家具 id 时，`byFurnitureId` 只会命中先注册的那个，
     * 放下另一种棋盘会开出错的棋局。
     */
    @Test
    fun `家具 id 与命令 id 都不重复`() {
        val furnitureIds = GameType.entries.map { it.furnitureId }
        assertEquals(furnitureIds.size, furnitureIds.toSet().size, "家具 id 有重复：$furnitureIds")
        val ids = GameType.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "命令 id 有重复：$ids")
    }

    /**
     * 家具 id 必须能反查回同一个棋种。
     *
     * 这是放置流程的关键一跳：CE 事件只给家具 id，查不回棋种就开不了局。
     */
    @Test
    fun `家具 id 能反查回同一棋种`() {
        for (type in GameType.entries) {
            assertEquals(type, GameType.byFurnitureId(type.furnitureId))
        }
        assertNull(GameType.byFurnitureId("muchess:not_a_board"), "不认识的家具 id 必须返回 null")
    }

    /**
     * 命令参数用的英文 id 必须大小写无关地查得到。
     *
     * debug 命令允许玩家打 `Gomoku` 或 `GOMOKU`，大小写敏感会让命令莫名失败。
     */
    @Test
    fun `命令 id 大小写无关`() {
        assertEquals(GameType.XIANGQI, GameType.byId("xiangqi"))
        assertEquals(GameType.GOMOKU, GameType.byId("GOMOKU"))
        assertEquals(GameType.GOMOKU, GameType.byId("Gomoku"))
        assertNull(GameType.byId("shogi"))
    }

    /**
     * 每个棋种的盘面尺寸必须与它规则实现认的尺寸一致。
     *
     * 这条是最要紧的一致性：`GameType.spec` 决定棋子铺在几乘几的网格上，
     * 规则实现里的 spec 决定走法与终局怎么算。两者不一致时棋子会落在
     * 规则认为不存在的格点上，或者规则算出的落点渲染不出来。
     */
    @Test
    fun `棋种尺寸与规则实现的尺寸一致`() {
        for (type in GameType.entries) {
            val rules = type.createRules()
            assertEquals(
                type.spec,
                rules.initialBoard().spec,
                "棋种 ${type.id} 的 spec 与规则实现给出的初始局面尺寸不符",
            )
        }
    }

    /** 象棋 9×10 铺 2x2，五子棋 15×15 铺 2x2 —— 尺寸写错会让整盘错位 */
    @Test
    fun `棋种绑定的尺寸与占位符合各棋种规格`() {
        assertEquals(BoardSpec.XIANGQI, GameType.XIANGQI.spec)
        assertEquals(2, GameType.XIANGQI.footprintSize)
        assertEquals(BoardSpec.GOMOKU, GameType.GOMOKU.spec)
        assertEquals(2, GameType.GOMOKU.footprintSize)
    }

    /**
     * 每次取规则都得是新实例。
     *
     * 规则实例带可调项，共用单例时一副棋盘改了限着回合数会影响其它所有棋盘。
     */
    @Test
    fun `每次创建规则都是独立实例`() {
        for (type in GameType.entries) {
            val first = type.createRules()
            val second = type.createRules()
            assertNotNull(first)
            assertTrue(first !== second, "棋种 ${type.id} 的规则被共用成单例了")
        }
    }

    /**
     * 五子棋是落子棋、象棋是走子棋，这个标记决定交互层走哪条分支。
     *
     * 标记错了不会报错：五子棋会变成"先点选一个空点再点目标"，玩家永远落不下子。
     */
    @Test
    fun `落子棋与走子棋的标记正确`() {
        assertEquals(false, GameType.XIANGQI.createRules().isPlacement)
        assertEquals(true, GameType.GOMOKU.createRules().isPlacement)
    }
}
