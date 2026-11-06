package linmumua.chess.compat

import kotlin.test.assertEquals
import linmumua.chess.game.GameType
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 桥接层认得的棋盘家具 id 必须覆盖全部棋种。
 *
 * 为什么这条必须有测试守着：桥接层刻意不认棋种（家具 id 到棋种的映射属于业务侧），
 * 于是 `CraftEngineBridge.BOARD_IDS` 与 `GameType.furnitureId` 成了两处需要手工同步的清单。
 * 漏补的表现极其难查 —— 资源包照常生成家具、玩家拿得到物品、放下去家具也确实出现，
 * 但 `boardFurnitureId` 认不出它而返回 null，`onPlace` 静默 return：
 * 没有会话、没有警告、没有任何提示，玩家看到的就是"这个棋种不能玩"。
 *
 * 围棋就是这样漏的：`GameType.GO` 与构建期 `PackSpec` 都有 `go_board`，
 * 只有 `BOARD_IDS` 少了它，围棋因此完全无法开局。
 *
 * 这条测试不依赖 Bukkit 也不依赖 CE，只比两个常量集合，所以能在单测里跑。
 */
class BoardFurnitureIdsTest {

    @Test
    @DisplayName("每个棋种的棋盘家具 id 都要被桥接层认得，漏一个那个棋种就完全不能玩")
    fun `bridge recognises every game type board`() {
        val fromGameTypes = GameType.entries.map { it.furnitureId }.toSet()
        assertEquals(
            fromGameTypes,
            CraftEngineBridge.BOARD_IDS,
            "BOARD_IDS 与 GameType.furnitureId 不一致。新增棋种时两处都要补：" +
                "只补 GameType 会让新棋种放下家具却建不起棋局，且没有任何提示。",
        )
    }

    @Test
    @DisplayName("象棋常量与枚举保持一致，BOARD_ID 是历史遗留的单独引用点")
    fun `xiangqi board id matches its game type`() {
        assertEquals(GameType.XIANGQI.furnitureId, CraftEngineBridge.BOARD_ID)
    }
}
