package linmumua.chess.compat

import linmumua.chess.board.BoardFacing
import linmumua.chess.render.BoardVisual
import linmumua.chess.render.BoardVisualHandle
import org.bukkit.Location
import taboolib.common.platform.function.warning

/**
 * 用一个 CraftEngine 家具呈现完整 2x2 棋盘。
 *
 * 家具 pivot 位于四格交界，模型自身以 pivot 为中心覆盖 2x2。句柄存 CE 家具
 * base entity 的 UUID，移除时整副棋盘只删一个家具，不再管理四个方块 part。
 */
object CraftEngineBoardVisual : BoardVisual {

    /** CE 家具注册表是否已完成加载 */
    @Volatile
    private var ready = false

    override fun isAvailable(): Boolean = ready && CraftEngineBridge.isAvailable

    override fun place(origin: Location, facing: BoardFacing, boardId: String): BoardVisualHandle? {
        if (!isAvailable()) {
            return null
        }
        val handle = CraftEngineBridge.placeBoardFurniture(origin, facing, boardId) ?: return null
        return BoardVisualHandle(handle)
    }

    override fun remove(handle: BoardVisualHandle) {
        if (!CraftEngineBridge.removeBoardFurniture(handle.id)) {
            warning("移除棋盘家具失败或实体未加载，句柄 ${handle.id}")
        }
    }

    override fun moveTo(handle: BoardVisualHandle, target: Location): Boolean =
        CraftEngineBridge.moveBoardFurniture(handle.id, target)

    /**
     * 家具注册表还没就绪时一律返回 true。
     *
     * 那个窗口内 CE 查不到任何家具，逐个判都会是"已死"，调用方会给每副棋盘
     * 各补一副新的。就绪之后才交给桥接层实判。
     */
    override fun isAlive(handle: BoardVisualHandle): Boolean {
        if (!isAvailable()) {
            return true
        }
        return CraftEngineBridge.isBoardFurnitureAlive(handle.id)
    }

    /** CE 重载完成，家具注册表可用 */
    fun markReady() {
        ready = true
    }

    /** CE 不可用 */
    fun markUnavailable() {
        ready = false
    }
}
