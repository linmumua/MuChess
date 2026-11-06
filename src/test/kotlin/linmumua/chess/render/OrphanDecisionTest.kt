package linmumua.chess.render

import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 孤儿判据。
 *
 * 判错的代价不对等，所以两个方向都要守住：
 * - 该删的没删 → 残留留在世界里，且没有任何其他路径能清掉它（本次修复要解决的问题）。
 * - 不该删的删了 → 正在进行的对局棋子凭空消失，不可逆。
 *
 * 判据本身是纯逻辑，实体与区块要真实服务端才能跑，所以这里只测判据。
 */
class OrphanDecisionTest {

    @Test
    @DisplayName("会话已死：它的实体不在任何在册集合里，判孤儿")
    fun `entity of dead session is orphan`() {
        val stranded = UUID.randomUUID()
        // 会话没了，它的渲染器一并没了，对全集零贡献 —— 全集为空
        assertTrue(OrphanDecision.isOrphan(stranded, emptySet()))
    }

    @Test
    @DisplayName("活会话但实体脱离索引：同样判孤儿，这是朴素判据漏掉的那一类")
    fun `entity dropped from a live session index is orphan`() {
        // 场景：会话还活着，索引里有两枚正常棋子，但第三枚因为半清状态丢了引用。
        // 朴素判据"会话在不在"会放过它，因为它身上带的是活会话的 id ——
        // 于是泄漏照旧无法恢复。改用"在不在册"才能覆盖。
        val healthy = setOf(UUID.randomUUID(), UUID.randomUUID())
        val dropped = UUID.randomUUID()
        assertTrue(OrphanDecision.isOrphan(dropped, healthy))
    }

    @Test
    @DisplayName("在册实体一律保留，否则会删掉正在对局的棋子")
    fun `tracked entity is never orphan`() {
        val tracked = UUID.randomUUID()
        val live = setOf(tracked, UUID.randomUUID())
        assertFalse(OrphanDecision.isOrphan(tracked, live))
    }

    @Test
    @DisplayName("多个会话的在册集合并起来之后，每个会话的实体都还认得")
    fun `union across sessions keeps every session safe`() {
        // 清扫是全局的：判一只实体时用的是所有活会话的并集，
        // 不能因为它不属于"当前遍历到的那个会话"就被误删。
        val firstBoard = setOf(UUID.randomUUID(), UUID.randomUUID())
        val secondBoard = setOf(UUID.randomUUID())
        val union = firstBoard + secondBoard
        firstBoard.forEach { assertFalse(OrphanDecision.isOrphan(it, union)) }
        secondBoard.forEach { assertFalse(OrphanDecision.isOrphan(it, union)) }
    }
}
