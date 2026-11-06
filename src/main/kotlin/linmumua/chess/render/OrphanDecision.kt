package linmumua.chess.render

import java.util.UUID

/**
 * 判断一只带标记的显示实体是不是孤儿。
 *
 * 抽成不依赖 Bukkit 的纯函数是为了可单测：清扫会 `remove()` 实体，判错就是不可逆的
 * 误删，而实体、区块、PDC 全都要真实服务端才能跑。判据本身是纯逻辑，值得单独钉住。
 *
 * ## 判据为什么是"不在册"而不是"会话已死"
 *
 * 朴素判据是"标记里的会话在 GameManager 查不到就删"。它漏掉一整类泄漏：
 * 某个**活着**的会话，渲染器索引丢了对某只实体的引用（`pieces` 里被移走但没 `remove()`，
 * 或 `clear()` 中途抛异常留下半清状态）。那只实体带的是活会话的 id，朴素判据会放过它，
 * 于是泄漏照旧无法恢复 —— 而这正是最需要清扫的场景。
 *
 * 所以改用"在册实体 UUID 全集"：一只带标记的实体是孤儿 ⟺ 它的 UUID 不在全集里。
 * 一次判断同时覆盖两类：
 *
 * - 会话已死：它的渲染器一并没了，对全集零贡献 → 它的实体必然不在全集 → 判孤儿。
 * - 会话还活着但实体脱离索引：那只实体不在全集 → 判孤儿；仍在索引的正常实体在全集里 → 保留。
 *
 * ## 为什么全集是可靠真源
 *
 * 读码确认过三条路径都维持"在册 = 活着"：`BoardRenderer.applyMove` 走子时先
 * `pieces.remove(from)` 再 `pieces[to] = display`，实体始终在册；吃掉的子当场 `remove()`；
 * 三个渲染器的 `clear()` 都是先 `remove()` 实体再清索引。
 *
 * ## 无竞态
 *
 * 标记写在 `world.spawn` 的初始化 lambda 里，索引插入紧跟 spawn 返回，两者与清扫
 * 都在主线程、同一 tick 内不会交错，不会误删刚生成还没入册的实体。
 */
object OrphanDecision {

    /**
     * 这只实体是不是孤儿。
     *
     * @param entityId 待判实体的 UUID
     * @param liveEntityIds 当前所有活会话在册实体 UUID 的并集
     * @return true 表示无主，可以删
     */
    fun isOrphan(entityId: UUID, liveEntityIds: Set<UUID>): Boolean = entityId !in liveEntityIds
}
