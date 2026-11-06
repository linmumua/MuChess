package linmumua.chess.compat

import linmumua.chess.board.BoardFacing
import linmumua.chess.board.PieceOrientation
import linmumua.chess.render.MoveMarkerAppearance
import linmumua.chess.render.PieceAppearance
import linmumua.chess.rule.core.Piece
import org.bukkit.inventory.ItemStack
import taboolib.common.platform.function.warning

/**
 * 用 CraftEngine 自定义物品作棋子外观。
 *
 * 只有 CE 可用**并且**已经重载过一次（注册表非空）时才报告就绪。
 * CE 的 `loadedItems()` 在 onEnable 阶段是空的，这时候解析必然拿不到东西，
 * 所以由 [markReady] 在 `CraftEngineReloadEvent` 之后打开开关。
 */
object CraftEnginePieceAppearance : PieceAppearance {

    /** CE 注册表是否已经就绪 */
    @Volatile
    private var ready = false

    /** 解析结果缓存。每枚棋子的物品在一局里要取几十次，没必要反复问 CE */
    private val cache = HashMap<String, ItemStack>()

    /** 记过日志的缺失 id，避免同一个缺失刷满控制台 */
    private val warned = HashSet<String>()

    override fun isAvailable(): Boolean = ready && CraftEngineBridge.isAvailable

    override fun itemFor(piece: Piece, facing: BoardFacing): ItemStack? {
        val key = PieceOrientation.displayItemKey(piece.assetKey, facing)
        cache[key]?.let { return it.clone() }
        val resolved = CraftEngineBridge.itemOf(key)
        if (resolved == null) {
            // 缺一个资源不该让整副棋渲染失败，退兜底并只记一次
            if (warned.add(key)) {
                warning("CraftEngine 里找不到棋盘专用棋子资源 ${CraftEngineBridge.NAMESPACE}:$key，这枚棋子退回原版外观")
            }
            return null
        }
        cache[key] = resolved.clone()
        return resolved
    }

    /** CE 重载完成，注册表可用了 */
    fun markReady() {
        ready = true
        cache.clear()
        warned.clear()
    }

    /** CE 不可用或即将重载，先关掉 */
    fun markUnavailable() {
        ready = false
        cache.clear()
    }
}

/** 用 CraftEngine 内部物品 `muchess:move_marker` 作为空合法落点的白圈外观。 */
object CraftEngineMoveMarkerAppearance : MoveMarkerAppearance {

    @Volatile
    private var ready = false

    private var cache: ItemStack? = null
    private var warned = false

    override fun isAvailable(): Boolean = ready && CraftEngineBridge.isAvailable

    override fun item(): ItemStack? {
        cache?.let { return it.clone() }
        val resolved = CraftEngineBridge.itemOf(MARKER_ID)
        if (resolved == null) {
            if (!warned) {
                warned = true
                warning("CraftEngine 里找不到合法落点资源 ${CraftEngineBridge.NAMESPACE}:$MARKER_ID，已退回原版白色标记")
            }
            return null
        }
        cache = resolved.clone()
        return resolved
    }

    /** CE 重载完成，允许从注册表解析 marker */
    fun markReady() {
        ready = true
        cache = null
        warned = false
    }

    /** CE 卸载或即将重载，清掉旧物品缓存 */
    fun markUnavailable() {
        ready = false
        cache = null
    }

    private const val MARKER_ID = "move_marker"
}
