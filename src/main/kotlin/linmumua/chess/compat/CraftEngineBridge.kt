package linmumua.chess.compat

import java.nio.file.Path
import java.util.UUID
import linmumua.chess.board.BoardFacing
import linmumua.chess.board.BoardFootprint
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import taboolib.common.platform.function.info
import taboolib.common.platform.function.submit
import taboolib.common.platform.function.submitAsync
import taboolib.common.platform.function.warning

/**
 * CraftEngine 的唯一入口。
 *
 * CE 是 softdepend，业务代码不能直接依赖注册表生命周期。物品、家具放置/移除与
 * 类型识别都收敛在这里；CE 缺失或版本不兼容时返回安全空值并写清失败原因。
 *
 * CE 26.8 家具 API：
 * - `CraftEngineItems.byId(String)` 取物品定义；
 * - `CraftEngineFurniture.place(Location, Key)` 放家具；
 * - `CraftEngineFurniture.remove(Entity, boolean, boolean)` 移除家具；
 * - `BukkitFurniture.baseEntity()` 是稳定句柄实体。
 */
object CraftEngineBridge {

    /** CE 插件名，plugin.yml 里的 softdepend 用的是这个字符串 */
    private const val PLUGIN_NAME = "CraftEngine"

    /** CE 类是否真的能加载 */
    private val classesPresent: Boolean by lazy {
        try {
            Class.forName("net.momirealms.craftengine.bukkit.api.CraftEngineItems")
            Class.forName("net.momirealms.craftengine.bukkit.api.CraftEngineFurniture")
            true
        } catch (error: Throwable) {
            warning("CraftEngine API 类加载不上，外观功能降级：${error.javaClass.simpleName} ${error.message}")
            false
        }
    }

    /** CE 是否可用：插件装了、启用了、类也能加载 */
    val isAvailable: Boolean
        get() {
            val plugin = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME) ?: return false
            return plugin.isEnabled && classesPresent
        }

    /** CE 的插件数据目录，用于定位 resources 目录 */
    fun dataFolder(): Path? = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME)?.dataFolder?.toPath()

    /** 按资源 id 取自定义物品，取不到返回 null */
    fun itemOf(assetKey: String): ItemStack? {
        if (!isAvailable) {
            return null
        }
        return guard("取物品 $assetKey") {
            val definition = net.momirealms.craftengine.bukkit.api.CraftEngineItems.byId("$NAMESPACE:$assetKey")
                ?: return@guard null
            definition.buildBukkitItem()
        }
    }

    /**
     * 给玩家播放一次 CraftEngine 图腾动画。
     *
     * 来源：CraftEngine 26.7.4 自带 `totem_animation` 命令的公开调用链：
     * `BukkitAdaptor.adapt` → `BukkitItemDefinition.buildItem` → `BukkitServerPlayer.sendTotemAnimation`。
     * 1.21.2+ 还要临时补 `DEATH_PROTECTION` 组件，做法与 CE 自带命令一致；只改本次发送的物品副本。
     */
    fun playTotemAnimation(player: Player, itemId: String): Boolean {
        if (itemId.isBlank()) {
            return false
        }
        if (!isAvailable) {
            warning("CraftEngine 图腾动画不可用：CE 未就绪，player=${player.name}，item=$itemId")
            return false
        }
        val resolvedId = normalizeTotemItemId(itemId)
        return guard("播放图腾动画 $resolvedId") {
            val cePlayer = net.momirealms.craftengine.bukkit.api.BukkitAdaptor.adapt(player)
                ?: run {
                    warning("CraftEngine 图腾动画失败：玩家尚未适配为 CE 在线用户，player=${player.name}")
                    return@guard false
                }
            val definition = net.momirealms.craftengine.bukkit.api.CraftEngineItems.byId(resolvedId)
                ?: run {
                    warning(
                        "CraftEngine 图腾动画失败：物品未注册，配置值=$itemId，查询值=$resolvedId；" +
                            "请确认 CE 已完成重载且物品 id 存在",
                    )
                    return@guard false
                }
            val item = definition.buildItem(cePlayer)
            if (net.momirealms.craftengine.core.util.VersionHelper.isOrAbove1_21_2) {
                item.setJavaComponent(
                    net.momirealms.craftengine.bukkit.item.DataComponentTypes.DEATH_PROTECTION,
                    emptyMap<Any, Any>(),
                )
            }
            // 终局已有独立 game-over 音效，这里只发一个物品的图腾画面，避免声音叠播。
            cePlayer.sendTotemAnimation(item, null, true)
            true
        } ?: false
    }

    /** 兼容早期错误配置中的棋子 ID，资源生成 ID 以 PackSpec 为准。 */
    private fun normalizeTotemItemId(itemId: String): String = when (itemId.trim()) {
        "muchess:xiangqi_red_king" -> "muchess:red_king"
        "muchess:xiangqi_black_king" -> "muchess:black_king"
        else -> itemId.trim()
    }

    /**
     * 在 origin 对应的四格交界 pivot 放一个完整 2x2 棋盘家具。
     *
     * CE 的 ground 放置会把家具 base yaw 设为玩家 yaw + 180；资源里的 item_display
     * 再本地旋转 180 抵消。因此 API 放置时也传 [BoardFacing.furnitureYaw]，最终视觉
     * 朝向与 [BoardFacing.yaw] 一致。
     *
     * @param furnitureId 要放的棋盘家具 id（各棋种一个），默认象棋盘
     * @return 家具 base entity UUID 字符串
     */
    fun placeBoardFurniture(
        origin: Location,
        facing: BoardFacing,
        furnitureId: String = BOARD_ID,
    ): String? {
        if (!isAvailable) {
            return null
        }
        val world = origin.world ?: return null
        return guard("放置棋盘家具") {
            val (pivotX, pivotY, pivotZ) = BoardFootprint.pivotOf(
                origin.blockX,
                origin.blockY,
                origin.blockZ,
            )
            val pivot = Location(
                world,
                pivotX.toDouble(),
                pivotY.toDouble(),
                pivotZ.toDouble(),
                facing.furnitureYaw,
                0f,
            )
            val key = net.momirealms.craftengine.core.util.Key.of("$NAMESPACE:$furnitureId")
            val furniture = net.momirealms.craftengine.bukkit.api.CraftEngineFurniture.place(pivot, key)
                ?: return@guard null
            furniture.bukkitEntity().uniqueId.toString()
        }
    }

    /** 按 base entity UUID 移除整副棋盘家具，不触发 CE 默认掉落 */
    fun removeBoardFurniture(handle: String): Boolean {
        if (!isAvailable) {
            return false
        }
        val uuid = runCatching { UUID.fromString(handle) }.getOrNull() ?: return false
        val entity = Bukkit.getEntity(uuid) ?: return false
        return guard("移除棋盘家具") {
            net.momirealms.craftengine.bukkit.api.CraftEngineFurniture.remove(entity, false, false)
        } ?: false
    }

    /**
     * 这个句柄对应的棋盘家具是否还活在世界里。
     *
     * 用途是识别"会话还在、家具已经没了"这种错位状态：家具被 `/kill`、被别的插件清场、
     * 或 CE 重载时掉了注册，会话的 `visualHandle` 仍然非 null，于是棋子与悬浮字照常渲染，
     * 底下却空无一物，而且没有家具可拆就永远触发不了 `FurnitureBreakEvent`，
     * 会话再也收不掉。
     *
     * **调用约束：只能在家具所在区块已加载时调。** 区块没加载时 `Bukkit.getEntity`
     * 对活着的实体同样返回 null，那时判"已死"会让调用方补建出第二副棋盘。
     * 所以恢复流程（`WorldLoadEvent`，区块通常还没加载）不能用它，
     * 只有区块加载后的重挂路径可以。
     *
     * CE 不可用时返回 `true` 而不是 false：降级模式下本来就没有家具，
     * 返回 false 会让调用方反复尝试补建一个永远建不出来的东西。
     *
     * @param handle 家具 base entity 的 UUID 字符串
     * @return 家具还在返回 true；句柄损坏、实体不在、或实体已不是 CE 家具返回 false
     */
    fun isBoardFurnitureAlive(handle: String): Boolean {
        if (!isAvailable) {
            return true
        }
        val uuid = runCatching { UUID.fromString(handle) }.getOrNull() ?: return false
        val entity = Bukkit.getEntity(uuid) ?: return false
        return guard("判断棋盘家具是否存活") {
            // 先按 base entity 反解家具，解不出再退一步只问"是不是家具实体"：
            // CE 注册表在重载窗口内可能短暂查不到，但实体确实还在，
            // 这时按"活着"处理更安全 —— 误判已死会多放一副棋盘，误判活着只是这次不补。
            val furniture = net.momirealms.craftengine.bukkit.api.CraftEngineFurniture
                .getLoadedFurnitureByMetaEntity(entity)
            if (furniture != null) {
                return@guard true
            }
            net.momirealms.craftengine.bukkit.api.CraftEngineFurniture.isFurniture(entity)
        } ?: true
    }

    /**
     * 把已放下的棋盘家具挪到新位置。
     *
     * 必须走 CE 的 `Furniture.moveTo`，不能直接 teleport base entity。
     * 家具的可见外观（`elements`）不是真实体，而是 CE 按放置时的 base 位置算好
     * **绝对坐标**后发给客户端的伪实体；base 挪走它们不会跟着动。之前这里用
     * `entity.teleport` 只搬动了 base 与 hitbox，棋盘那块薄板还留在原来的高度 ——
     * 棋子按修正后的 anchor 渲染，于是出现"棋盘悬在半空、棋子贴在地面"。
     *
     * `moveTo` 会依次 invalidate 家具、清掉 collider 与座位、隐藏 hitbox、
     * teleport base，再按新位置重建 elements 并重新 init，外观才真的跟着落下。
     *
     * 返回的是 `CompletableFuture`：Paper 上内部走 `teleportAsync`，主线程 `join`
     * 会等一个只能由主线程完成的 future，直接死锁。所以这里只发起请求，失败在回调里记日志。
     *
     * @return 是否成功发起移动；实体没加载、CE 不可用或家具已在移动中返回 false
     */
    fun moveBoardFurniture(handle: String, target: Location): Boolean {
        if (!isAvailable) {
            return false
        }
        val uuid = runCatching { UUID.fromString(handle) }.getOrNull() ?: return false
        val entity = Bukkit.getEntity(uuid) ?: return false
        return guard("移动棋盘家具") {
            val furniture = net.momirealms.craftengine.bukkit.api.CraftEngineFurniture
                .getLoadedFurnitureByMetaEntity(entity)
            if (furniture == null) {
                // 注册表里查不到家具（CE 还没认领这只 base）时退回裸 teleport：
                // 位置至少是对的，外观下一次区块重载会重建。
                warning("棋盘家具 $handle 不在 CE 注册表内，退回直接 teleport，外观可能滞留原高度")
                return@guard entity.teleport(target)
            }
            val position = net.momirealms.craftengine.bukkit.util.LocationUtils.toWorldPosition(target)
            furniture.moveTo(position).whenComplete { moved, error ->
                if (error != null) {
                    warning("棋盘家具 $handle 移动失败：${error.message}")
                } else if (moved != true) {
                    warning("棋盘家具 $handle 移动被 CE 拒绝，可能是目标位置有碰撞")
                }
            }
            true
        } ?: false
    }

    /**
     * 事件携带的家具是不是 MuChess 的棋盘，是则返回不带命名空间的家具 id。
     *
     * 桥接层只认"这是不是本插件命名空间下的棋盘家具"，**不判断是哪个棋种** ——
     * 家具 id 到棋种的映射属于业务侧（`GameType.byFurnitureId`），
     * 桥接层不做棋类概念上的推理。
     */
    fun boardFurnitureId(value: Any): String? {
        val id = furniture(value)?.id()?.toString() ?: return null
        val prefix = "$NAMESPACE:"
        if (!id.startsWith(prefix)) return null
        val local = id.removePrefix(prefix)
        return if (local in BOARD_IDS) local else null
    }

    /** 事件携带的家具是不是 MuChess 的棋盘（任意棋种） */
    fun isBoardFurniture(value: Any): Boolean = boardFurnitureId(value) != null

    /** 取事件家具的 base entity UUID 句柄 */
    fun boardFurnitureHandle(value: Any): String? {
        val furniture = furniture(value) ?: return null
        if (boardFurnitureId(furniture) == null) {
            return null
        }
        return furniture.bukkitEntity().uniqueId.toString()
    }

    /** 取事件家具的 base location；返回 clone，调用方可安全修改 */
    fun boardFurnitureLocation(value: Any): Location? {
        val furniture = furniture(value) ?: return null
        if (boardFurnitureId(furniture) == null) {
            return null
        }
        return furniture.location().clone()
    }

    /** Any → BukkitFurniture，避免把 CE 类型暴露到桥接层外的方法签名 */
    private fun furniture(value: Any): net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture? =
        value as? net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture

    /** 让 CraftEngine 重新加载资源与注册表 */
    fun reload(): Boolean {
        if (!isAvailable) {
            return false
        }
        return guard("触发 CE 重载") {
            val engine = net.momirealms.craftengine.core.plugin.CraftEngine.instance()
            if (engine.isReloading) {
                info("CraftEngine 正在重载，跳过这次触发")
                return@guard false
            }
            // CE 签名顺序是（异步执行器，同步执行器，是否重生成资源包）。
            // 第二个执行器会触发只允许主线程调用的 CraftEngineReloadEvent。
            val syncExecutor = java.util.concurrent.Executor { task -> submit { task.run() } }
            val asyncExecutor = java.util.concurrent.Executor { task -> submitAsync { task.run() } }
            engine.reloadPlugin(asyncExecutor, syncExecutor, true)
            true
        } ?: false
    }

    /** 判断实体是否属于任意 CE 家具 */
    fun isFurniture(entity: Entity): Boolean {
        if (!isAvailable) {
            return false
        }
        return guard("判断家具") {
            net.momirealms.craftengine.bukkit.api.CraftEngineFurniture.isFurniture(entity)
        } ?: false
    }

    /**
     * 扫描指定方块坐标列表是否被 CE 家具阻挡。
     *
     * CE 家具是实体（base ItemDisplay + hitbox Interaction + 可选 collider），不占方块，
     * 不会被 `canPlace` 的方块检查发现。本方法用 `getNearbyEntities` 扫区域内实体，
     * 过滤 CE 家具相关实体（isFurniture 或 isCollisionEntity 或能反解出所属家具），
     * 返回被阻挡格子的 Location 列表，供粒子标记用。
     *
     * 自我误判防护：`FurniturePlaceEvent` 触发时家具已创建，扫描会命中自己刚放的那副棋盘。
     * 调用者必须传 `excludeHandle`（base entity UUID），本方法会排除该家具的全部实体。
     *
     * @param world 待查世界
     * @param blocks 方块坐标列表，每个元素是 Triple(x, y, z)
     * @param excludeHandle 要排除的家具 base entity UUID 字符串，null 表示不排除
     * @return 被 CE 家具阻挡的方块 Location 列表；CE 不可用或无阻挡时返回空列表
     */
    fun furnitureBlocking(
        world: World,
        blocks: List<Triple<Int, Int, Int>>,
        excludeHandle: String?,
    ): List<Location> {
        if (!isAvailable) {
            return emptyList()
        }
        return guard("扫描家具阻挡") {
            val blockedLocations = mutableListOf<Location>()
            val excludeUUID = excludeHandle?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }

            for ((x, y, z) in blocks) {
                // ItemDisplay 的 bounding box 是零尺寸，单靠 box 相交可能漏，
                // 所以在 Y 上下各留 0.5 格余量，并对返回实体额外按方块坐标复核。
                // BoundingBox.of(Vector/Location, halfX, halfY, halfZ) 是中心+半轴版本，
                // 不适合直接用最小角/最大角；改用 6 参数构造器 BoundingBox(x1,y1,z1, x2,y2,z2)。
                val box = org.bukkit.util.BoundingBox(
                    x.toDouble(), y - 0.5, z.toDouble(),
                    (x + 1).toDouble(), y + 1.5, (z + 1).toDouble(),
                )
                val nearby = world.getNearbyEntities(box)

                for (entity in nearby) {
                    // 复核实体位置确实在当前方块内（Y 允许下方承托层，因 blocksWithSupport 包含 Y-1）
                    val loc = entity.location
                    val blockX = loc.blockX
                    val blockY = loc.blockY
                    val blockZ = loc.blockZ
                    if (blockX != x || blockZ != z || (blockY != y && blockY != y - 1)) {
                        continue
                    }

                    // 判断是否 CE 家具相关实体，静态方法直接用完整类名调
                    val furnitureByMeta = net.momirealms.craftengine.bukkit.api.CraftEngineFurniture
                        .getLoadedFurnitureByMetaEntity(entity)
                    val isCollider = net.momirealms.craftengine.bukkit.api.CraftEngineFurniture
                        .isCollisionEntity(entity)
                    val furnitureByCollider = if (isCollider) {
                        net.momirealms.craftengine.bukkit.api.CraftEngineFurniture
                            .getLoadedFurnitureByCollider(entity)
                    } else null
                    val isCEFurniture = net.momirealms.craftengine.bukkit.api.CraftEngineFurniture
                        .isFurniture(entity)

                    val owningFurniture = when {
                        furnitureByMeta != null -> furnitureByMeta
                        furnitureByCollider != null -> furnitureByCollider
                        isCEFurniture -> furniture(entity)
                        else -> null
                    }

                    if (owningFurniture == null) {
                        continue
                    }

                    // 排除 excludeHandle 对应的家具（自我误判防护）。
                    //
                    // 用 bukkitEntity() 而不是 baseEntity()：后者在 CraftEngine 26.7.4 已标
                    // @Deprecated（javap 核对过，同类里只有它带这个标记，bukkitEntity() 与
                    // getBukkitEntity() 都不带），两者返回的都是家具的 base 实体。
                    val baseUUID = owningFurniture.bukkitEntity()?.uniqueId
                    if (baseUUID != null && baseUUID == excludeUUID) {
                        continue
                    }

                    // 确认阻挡，记录该方块 Location
                    blockedLocations.add(Location(world, x.toDouble(), y.toDouble(), z.toDouble()))
                    break // 该方块已确认阻挡，不用继续检查其他实体
                }
            }

            blockedLocations.distinct()
        } ?: emptyList()
    }

    /** 统一 CE 调用包裹：版本差异与异常都降级为 null，并保留日志 */
    private inline fun <T> guard(action: String, block: () -> T?): T? = try {
        block()
    } catch (error: Throwable) {
        warning("CraftEngine 调用失败（$action）：${error.javaClass.simpleName} ${error.message}")
        null
    }

    /** 首次可用时打一条日志，方便服主确认 hook 上了 */
    fun logStatus() {
        if (isAvailable) {
            val version = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME)?.pluginMeta?.version ?: "未知"
            info("已挂上 CraftEngine（版本 $version），棋盘与棋子外观可用")
        } else {
            info("没有可用的 CraftEngine，棋盘与棋子外观降级，规则与对局照常")
        }
    }

    /** 资源命名空间，与构建期 PackSpec.NAMESPACE 一致 */
    const val NAMESPACE = "muchess"

    /** 象棋棋盘家具 id，与构建期 PackSpec.BOARD_IDS 一致 */
    const val BOARD_ID = "xiangqi_board"

    /**
     * 全部棋盘家具 id。
     *
     * 与构建期 `PackSpec.BOARD_IDS` 一一对应，也与 `GameType.furnitureId` 一致。
     * 桥接层只用它判断"这个家具是不是棋盘"，不关心是哪个棋种。
     *
     * 新增棋种时必须同时补这里。漏了的表现极难查：资源包照常生成出家具、玩家拿得到物品、
     * 放下去家具也确实出现，但 `boardFurnitureId` 认不出它而返回 null，
     * `onPlace` 于是静默 return —— 没有会话、没有警告、没有任何提示，
     * 看起来就是"这个棋种不能玩"。围棋曾经就漏在这里。
     *
     * 这里刻意不写成 `GameType.entries.map { it.furnitureId }`：桥接层不认棋种，
     * 家具 id 到棋种的映射属于业务侧（见 [boardFurnitureId] 的说明）。
     * 两侧一致由 `BoardFurnitureIdsTest` 钉住，漏补会直接测试失败。
     */
    val BOARD_IDS: Set<String> = setOf(BOARD_ID, "gomoku_board", "go_board")
}
