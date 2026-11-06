package linmumua.chess.compat

import linmumua.chess.listener.BoardInteractListener
import org.bukkit.Bukkit
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import taboolib.common.platform.function.info
import taboolib.common.platform.function.warning

/**
 * CraftEngine 棋盘家具右键的命中转发。
 *
 * 使用 `FurnitureInteractEvent.interactionPoint()` 反算交叉点。一个完整 2x2 家具上
 * 有多个交叉点，只看家具实体位置无法区分，必须保留精确命中点。
 */
object CraftEngineInteractListener : Listener {

    /** 是否已注册，避免重复挂监听 */
    private var registered = false

    /** 注册家具交互监听，CE 不可用时直接返回 false */
    fun register(): Boolean {
        if (registered) {
            return true
        }
        if (!CraftEngineBridge.isAvailable) {
            return false
        }
        return try {
            attach()
            registered = true
            info("已接管 CraftEngine 棋盘家具右键，落点用 interactionPoint 反算")
            true
        } catch (error: Throwable) {
            warning("注册 CraftEngine 家具交互监听失败，棋盘将退回原版右键命中：${error.javaClass.simpleName} ${error.message}")
            false
        }
    }

    /** softdepend 场景下推迟 CE 事件类加载 */
    private fun attach() {
        Bukkit.getPluginManager().registerEvent(
            net.momirealms.craftengine.bukkit.api.event.FurnitureInteractEvent::class.java,
            this,
            EventPriority.NORMAL,
            { _, event ->
                if (event is net.momirealms.craftengine.bukkit.api.event.FurnitureInteractEvent) {
                    onFurnitureInteract(event)
                }
            },
            taboolib.platform.BukkitPlugin.getInstance(),
            true,
        )
    }

    /** 主手只处理一次；识别到本插件家具后把精确落点交给统一交互入口 */
    private fun onFurnitureInteract(
        event: net.momirealms.craftengine.bukkit.api.event.FurnitureInteractEvent,
    ) {
        if (!CraftEngineBridge.isBoardFurniture(event.furniture())) {
            return
        }
        val point = event.interactionPoint()
        val handle = CraftEngineBridge.boardFurnitureHandle(event.furniture())
        if (event.hand() != net.momirealms.craftengine.core.entity.player.InteractionHand.MAIN_HAND) {
            return
        }
        val player = event.player()
        val held = player.inventory.itemInMainHand.type
        // Shift+右键空手/非方块打开菜单；拿着方块时放过事件，继续允许在棋盘下方建造。
        if (player.isSneaking && !held.isAir && held.isBlock) {
            return
        }
        event.isCancelled = true
        BoardInteractListener.handleFurniture(player, handle, point)
    }
}
