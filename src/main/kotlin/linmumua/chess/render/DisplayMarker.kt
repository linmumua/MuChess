package linmumua.chess.render

import java.util.UUID
import org.bukkit.NamespacedKey
import org.bukkit.entity.Entity
import org.bukkit.persistence.PersistentDataType
import taboolib.platform.BukkitPlugin

/**
 * 给本插件生成的显示实体打归属标记。
 *
 * 存在的理由是**可恢复性**。棋子、白圈、悬浮字、判定框都由各渲染器的内存索引管着，
 * 索引是唯一真源。可是索引一旦丢了对某个实体的引用（会话没经过 `close()` 就从表里消失、
 * 渲染器 `clear()` 中途抛异常留下半清状态），那只实体在世界里就再没有任何东西能证明
 * 它属于 MuChess —— 既认不出来，也删不掉，只能等区块卸载。
 *
 * 标记把归属信息从"只在内存里"变成"也写在实体身上"，于是清扫有了依据。
 * 这不是给正常走棋用的：走棋路径查交叉点仍然只走内存索引（项目明确禁止轮询扫实体找棋子）。
 * 标记只在清扫路径上读，见 [OrphanDecision]。
 *
 * `isPersistent = false` 与本标记是两层不同的防护：前者防重启后残留，
 * 后者防服务端不停、索引丢了引用的残留。两者都需要。
 */
object DisplayMarker {

    /** 归属会话 id 的键名，值是会话 UUID 字符串 */
    const val BOARD_KEY = "board"

    /** 实体用途的键名，取值见 [Role] */
    const val ROLE_KEY = "role"

    /**
     * 显示实体的用途。
     *
     * 清扫日志按用途分类报数，服主能一眼看出漏的是棋子还是牌子，
     * 排查时不必再去世界里逐个点。
     */
    enum class Role(val id: String) {
        /** 棋子，见 [BoardRenderer.spawn] */
        PIECE("piece"),

        /** 合法落点白圈，见 [HighlightRenderer] */
        MARKER("marker"),

        /** 棋盘上方悬浮字，见 [BoardLabelRenderer] */
        LABEL("label"),

        /** 悬浮字的命中判定框，与 [LABEL] 成对 */
        LABEL_HITBOX("label-hitbox"),
        ;

        companion object {
            /** 按存进实体的 id 反查，认不出的返回 null */
            fun byId(id: String?): Role? = entries.firstOrNull { it.id == id }
        }
    }

    /** 会话归属键，插件实例在 ENABLE 之后才有，所以惰性取 */
    private val boardKey: NamespacedKey by lazy {
        NamespacedKey(BukkitPlugin.getInstance(), BOARD_KEY)
    }

    /** 用途键 */
    private val roleKey: NamespacedKey by lazy {
        NamespacedKey(BukkitPlugin.getInstance(), ROLE_KEY)
    }

    /**
     * 打标记。
     *
     * 必须在 `world.spawn` 的初始化 lambda 里调，与 `isPersistent = false` 同一处：
     * 那时实体还没进世界，写 PDC 不额外多一次实体更新；等 spawn 返回再写就是第二次改动。
     *
     * @param entity 刚创建的显示实体
     * @param sessionId 所属会话
     * @param role 这只实体的用途
     */
    fun mark(entity: Entity, sessionId: UUID, role: Role) {
        val container = entity.persistentDataContainer
        container.set(boardKey, PersistentDataType.STRING, sessionId.toString())
        container.set(roleKey, PersistentDataType.STRING, role.id)
    }

    /** 是否是本插件打过标记的显示实体 */
    fun isMarked(entity: Entity): Boolean =
        entity.persistentDataContainer.has(boardKey, PersistentDataType.STRING)

    /**
     * 读归属会话 id，没标记或值损坏时返回 null。
     *
     * 值损坏（不是合法 UUID）按"没标记"处理而不是当孤儿删：删实体不可逆，
     * 而写坏值只可能来自外部改动，宁可留着让服主自己处理。
     */
    fun sessionIdOf(entity: Entity): UUID? {
        val raw = entity.persistentDataContainer.get(boardKey, PersistentDataType.STRING) ?: return null
        return runCatching { UUID.fromString(raw) }.getOrNull()
    }

    /** 读用途，没标记或认不出时返回 null */
    fun roleOf(entity: Entity): Role? =
        Role.byId(entity.persistentDataContainer.get(roleKey, PersistentDataType.STRING))
}
