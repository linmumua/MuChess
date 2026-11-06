package linmumua.chess.compat

import linmumua.chess.game.GameManager
import linmumua.chess.pack.PackService
import linmumua.chess.render.BoardVisualRegistry
import linmumua.chess.render.MoveMarkerAppearanceRegistry
import linmumua.chess.render.PieceAppearanceRegistry
import taboolib.common.platform.function.info

/**
 * CraftEngine 就绪时机的处理。
 *
 * CE 的物品与家具注册表在 `onEnable` 阶段是空的，必须等 `CraftEngineReloadEvent`。
 * 这个约束来自 CE 官方 API 文档的明确警告，不是猜测：在 onEnable 里调
 * `loadedItems()` / `loadedFurniture()` 只会拿到空表。
 *
 * 事件监听没有直接写 `@SubscribeEvent(CraftEngineReloadEvent::class)`：
 * CE 是 softdepend，类不在时 TabooLib 扫描注解会因为拿不到事件类型而报错。
 * 改成运行期判断 CE 存在后再手动注册，缺 CE 时这段代码根本不会执行。
 */
object CraftEngineLifecycle {

    /** 是否已经注册过监听，避免重复注册 */
    private var registered = false

    /**
     * 尝试注册 CE 重载监听。
     *
     * @return 是否注册成功
     */
    fun register(): Boolean {
        if (registered) {
            return true
        }
        if (!CraftEngineBridge.isAvailable) {
            // CE 不在，两个外观都保持默认降级实现
            return false
        }
        return try {
            registerReloadListener()
            registered = true
            info("已注册 CraftEngine 重载监听，等 CE 加载完注册表后接管棋盘与棋子外观")
            true
        } catch (error: Throwable) {
            // 注册失败要说清原因，否则服主只会看到"外观没出来"却不知道为什么
            taboolib.common.platform.function.warning(
                "注册 CraftEngine 重载监听失败，外观降级：${error.javaClass.simpleName} ${error.message}",
            )
            false
        }
    }

    /**
     * 真正碰 CE 事件类的地方。
     *
     * 单独抽一个方法，是为了让 CE 类的加载推迟到这一步。
     * 上面已经确认过 CE 可用，这里才会被调用。
     */
    private fun registerReloadListener() {
        val plugin = org.bukkit.Bukkit.getPluginManager().getPlugin("CraftEngine")
            ?: error("CraftEngine 插件实例取不到")
        val self = taboolib.platform.BukkitPlugin.getInstance()
        org.bukkit.Bukkit.getPluginManager().registerEvent(
            net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent::class.java,
            object : org.bukkit.event.Listener {},
            org.bukkit.event.EventPriority.MONITOR,
            { _, event ->
                if (event is net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent) {
                    onReloaded()
                }
            },
            self,
            true,
        )
        info("CraftEngine ${plugin.pluginMeta.version} 已就位")
    }

    /** CE 重载完成，注册表可用；同时补查 muchess 目录，缺失时生成后再补一次 CE 重载。 */
    private fun onReloaded() {
        PackService.ensureDirectory()
        CraftEnginePieceAppearance.markReady()
        CraftEngineMoveMarkerAppearance.markReady()
        CraftEngineBoardVisual.markReady()
        PieceAppearanceRegistry.install(CraftEnginePieceAppearance)
        MoveMarkerAppearanceRegistry.install(CraftEngineMoveMarkerAppearance)
        BoardVisualRegistry.install(CraftEngineBoardVisual)
        // 旧四方块迁移或启动时 CE 未就绪的会话，会在这里补成单个家具。
        GameManager.reconcileVisuals()
        info("CraftEngine 注册表已加载，棋盘与棋子外观接管完成")
    }

    /** 关服或 CE 卸载时退回降级实现 */
    fun shutdown() {
        CraftEnginePieceAppearance.markUnavailable()
        CraftEngineMoveMarkerAppearance.markUnavailable()
        CraftEngineBoardVisual.markUnavailable()
        PieceAppearanceRegistry.reset()
        MoveMarkerAppearanceRegistry.reset()
        BoardVisualRegistry.reset()
    }
}
