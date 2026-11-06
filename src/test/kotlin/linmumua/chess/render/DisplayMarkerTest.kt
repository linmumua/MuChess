package linmumua.chess.render

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 归属标记的键名与用途取值。
 *
 * 为什么这几条值得单独钉住：键名是**已经写进世界里那些实体身上的**约定。改掉它，
 * 所有现存实体身上的旧键就再也读不出来 —— 它们会瞬间集体变成认不出、也删不掉的孤儿，
 * 而这正是本次修复要解决的问题本身。这种回归不会有编译错误，也不会有运行时异常，
 * 只会表现为"清扫命令报没找到，但世界里明明还有一片棋子"。
 *
 * 实体生成、PDC 读写要真实服务端才能跑，这里只覆盖不依赖 Bukkit 的那部分。
 */
class DisplayMarkerTest {

    @Test
    @DisplayName("键名是写进实体的约定，改动会让现存实体全部失联")
    fun `key names stay stable`() {
        assertEquals("board", DisplayMarker.BOARD_KEY)
        assertEquals("role", DisplayMarker.ROLE_KEY)
    }

    @Test
    @DisplayName("用途 id 是写进实体的约定，同样不能改")
    fun `role ids stay stable`() {
        assertEquals("piece", DisplayMarker.Role.PIECE.id)
        assertEquals("marker", DisplayMarker.Role.MARKER.id)
        assertEquals("label", DisplayMarker.Role.LABEL.id)
        assertEquals("label-hitbox", DisplayMarker.Role.LABEL_HITBOX.id)
    }

    @Test
    @DisplayName("四个用途各对应一个生成点，数量对不上说明有生成点漏打标记")
    fun `role count matches spawn sites`() {
        // 棋子、落点白圈、悬浮字、悬浮字判定框，四处 world.spawn 一一对应。
        // 新增显示实体生成点时这条会失败，提醒补标记 —— 漏打标记的实体清扫救不回来。
        assertEquals(4, DisplayMarker.Role.entries.size)
    }

    @Test
    @DisplayName("按 id 反查用途，认不出的返回 null 而不是猜一个")
    fun `role lookup by id`() {
        assertEquals(DisplayMarker.Role.PIECE, DisplayMarker.Role.byId("piece"))
        assertEquals(DisplayMarker.Role.LABEL_HITBOX, DisplayMarker.Role.byId("label-hitbox"))
        // 认不出时必须返回 null：猜成 PIECE 会让清扫日志把悬浮字报成棋子，误导排查
        assertNull(DisplayMarker.Role.byId("nonexistent"))
        assertNull(DisplayMarker.Role.byId(null))
    }
}
