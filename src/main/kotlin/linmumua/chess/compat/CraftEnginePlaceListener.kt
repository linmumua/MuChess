package linmumua.chess.compat

import linmumua.chess.config.ChessConfig
import linmumua.chess.config.Msg
import linmumua.chess.game.BoardPlacement
import linmumua.chess.game.GameManager
import linmumua.chess.game.GameType
import linmumua.chess.game.GamePhase
import linmumua.chess.game.PlaceResult
import linmumua.chess.render.BoardVisualHandle
import linmumua.chess.render.PlacementFeedback
import org.bukkit.Bukkit
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import taboolib.common.platform.function.info
import taboolib.common.platform.function.warning

/**
 * 棋盘家具的放置与破坏。
 *
 * 棋盘只能由 CE 棋盘物品放置，CE 一次放成完整 2x2 家具。本监听只认领该家具、
 * 建立棋局与处理整副回收，不再补放或拆除四个自定义方块。
 *
 * 这是**唯一**的建棋局入口：插件已去掉 `/chess place` 与 `/chess give`，
 * 棋盘物品由 CE 自己的给予命令发放。
 */
object CraftEnginePlaceListener : Listener {

    /** 是否已注册，避免重复挂监听 */
    private var registered = false

    /** 注册家具放置与破坏监听，CE 不可用时直接返回 false */
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
            info("已接管 CraftEngine 棋盘家具放置与破坏，单个家具覆盖完整 2x2")
            true
        } catch (error: Throwable) {
            warning(
                "注册 CraftEngine 家具放置监听失败，give 出来的棋盘将不会自动建棋局：" +
                    "${error.javaClass.simpleName} ${error.message}",
            )
            false
        }
    }

    /** softdepend 场景下推迟 CE 事件类加载 */
    private fun attach() {
        val plugin = taboolib.platform.BukkitPlugin.getInstance()
        // 这里曾经挂过 FurnitureAttemptPlaceEvent 去清草，理由写的是"这是唯一能让棋盘
        // 按清完草的地面定位的时机"。那条理由是错的，已删：
        //
        // 1. CE 在触发该事件**之前**就算好了落点（事件里的 location() 还是个 clone，
        //    改它也没用），清草降不下落点；
        // 2. 更糟的是清错了层。草在 y=64、CE 落点在 y=65 时，那里按落点层清的是
        //    y=65 的空气，一格都没清到。
        //
        // 真正生效的是 placeFromFurniture：先用 groundLevel 把 anchor 落回实体方块顶面，
        // 再按修正后的层清草，最后用 Furniture.moveTo 把家具整体搬下去。
        Bukkit.getPluginManager().registerEvent(
            net.momirealms.craftengine.bukkit.api.event.FurniturePlaceEvent::class.java,
            this,
            EventPriority.NORMAL,
            { _, event ->
                if (event is net.momirealms.craftengine.bukkit.api.event.FurniturePlaceEvent) {
                    onPlace(event)
                }
            },
            plugin,
            true,
        )
        Bukkit.getPluginManager().registerEvent(
            net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent::class.java,
            this,
            EventPriority.NORMAL,
            { _, event ->
                if (event is net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent) {
                    onBreak(event)
                }
            },
            plugin,
            true,
        )
    }

    /**
     * CE 已放出家具后认领它并建棋局。
     *
     * 校验失败直接取消事件：CE 会销毁刚创建的家具并恢复放置物品，不留下空壳。
     */
    private fun onPlace(event: net.momirealms.craftengine.bukkit.api.event.FurniturePlaceEvent) {
        // 放置事件只会为实际使用的那只手触发一次，副手放置同样必须建立棋局。
        val furniture = event.furniture()
        // 家具 id 决定棋种：每个棋种一个棋盘家具，放下哪种就开哪种棋局。
        val furnitureId = CraftEngineBridge.boardFurnitureId(furniture) ?: return
        val gameType = GameType.byFurnitureId(furnitureId) ?: run {
            warning("棋盘家具 $furnitureId 没有对应棋种，已忽略放置")
            return
        }
        val location = CraftEngineBridge.boardFurnitureLocation(furniture) ?: event.location()
        val handle = CraftEngineBridge.boardFurnitureHandle(furniture)
        if (handle == null) {
            event.isCancelled = true
            warning("棋盘家具已放置但取不到 base entity UUID，已取消放置")
            Msg.send(event.player(), "board-incomplete")
            return
        }

        when (
            val result = GameManager.placeFromFurniture(
                furnitureLocation = location,
                handle = BoardVisualHandle(handle),
                gameType = gameType,
            )
        ) {
            is PlaceResult.Success -> Unit
            PlaceResult.NoSpace -> {
                event.isCancelled = true
                Msg.send(event.player(), "board-no-space")
            }
            is PlaceResult.LimitReached -> {
                event.isCancelled = true
                Msg.send(event.player(), "board-limit-reached", "limit" to result.limit)
            }
            is PlaceResult.FurnitureOverlap -> {
                event.isCancelled = true
                // 动作栏提示：说清有家具阻挡，换地方放
                Msg.actionBar(event.player(), "board-furniture-overlap")
                // 粒子标出被阻挡的格子，让玩家直观看到哪里挡住了
                PlacementFeedback.markBlocked(event.player(), result.blocked)
            }
        }
    }

    /**
     * 破坏家具任意 hitbox 都收走同一副棋盘。
     *
     * 进行中的棋局禁止拆除。允许收走时关闭会话与棋子，但让 CE 当前 break 流程负责
     * 删除家具实体，避免同一事件里重复 remove；默认掉落关闭，最多手工返还一个物品。
     */
    private fun onBreak(event: net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent) {
        val furniture = event.furniture()
        val furnitureId = CraftEngineBridge.boardFurnitureId(furniture) ?: return
        val gameType = GameType.byFurnitureId(furnitureId) ?: return
        val location = CraftEngineBridge.boardFurnitureLocation(furniture) ?: event.location()
        val handle = CraftEngineBridge.boardFurnitureHandle(furniture)
        val session = handle?.let(GameManager::sessionByVisualHandle) ?: run {
            // 兜底按坐标找会话时也要带棋种：围棋 3x3 的 origin 推算与 2x2 不同。
            val anchor = BoardPlacement.anchorForFurniture(location, ChessConfig.settings.board, gameType)
            GameManager.sessionAtOrigin(
                anchor.world,
                anchor.origin.blockX,
                anchor.origin.blockY,
                anchor.origin.blockZ,
            )
        }
        if (session == null) {
            // 两条查找都没命中，但 CE 已经要把这副家具拆掉了。
            // 家具一消失就再没有任何事件会经过这个位置，所以必须就地把脱离索引的
            // 显示实体收掉，否则棋子与悬浮字会一直留在原地 —— 这正是残留的来源之一。
            val swept = GameManager.sweepAt(location)
            if (swept > 0) {
                info("拆掉的棋盘家具没有对应会话，就地清掉 $swept 只残留显示实体")
            }
            return
        }

        val player = event.player()
        if (session.game.phase == GamePhase.PLAYING) {
            event.isCancelled = true
            // 走动作栏：挖家具很容易连着挖好几下，每下都发一条聊天会瞬间刷屏。
            // 与 ChessCommandHandler.remove 里同一条键保持同一个通道。
            Msg.actionBar(player, "board-remove-in-game")
            return
        }

        event.setDropItems(false)
        GameManager.remove(session, removeVisual = false)
        if (ChessConfig.settings.board.refundOnBreak) {
            // 退还的必须是同一棋种的棋盘，拆五子棋盘不能退回象棋盘
            giveBoardBack(player, gameType.furnitureId)
        }
    }

    /** 退还一个棋盘物品，背包满就掉在脚下 */
    private fun giveBoardBack(player: org.bukkit.entity.Player, furnitureId: String) {
        val item = CraftEngineBridge.itemOf(furnitureId)
        if (item == null) {
            warning("退还棋盘物品失败：CE 取不到 $furnitureId")
            return
        }
        val leftover = player.inventory.addItem(item)
        for (drop in leftover.values) {
            player.world.dropItemNaturally(player.location, drop)
        }
    }
}
