package linmumua.chess.game

import java.util.UUID
import linmumua.chess.board.BoardAnchor
import linmumua.chess.board.BoardFacing
import linmumua.chess.board.BoardFootprint
import linmumua.chess.board.BoardGaze
import linmumua.chess.config.ChessConfig
import linmumua.chess.config.ChessSettings
import linmumua.chess.game.bot.BotBrain
import linmumua.chess.game.request.GameRequests
import linmumua.chess.interact.SelectionRegistry
import linmumua.chess.render.BoardLabelRenderer
import linmumua.chess.render.BoardVisualHandle
import linmumua.chess.render.BoardVisualRegistry
import linmumua.chess.render.DisplayMarker
import linmumua.chess.render.OrphanDecision
import linmumua.chess.rule.core.Square
import linmumua.chess.rule.xiangqi.XiangqiRuleOptions
import linmumua.chess.rule.core.GameRules
import linmumua.chess.rule.go.GoRuleOptions
import linmumua.chess.rule.gomoku.GomokuRuleOptions
import linmumua.chess.rule.xiangqi.XiangqiRules
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.util.Vector
import taboolib.common.platform.function.info
import taboolib.common.platform.function.warning

/**
 * 所有已放置棋盘与对局的登记处。
 *
 * 负责放置、拆除、查找、恢复、落盘编排。只在主线程访问会话表，
 * 落盘的文件写入交给 [BoardStorage] 走异步。
 *
 * 这个类不碰渲染细节也不发消息：放完棋盘之后由命令层给玩家回话，
 * 棋子怎么摆是 [GameSession.renderer] 的事。
 */
object GameManager {

    /** 棋盘 id → 会话 */
    private val sessions = LinkedHashMap<UUID, GameSession>()

    /** 全服选中态 */
    val selections = SelectionRegistry()

    /**
     * 象棋规则可调项，按配置的限着回合数构造。
     *
     * 规则实例本身不再全局共享 —— 一台服务器上可以同时摆着象棋盘和五子棋盘，
     * 每副棋盘的对局各自持有自己棋种的规则（见 [GameType.createRules]）。
     * 这里只留"建规则时该用什么可调项"。
     */
    @Volatile
    var xiangqiOptions: XiangqiRuleOptions = XiangqiRuleOptions.DEFAULT
        private set

    /** 五子棋规则可调项 */
    @Volatile
    var gomokuOptions: GomokuRuleOptions = GomokuRuleOptions.DEFAULT
        private set

    /** 围棋规则可调项 */
    @Volatile
    var goOptions: GoRuleOptions = GoRuleOptions.DEFAULT
        private set

    /** 会话数量 */
    val sessionCount: Int get() = sessions.size

    /** 是否一个棋盘都没有，供调度任务停摆判断 */
    fun isEmpty(): Boolean = sessions.isEmpty()

    /** 全部会话的只读视图 */
    fun allSessions(): List<GameSession> = sessions.values.toList()

    /** 按 id 取会话 */
    fun session(id: UUID): GameSession? = sessions[id]

    /**
     * 按配置重建规则门面。
     *
     * draw-move-limit 配 0 表示关掉限着判和。配置重载后要调一次，
     * 否则改了限着回合数对已有对局不生效。
     */
    fun applySettings() {
        val settings = ChessConfig.settings
        val limit = settings.game.drawMoveLimit
        xiangqiOptions = if (limit <= 0) {
            XiangqiRuleOptions(moveLimitDrawEnabled = false)
        } else {
            XiangqiRuleOptions(moveLimitDrawEnabled = true, moveLimitFullMoves = limit)
        }
        gomokuOptions = GomokuRuleOptions(
            forbiddenMovesEnabled = settings.gomoku.forbiddenMovesEnabled,
        )
        goOptions = GoRuleOptions(komi = settings.go.komi)
        // 围棋机器人的候选上限走围棋段的独立配置，重载时要同步过去，
        // 否则改了配置对机器人不生效
        BotBrain.goMaxCandidates = settings.go.botMaxCandidates
        sessions.values.forEach { session ->
            // 合法落点与 marker 尺寸都来自配置；重载时清掉旧缓存和旧显示，避免过期着法仍可点击。
            selections.clearBoard(session.id)
            session.clearSelectionVisuals()
            session.updateSettings(settings)
            // 悬浮字不走 updateSettings（它现读 ChessConfig），但高度、缩放、判定框尺寸都是
            // spawn 时写进实体的，光换配置不重建实体等于没生效。这里刷一次让渲染器自己比对
            // 新旧配置决定要不要重建，同时把 board-label.enabled 改成 false 的牌子清掉。
            // 少了这行，一副静置的 WAITING 棋盘要等到有人入座或离座才套用新配置，
            // 而关掉开关的牌子会一直留在世界里。
            session.refreshLabel()
        }
    }

    /**
     * 巡检未开局棋盘的悬浮字，把被外力删掉的牌子补回来。
     *
     * 为什么需要周期巡检：`refreshLabel()` 的那些调用点**全部**要状态变化才触发
     * （放置、入座、离座、加撤机器人、开局、走子、终局、区块重载）。服主执行
     * `/kill @e[type=text_display]` 或 `/kill @e[type=interaction]` 清场之后，
     * 一副 WAITING 棋盘的牌子就没了，而且没有任何事件会让它回来 —— 一副满座待开局的
     * 棋盘尤其糟，此时唯一能触发刷新的动作是有人离座，等于这副棋盘再也点不动。
     *
     * 为什么只巡检 WAITING，而 PLAYING 与 FINISHED 也有牌子却不管：
     * - [GamePhase.PLAYING] 有 `MoveExecutor.afterMove` 每走一手刷一次，牌子被清掉之后
     *   最多缺一手就自己回来，不值得再花一轮遍历。
     * - [GamePhase.FINISHED] 是有意接受的取舍：终局之后不再有走子事件，牌子被外力删掉就
     *   真的不回来了，要等区块重新加载（走 [resyncSessionsIn]）。终局牌子只是结果告示，
     *   点不动也不影响任何人继续玩，为它每轮扫一遍所有终局棋盘不合算。
     *
     * 为什么这不算空转（项目要求没有活跃对局时任务必须自行停摆）：
     * - 只看 [GamePhase.WAITING] 的会话，一副棋盘都没在等人时这里一次实体操作都不做。
     * - 开关关掉时直接返回，连遍历都不做。
     * - `BoardLabelRenderer.refresh` 本身幂等：牌子活着且状态没变就在两次 `isValid`
     *   检查之后短路返回，不重发文本、不碰实体。所以正常情况下每副等人的棋盘每轮只花
     *   几次字段读取。
     *
     * 跳过未加载区块是必须的，不是优化：`World.spawn` 会把目标区块强行拉起来，
     * 不跳过的话一副没人在旁边的 WAITING 棋盘会被每轮巡检反复触发区块加载，
     * 那才是真正的空转。那种棋盘的牌子由 [resyncSessionsIn] 在区块重新加载时补。
     *
     * 必须在主线程调用：会碰实体。
     */
    fun refreshWaitingLabels() {
        if (!ChessConfig.settings.boardLabel.enabled) {
            return
        }
        for (session in sessions.values) {
            if (session.game.phase != GamePhase.WAITING) {
                continue
            }
            if (!isChunkLoaded(session)) {
                continue
            }
            session.refreshLabel()
        }
    }

    /**
     * 棋盘中心所在区块是否已加载。
     *
     * 用中心点而不是 2x2 的四角：棋盘家具的 base entity 与悬浮字都落在中心，
     * 与 [resyncSessionsIn] 认的是同一个区块，两处判断保持一致。
     */
    private fun isChunkLoaded(session: GameSession): Boolean {
        val center = session.anchor.centerLocation()
        return session.anchor.world.isChunkLoaded(center.blockX shr 4, center.blockZ shr 4)
    }

    /** 某世界已有的棋盘数，用于上限检查 */
    fun countInWorld(worldName: String): Int =
        sessions.values.count { it.anchor.world.name == worldName }

    /**
     * 由已经放下的 CE 棋盘家具建棋局。
     *
     * 家具已经存在，所以这里只认领 base entity UUID，不再调用 `visual.place`。
     * 家具中心 pivot、2x2 最小角与朝向全部从实际家具 location 反算，避免信任
     * 玩家视线造成模型和格点方向不一致。
     */
    fun placeFromFurniture(
        furnitureLocation: Location,
        handle: BoardVisualHandle,
        gameType: GameType = GameType.XIANGQI,
    ): PlaceResult {
        val settings = ChessConfig.settings
        val world = furnitureLocation.world ?: return PlaceResult.NoSpace
        val anchor = BoardPlacement.anchorForFurniture(furnitureLocation, settings.board, gameType)

        validate(world, anchor, gameType, handle.id)?.let { return it }

        // 手动放置家具走的是这条路径，同样要顶掉草，否则棋盘悬在草尖上
        clearReplaceable(world, anchor, settings, gameType)

        val session = GameSession(
            id = UUID.randomUUID(),
            anchor = anchor,
            game = ChessGame(gameType.createRules()),
            settings = settings,
            gameType = gameType,
        )
        session.visualHandle = handle
        // anchor 的 Y 可能被下修过（CE 把家具放在草层上方时要落回实体方块顶面），
        // 家具实体得跟着挪，否则棋盘留在原高度、棋子按修正后的高度渲染，两者错开一格。
        alignFurnitureToAnchor(handle, anchor, furnitureLocation)
        // 这里**不摆棋子**：刚放下的棋盘是空盘，棋子等到有人点"开始"才铺
        // （见 ChessCommandHandler.start）。会话建好但盘上空着是正常状态 ——
        // 走子入口 ChessGame.move 在 WAITING 阶段一律拒，不存在"没棋子却能走"的路径。
        sessions[session.id] = session
        // 空盘上方挂"加入对局"的牌子，这是玩家入座的主入口（board-label.enabled
        // 关掉时退到 shift+右键的箱子菜单），放下棋盘就得有
        session.refreshLabel()
        markDirty()
        return PlaceResult.Success(session)
    }

    /**
     * 按显式 origin 建棋盘，供 `/chess debug test` 压测用。
     *
     * 与 [placeFromFurniture] 的区别是**放置顺序反过来**：那边家具已经由玩家放好、
     * 这里要先算好位置再自己去放。所以先校验再放家具 —— 反过来的话校验失败就得
     * 回头删掉刚放的家具，多一条容易漏的清理路径。
     *
     * CE 的 `CraftEngineFurniture.place(location, key)` 不会触发 `FurniturePlaceEvent`
     * （那个事件继承 `PlayerEvent`，构造器强制要 Player），所以这里放家具**不会**被
     * [linmumua.chess.compat.CraftEnginePlaceListener] 再认领一次，不存在重复建会话。
     *
     * CE 不可用时 [BoardVisualHandle] 为 null，会话照样建 —— 与 [NoopBoardVisual] 的
     * 降级约定一致：看不见棋盘但坐标、规则、棋子渲染都正常，压测数据仍然有意义。
     *
     * @param origin 占位区域最小角的方块坐标
     * @param facing 棋盘朝向
     * @return 校验失败返回对应原因，成功返回新建会话
     */
    fun placeForDebug(
        world: World,
        origin: Vector,
        facing: BoardFacing,
        gameType: GameType,
    ): PlaceResult {
        val settings = ChessConfig.settings
        val anchor = BoardPlacement.anchorAt(world, origin, facing, settings.board, gameType)

        // 先校验后放家具：此时世界里还没有本副棋盘的实体，不需要排除自身
        validate(world, anchor, gameType, excludeHandle = null)?.let { return it }

        clearReplaceable(world, anchor, settings, gameType)

        val session = GameSession(
            id = UUID.randomUUID(),
            anchor = anchor,
            game = ChessGame(gameType.createRules()),
            settings = settings,
            gameType = gameType,
        )
        // 家具放在校验通过之后。CE 缺失时 place 返回 null，句柄留空即可。
        session.visualHandle = BoardVisualRegistry.current.place(
            anchor.origin.toLocation(world),
            facing,
            gameType.furnitureId,
        )
        sessions[session.id] = session
        session.refreshLabel()
        markDirty()
        return PlaceResult.Success(session)
    }

    /**
     * 把已放好的家具实体挪到 anchor 修正后的高度。
     *
     * 只在高度确实变了时动，避免每次放置都白做一次 teleport。X/Z 不动 ——
     * 那两轴由 CE 的 corner 对齐决定，anchor 是照抄的，改反而会错位。
     */
    private fun alignFurnitureToAnchor(
        handle: BoardVisualHandle,
        anchor: BoardAnchor,
        placed: Location,
    ) {
        val targetY = anchor.origin.y
        if (ChessConfig.settings.debug) {
            // 排查"棋盘浮空"必须能看到这三个高度：CE 落点、修正目标、棋子面。
            // 三者对不上时，差值直接说明是落点算高了还是外观没跟着挪。
            info(
                "棋盘高度校正：CE 落点 Y=${placed.y}，修正目标 Y=$targetY，" +
                    "棋子面 Y=${anchor.surfaceY}，差值 ${targetY - placed.y}",
            )
        }
        if (Math.abs(placed.y - targetY) < 1.0e-6) {
            return
        }
        val moved = BoardVisualRegistry.current.moveTo(
            handle,
            Location(placed.world, placed.x, targetY, placed.z, placed.yaw, placed.pitch),
        )
        if (!moved) {
            warning(
                "棋盘家具高度校正失败，句柄 ${handle.id}：" +
                    "期望 Y=$targetY，实际留在 ${placed.y}，棋盘可能与棋子错开一格",
            )
        }
    }

    /**
     * 顶掉 2x2 占位范围内的草等可替换方块。
     *
     * 必须在校验通过之后、家具高度校正之前做：清早了校验失败会白毁地表，清晚了棋盘
     * 已经按草尖高度落位。清除数量写调试日志，方便排查"棋盘还是浮着"这类反馈。
     */
    private fun clearReplaceable(
        world: World,
        anchor: BoardAnchor,
        settings: ChessSettings,
        gameType: GameType,
    ) {
        val cleared = BoardPlacement.clearReplaceable(
            world,
            anchor.origin,
            settings.board,
            gameType.footprintSize,
        )
        // 调试模式才记录，正常放置不刷控制台；沿用 BoardInteractListener 的写法
        if (cleared > 0 && ChessConfig.settings.debug) {
            info("放置棋盘时顶掉了 $cleared 个可替换方块（草、花之类）")
        }
    }

    /**
     * 放置前的共用校验：世界上限、2x2 占位、棋盘重叠、CE 家具阻挡。
     *
     * 抽出来给 [placeFromFurniture] 共用。家具是实体，不会先占住某个 Bukkit 方块，
     * 因此方块检查之外还必须单独扫 CE 家具。
     *
     * @param excludeHandle base entity UUID 字符串，用于排除自身家具（防自检误判）；
     *   只有 [placeFromFurniture] 走 CE 放置事件时才有值，其他路径传 null
     * @return 校验失败的原因，全部通过返回 null
     */
    private fun validate(
        world: World,
        anchor: BoardAnchor,
        gameType: GameType,
        excludeHandle: String? = null,
    ): PlaceResult? {
        val settings = ChessConfig.settings
        val size = gameType.footprintSize
        if (countInWorld(world.name) >= settings.board.maxPerWorld) {
            return PlaceResult.LimitReached(settings.board.maxPerWorld)
        }
        if (!BoardPlacement.canPlace(world, anchor.origin, settings.board, size)) {
            return PlaceResult.NoSpace
        }
        // 棋盘重叠：两副棋盘的占位有交集就算重叠，不要求 origin 精确相同。
        // 旧的"origin 精确相等"只能防完全同格，错开 1 格但共享方块的情况会漏。
        // 两副棋盘的边长可能不同（3x3 围棋盘挨着 2x2 象棋盘），所以各传各自的尺寸。
        val occupied = sessions.values.any { existing ->
            existing.anchor.world == world &&
                BoardFootprint.overlaps(
                    anchor.origin.blockX, anchor.origin.blockY, anchor.origin.blockZ,
                    existing.anchor.origin.blockX, existing.anchor.origin.blockY, existing.anchor.origin.blockZ,
                    sizeA = size,
                    sizeB = existing.gameType.footprintSize,
                )
        }
        if (occupied) {
            return PlaceResult.NoSpace
        }
        // CE 家具阻挡：家具是实体，canPlace 的方块检查看不见。
        // 同层重合会导致 hitbox 串道；承托层是家具时 groundLevel 会穿过去错层。
        val blocked = BoardPlacement.blockingFurniture(world, anchor.origin, excludeHandle, size)
        if (blocked.isNotEmpty()) {
            return PlaceResult.FurnitureOverlap(blocked)
        }
        return null
    }

    /**
     * 按位置找棋盘，用于家具被挖时定位对应棋局。
     *
     * 与 [nearestSession] 不同，这里要求 origin 方块坐标**精确相等** ——
     * 挖掉一个家具只能收掉它自己那副棋盘，不能顺带收掉旁边最近的那副。
     */
    fun sessionAtOrigin(world: World, blockX: Int, blockY: Int, blockZ: Int): GameSession? =
        sessions.values.firstOrNull { session ->
            session.anchor.world == world &&
                session.anchor.origin.blockX == blockX &&
                session.anchor.origin.blockY == blockY &&
                session.anchor.origin.blockZ == blockZ
        }

    /**
     * 取实际覆盖某个方块的棋盘。
     *
     * 原版交互兜底打开菜单时必须命中 2x2 占位本身，不能像普通走棋那样只找附近棋盘，
     * 否则玩家潜行右键旁边的方块也会误开菜单。
     */
    fun sessionCovering(location: Location): GameSession? {
        val world = location.world ?: return null
        val blockX = location.blockX
        val blockY = location.blockY
        val blockZ = location.blockZ
        return sessions.values.firstOrNull { session ->
            val origin = session.anchor.origin
            session.anchor.world == world && BoardFootprint.containsBlock(
                origin.blockX,
                origin.blockY,
                origin.blockZ,
                blockX,
                blockY,
                blockZ,
                // 按该会话自己的棋种边长判：围棋 3x3 用默认的 2 会漏掉最外一圈，
                // 表现是潜行右键围棋盘边缘打不开菜单
                session.gameType.footprintSize,
            )
        }
    }

    /** 按家具 base entity UUID 句柄精确找会话 */
    fun sessionByVisualHandle(handle: String): GameSession? =
        sessions.values.firstOrNull { it.visualHandle?.id == handle }

    /**
     * 区块重新加载后补回其中棋盘的非持久化棋子实体。
     *
     * 棋子与白圈明确不写进区块存档，区块卸载时服务端会直接丢弃这些实体；
     * 渲染器内存索引却仍保留旧引用。等家具 base entity 所在区块重新加载后，
     * 清掉可能过期的选中态并按当前局面重铺，避免玩家回来只看到空棋盘。
     *
     * 还没开局的棋盘只清选中态、不补棋子：那副盘本来就该是空的，
     * 照局面重铺会让一副没人开始的棋盘在玩家走远再回来后凭空长出一副子。
     *
     * @return 本次重铺的棋盘数量
     */
    fun resyncSessionsIn(chunk: Chunk): Int {
        var resynced = 0
        for (session in sessions.values) {
            if (session.anchor.world != chunk.world) {
                continue
            }
            val center = session.anchor.centerLocation()
            if ((center.blockX shr 4) != chunk.x || (center.blockZ shr 4) != chunk.z) {
                continue
            }
            selections.clearBoard(session.id)
            session.clearSelectionVisuals()
            // 补悬浮字与棋子之前先确认棋盘家具真的还在。
            //
            // 家具被清场命令删掉、或 CE 重载时掉了注册之后，会话的 visualHandle 仍然非 null，
            // 下面两行会照常把牌子和棋子铺在一片空地上；更糟的是没有家具可拆就永远
            // 触发不了 FurnitureBreakEvent，这副会话再也收不掉，每次区块加载都重新长一遍。
            //
            // 这里是安全的判定时机：本方法跑在 ChunkLoadEvent 之后，而上面已经确认
            // 家具 base entity 所在区块正是当前这个 chunk，实体查得到。
            // 恢复流程（WorldLoadEvent）不能做这个判断，那时区块通常还没加载。
            ensureVisualAlive(session)
            // 悬浮字与棋子一样是非持久化实体，区块卸载时会被服务端丢掉，所以必须先补它。
            // 这一步要排在 hasStarted 判断**之前**：未开局的棋盘没有棋子要补，
            // 但正是它才需要那块"加入对局"的牌子 —— 漏了这行，玩家走远再回来
            // 会发现空棋盘上什么都没有，再也点不进这局。
            session.refreshLabel()
            if (!session.game.hasStarted()) {
                continue
            }
            session.renderer.resync(session.game.board)
            resynced++
        }
        return resynced
    }

    /**
     * 当前所有活会话在册的显示实体 UUID 全集。
     *
     * 清扫的判据基准，见 [OrphanDecision]。每次清扫现算一份而不是缓存：
     * 缓存会在走子、选中、终局之后过期，而过期的全集会让清扫误删正常棋子。
     */
    private fun liveEntityIds(): Set<UUID> {
        val result = HashSet<UUID>()
        for (session in sessions.values) {
            result += session.trackedEntityIds()
        }
        return result
    }

    /**
     * 清掉一批实体里无主的那些。
     *
     * 判据见 [OrphanDecision]：带本插件标记、但 UUID 不在任何活会话在册集合里，
     * 就是索引已经管不到的残留。没打标记的实体一律不碰 —— 那可能是别的插件的显示实体。
     *
     * @param candidates 待检查的实体
     * @param liveIds 活会话在册实体全集，由调用方一次算好复用
     * @return 各用途各删了多少
     */
    private fun sweepOrphans(
        candidates: Iterable<org.bukkit.entity.Entity>,
        liveIds: Set<UUID>,
    ): SweepResult {
        val byRole = HashMap<DisplayMarker.Role, Int>()
        var total = 0
        for (entity in candidates) {
            val owner = DisplayMarker.sessionIdOf(entity) ?: continue
            if (!OrphanDecision.isOrphan(entity.uniqueId, liveIds)) {
                continue
            }
            // 用途只用于报数，认不出用途也照删：标记在就说明是本插件生成的
            val role = DisplayMarker.roleOf(entity)
            if (role == DisplayMarker.Role.LABEL_HITBOX) {
                // 清扫绕开了渲染器实例（它可能已经不存在），所以反查表要在这里自己摘，
                // 否则表里留下一条指向已删实体的死记录
                BoardLabelRenderer.forgetHitbox(entity.uniqueId)
            }
            entity.remove()
            total++
            if (role != null) {
                byRole[role] = (byRole[role] ?: 0) + 1
            } else {
                // 总数独立于分类累加，否则用途认不出的实体会被删掉却不计数，
                // 命令就会在实际删了东西之后报"没找到残留"。owner 只用于日志定位。
                warning("清掉一只用途未知的残留显示实体，归属会话 $owner")
            }
        }
        return SweepResult(total, byRole)
    }

    /**
     * 清掉某个区块里的无主显示实体。
     *
     * 接在 [resyncSessionsIn] 之后：区块重载时顺手把这个区块内的残留收掉，
     * 服主不必手动跑命令。范围天然收敛到单个区块，开销由区块内实体数决定。
     *
     * **这不违反"禁止轮询扫实体找棋子"那条约束。** 那条约束管的是正常走棋路径 ——
     * 棋子与交叉点的对应关系必须由内存索引维护，不能靠扫世界去找。清扫是另一件事：
     * 孤儿按定义就不在索引里，索引找不到它们，扫描是唯一手段。而且它不在走棋路径上，
     * 只在区块加载与管理员命令时跑。
     *
     * @return 删掉的实体总数
     */
    fun sweepChunk(chunk: Chunk): Int {
        val result = sweepOrphans(chunk.entities.asIterable(), liveEntityIds())
        if (result.total > 0) {
            info(
                "区块 ${chunk.world.name} ${chunk.x},${chunk.z} 清掉 ${result.total} " +
                    "只无主显示实体：${result.byRole}",
            )
        }
        return result.total
    }

    /**
     * 以某个位置为中心，按区块半径清掉无主显示实体。
     *
     * 供管理员命令用。区块已加载、玩家不走远就不会触发 [sweepChunk]，
     * 所以必须有一条立即可用的路径。
     *
     * 范围必须收敛：实体操作只能在主线程，扫全世界已加载区块在大服上是几千个区块
     * 乘每区块实体数，一条命令能把主线程卡死。所以按半径开窗，并且**只看已加载区块**，
     * 绝不主动加载 —— 非持久化实体只可能存在于已加载区块里，加载新区块查不到任何东西。
     *
     * @param center 扫描中心
     * @param radiusChunks 半径，单位区块
     * @return 删了多少，含总数与各用途分项
     */
    fun sweepAround(center: Location, radiusChunks: Int): SweepResult {
        val world = center.world ?: return SweepResult.EMPTY
        val liveIds = liveEntityIds()
        val centerX = center.blockX shr 4
        val centerZ = center.blockZ shr 4
        val byRole = HashMap<DisplayMarker.Role, Int>()
        var total = 0
        for (x in (centerX - radiusChunks)..(centerX + radiusChunks)) {
            for (z in (centerZ - radiusChunks)..(centerZ + radiusChunks)) {
                if (!world.isChunkLoaded(x, z)) {
                    continue
                }
                val result = sweepOrphans(world.getChunkAt(x, z).entities.asIterable(), liveIds)
                total += result.total
                for ((role, count) in result.byRole) {
                    byRole[role] = (byRole[role] ?: 0) + count
                }
            }
        }
        return SweepResult(total, byRole)
    }

    /**
     * 清掉某个位置附近的无主显示实体，供拆棋盘路径兜底用。
     *
     * 家具已经在被拆了，而按句柄与按 origin 两条都没找到会话，说明这副棋盘的
     * 显示实体（如果有）已经脱离索引。就地扫一圈把它们收掉，
     * 否则家具消失之后再没有任何事件会经过这个位置。
     *
     * @return 删掉的实体总数
     */
    fun sweepAt(location: Location): Int =
        sweepAround(location, BREAK_SWEEP_RADIUS_CHUNKS).total

    /**
     * 找玩家附近的棋盘。
     *
     * 用于 `/chess remove`、`/chess leave` 这类不带坐标参数的命令。
     * 只在同世界里按棋盘中心的水平距离找最近的一副，超出 [SEARCH_RADIUS] 视为附近没有。
     */
    fun nearestSession(location: Location): GameSession? {
        val world = location.world ?: return null
        var best: GameSession? = null
        var bestDistance = Double.MAX_VALUE
        for (session in sessions.values) {
            if (session.anchor.world != world) {
                continue
            }
            val distance = session.anchor.centerLocation().distanceSquared(location)
            if (distance < bestDistance) {
                bestDistance = distance
                best = session
            }
        }
        return best?.takeIf { bestDistance <= SEARCH_RADIUS * SEARCH_RADIUS }
    }

    /**
     * 找玩家**正对着**的那副棋盘。
     *
     * 与 [nearestSession] 的区别是认视线而不是认距离。棋盘挨着摆时按距离会选错：
     * 站位偏一点就命中身后那副，而管理员要拆的是眼前这副 —— 拆错一副正在下的棋不可逆。
     *
     * 命中判定走 [BoardGaze]，对棋盘占位的包围盒求交。不用原版射线是因为棋盘那一格是空气
     * （本体是 CE 家具实体），`rayTraceBlocks` 打不到，详见那个类的说明。
     *
     * 多副棋盘同时在视线上时取**最近**的那副，与玩家的直觉一致。
     *
     * @param eye 视点，取 `player.eyeLocation`
     * @param maxDistance 最远认到多少格
     * @return 视线命中的最近棋盘，没有则 null
     */
    fun lookedSession(eye: Location, maxDistance: Double = SEARCH_RADIUS): GameSession? {
        val world = eye.world ?: return null
        val direction = eye.direction
        var best: GameSession? = null
        var bestDistance = Double.MAX_VALUE
        for (session in sessions.values) {
            if (session.anchor.world != world) {
                continue
            }
            val origin = session.anchor.origin
            val distance = BoardGaze.hitDistance(
                originX = origin.blockX,
                originY = origin.blockY,
                originZ = origin.blockZ,
                size = session.gameType.footprintSize,
                eyeX = eye.x,
                eyeY = eye.y,
                eyeZ = eye.z,
                dirX = direction.x,
                dirY = direction.y,
                dirZ = direction.z,
                maxDistance = maxDistance,
            ) ?: continue
            if (distance < bestDistance) {
                bestDistance = distance
                best = session
            }
        }
        return best
    }

    /**
     * 拆掉一副棋盘。
     *
     * 会清掉全部棋子实体与选中态。命令路径用默认值主动收家具；
     * `FurnitureBreakEvent` 已由 CE 自己进入删除流程时传 `removeVisual = false`，
     * 只释放句柄，避免对同一实体重复 remove。
     */
    fun remove(session: GameSession, removeVisual: Boolean = true) {
        selections.clearBoard(session.id)
        // 机器人索引按会话存，棋盘收走了必须一起清，否则会一直占着内存
        BotPlayer.clearSession(session.id)
        // 求和与悔棋请求同理：棋盘都收了，那条挂着的请求再没有对应的对局
        GameRequests.clear(session.id)
        session.close()
        val handle = session.releaseVisual()
        if (removeVisual && handle != null) {
            removeVisual(handle)
        }
        sessions.remove(session.id)
        markDirty()
    }

    /** 收掉棋盘家具外观，CE 不可用时跳过 */
    private fun removeVisual(handle: BoardVisualHandle) {
        val visual = BoardVisualRegistry.current
        if (!visual.isAvailable()) {
            return
        }
        runCatching { visual.remove(handle) }.onFailure {
            // 不允许吞异常：家具没收掉会在世界里留下一块拆不掉的棋盘
            warning("移除棋盘家具失败，句柄 ${handle.id}：${it.message}")
        }
    }

    /** 找玩家正在参与或观战的那副棋盘 */
    fun sessionOf(player: UUID): GameSession? =
        sessions.values.firstOrNull { it.game.isPlayer(player) || it.isSpectator(player) }

    /**
     * 找玩家**占着座位**的那副棋盘。
     *
     * 与 [sessionOf] 的差别是不把观战算进来。换桌前要查"是不是已经坐在别的桌上"，
     * 用 [sessionOf] 会把观战误判成占座：观战不占座位，一边观战一边到别处入座是允许的，
     * 拿 [sessionOf] 判会把玩家正在看的那局当成他的座位、退错桌。
     */
    fun seatedSessionOf(player: UUID): GameSession? =
        sessions.values.firstOrNull { it.game.isPlayer(player) }

    /** 关服清场：结束全部对局、清掉全部实体与选中态 */
    fun shutdown() {
        selections.clearAll()
        sessions.values.forEach { it.close() }
        sessions.clear()
    }

    /** 把某副棋盘导出成存档记录 */
    fun recordOf(session: GameSession): BoardRecord = BoardRecord(
        id = session.id,
        world = session.anchor.world.name,
        originX = session.anchor.origin.blockX,
        originY = session.anchor.origin.blockY,
        originZ = session.anchor.origin.blockZ,
        surfaceY = session.anchor.surfaceY,
        facing = session.anchor.facing,
        gameType = session.gameType,
        fen = session.game.rules.toFen(session.game.board),
        phase = session.game.phase,
        // 机器人不落盘：重启后座位上留着一个不存在的"玩家"会让棋局卡在
        // "已开局但没人能走"的状态，比没有机器人更糟。
        // 写 null 相当于那一方空着，重启后玩家可以正常入座。
        redPlayer = session.game.redPlayer?.takeUnless { BotPlayer.isBot(it) },
        blackPlayer = session.game.blackPlayer?.takeUnless { BotPlayer.isBot(it) },
        visualHandle = session.visualHandle?.id,
        bgmEnabled = session.bgmEnabled,
        turnTimeoutSeconds = session.game.turnTimeoutSeconds,
    )

    /** 导出全部棋盘，落盘用 */
    fun exportRecords(): List<BoardRecord> = sessions.values.map { recordOf(it) }

    /**
     * 从存档恢复一副棋盘。
     *
     * 必须在世界加载完成后调用，否则拿不到 World 对象。
     * 局面串坏掉时跳过这一副并记日志 —— 一副棋盘的数据错了不该拖垮其余的。
     */
    fun restore(record: BoardRecord, world: org.bukkit.World): GameSession? {
        val settings = ChessConfig.settings
        // 规则按存档里记的棋种建，不能用固定的象棋规则 ——
        // 五子棋的 FEN 是 15 行，拿象棋规则解会因行数不符直接抛异常。
        val rules = record.gameType.createRules()
        val board = runCatching { rules.fromFen(record.fen) }.getOrElse {
            warning("棋盘 ${record.id} 的局面串无法解析（棋种 ${record.gameType.id}），跳过恢复：${it.message}")
            return null
        }
        val anchor = BoardAnchor(
            world = world,
            origin = Vector(
                record.originX.toDouble(),
                record.originY.toDouble(),
                record.originZ.toDouble(),
            ),
            // 棋盘从半砖改成 0.125 格薄板后，旧 surfaceY 已失效；恢复时按当前模型重算。
            surfaceY = record.originY.toDouble() + BoardAnchor.BOARD_THICKNESS + settings.board.surfaceOffset,
            facing = record.facing,
            spec = record.gameType.spec,
            span = record.gameType.span,
        )
        val game = ChessGame(rules, board)
        game.restoreSeats(record.redPlayer, record.blackPlayer, record.phase)
        val session = GameSession(record.id, anchor, game, settings, record.gameType)
        session.visualHandle = restoreHandle(record.visualHandle, world)
        // 音乐开关跟着棋盘走：上一局把它关掉的人重启后不该被重新吵到
        session.bgmEnabled = record.bgmEnabled
        // 每局时限也要跟着回来：重启后玩家不该发现自己设的快棋时限被悄悄换回全局值
        session.game.turnTimeoutSeconds = record.turnTimeoutSeconds
        // 只有开过的局才摆棋子。存档里 WAITING 的棋盘重启后仍是空盘，
        // 与它被放下时的样子一致；FINISHED 要摆，终局盘面得留着让人复盘。
        if (game.hasStarted()) {
            session.renderer.renderAll(board)
        }
        // 恢复出来的棋盘同样要挂牌子：WAITING 的显示"加入/开始"，已开局的会自己隐藏
        session.refreshLabel()
        sessions[session.id] = session
        // 旧四方块迁移或原先 CE 缺失都会留下 null；注册表已经可用时立即补家具。
        if (session.visualHandle == null) {
            ensureVisual(session)
        }
        return session
    }

    /**
     * 恢复外观句柄并迁移 1.1.0 的四方块格式。
     *
     * - UUID：当前单家具句柄，直接沿用；
     * - `world:x:y:z`：旧四方块最小角，只清理这条记录明确登记的四格，再让
     *   [ensureVisual] 放置一个新家具；
     * - 未知格式：保留棋局、丢弃损坏句柄并写日志。
     */
    private fun restoreHandle(raw: String?, world: World): BoardVisualHandle? =
        when (val stored = BoardVisualHandleFormat.parse(raw)) {
            null -> null
            is StoredBoardVisualHandle.Furniture -> BoardVisualHandle(stored.uuid.toString())
            is StoredBoardVisualHandle.LegacyBlocks -> {
                if (stored.world != world.name) {
                    warning(
                        "旧棋盘句柄世界 ${stored.world} 与存档世界 ${world.name} 不一致，" +
                            "按当前已加载世界清理登记坐标",
                    )
                }
                for ((x, y, z) in BoardFootprint.blocksOf(stored.x, stored.y, stored.z)) {
                    world.getBlockAt(x, y, z).setType(Material.AIR, false)
                }
                info(
                    "已清理旧版四方块棋盘 ${world.name} ${stored.x},${stored.y},${stored.z}，" +
                        "等待转换为单家具",
                )
                markDirty()
                null
            }
            is StoredBoardVisualHandle.Unknown -> {
                warning("棋盘外观句柄格式不认识，已丢弃并等待重建：${stored.raw}")
                markDirty()
                null
            }
        }

    /** CE 家具注册表就绪后，为没有句柄的已恢复会话补放外观 */
    fun reconcileVisuals() {
        var restored = 0
        for (session in sessions.values) {
            if (session.visualHandle == null && ensureVisual(session)) {
                restored++
            }
        }
        if (restored > 0) {
            info("已为 $restored 副棋盘补建单家具外观")
            markDirty()
        }
    }

    /** 尝试为单个会话补家具；CE 不可用或放置失败时保持 null */
    /**
     * 句柄还在但家具已经没了时补建一副。
     *
     * 只在区块已加载的路径上调，见 [BoardVisual.isAlive] 的调用约束。
     *
     * 选补建而不是删掉会话，是因为**区分不出**"玩家拆了但回收事件没走到"与
     * "家具被清场命令删了"这两种情况。补建的代价是玩家可能看到棋盘回来一次，
     * 但它可见、可再拆，而且补建后句柄是新的，下次拆一定能命中会话；
     * 删会话的代价是把一副还在进行的对局连棋盘一起抹掉，不可逆。代价不对等。
     */
    private fun ensureVisualAlive(session: GameSession) {
        val handle = session.visualHandle ?: return
        val visual = BoardVisualRegistry.current
        if (!visual.isAvailable()) {
            return
        }
        if (visual.isAlive(handle)) {
            return
        }
        // 不吞这条信息：家具凭空消失是异常状态，服主要能从日志里看到发生过
        warning("棋盘 ${session.id} 的家具 ${handle.id} 已不在世界里，补建一副")
        session.visualHandle = null
        val rebuilt = ensureVisual(session)
        // 无论补建成功与否都要落盘：句柄已经从死 UUID 变成了别的值。
        // 只在成功时落盘会留下一个陷阱 —— 补建失败时存档里仍是那个死 UUID，
        // 重启后 restore 按它造出非空句柄、于是跳过补建，这副棋盘又回到
        // "会话在、家具没了、永远收不掉"的卡死状态。
        markDirty()
        if (!rebuilt) {
            warning("棋盘 ${session.id} 补建家具失败，已清空句柄，下次重启会重试")
        }
    }

    private fun ensureVisual(session: GameSession): Boolean {
        val visual = BoardVisualRegistry.current
        if (!visual.isAvailable()) {
            return false
        }
        val origin = Location(
            session.anchor.world,
            session.anchor.origin.x,
            session.anchor.origin.y,
            session.anchor.origin.z,
        )
        val handle = runCatching { visual.place(origin, session.anchor.facing, session.gameType.furnitureId) }.getOrElse {
            warning("补建棋盘家具失败 ${session.id}：${it.message}")
            null
        } ?: return false
        session.visualHandle = handle
        return true
    }

    /** 存档是否有未落盘的改动 */
    @Volatile
    var dirty: Boolean = false
        private set

    /** 标记有改动，下一次定时落盘会写出去 */
    fun markDirty() {
        dirty = true
    }

    /** 落盘完成后清标记 */
    fun clearDirty() {
        dirty = false
    }

    /** 找棋盘时的最大水平距离，单位方块 */
    const val SEARCH_RADIUS = 8.0

    /**
     * 拆棋盘兜底清扫的半径，单位区块。
     *
     * 只需要罩住这副棋盘自己的显示实体，它们都在棋盘中心一两格内，
     * 1 个区块（连中心共 3x3）足够覆盖跨区块边界的情况，再大就是白扫。
     */
    const val BREAK_SWEEP_RADIUS_CHUNKS = 1

    /**
     * 管理员清扫命令的默认半径，单位区块。
     *
     * 8 个区块是 128 格，罩得住管理员视野内的残留；扫描窗口 17x17 = 289 个区块，
     * 主线程遍历这个量级的实体在可接受范围内。
     */
    const val SWEEP_RADIUS_DEFAULT = 8

    /**
     * 管理员清扫命令的半径硬上限，单位区块。
     *
     * 16 个区块窗口是 33x33 = 1089 个区块。再往上主线程遍历实体的耗时会明显卡顿，
     * 而清扫本来就该由管理员走到残留附近执行，不需要覆盖整张地图。
     */
    const val SWEEP_RADIUS_MAX = 16
}

/**
 * 一次清扫删掉了多少残留显示实体。
 *
 * [total] 与 [byRole] 分开存是必要的：用途标记认不出的实体照样要删、照样要计入总数，
 * 但它进不了任何一个分项。如果只返回分项、让调用方求和，那种实体就会被删掉却不计数，
 * 命令于是在实际删了东西之后报"没找到残留"，服主会以为清扫没生效。
 */
data class SweepResult(
    /** 删掉的实体总数，含用途认不出的那些 */
    val total: Int,
    /** 按用途分项计数，仅用于报数与日志 */
    val byRole: Map<DisplayMarker.Role, Int>,
) {
    companion object {
        /** 什么都没删 */
        val EMPTY = SweepResult(0, emptyMap())
    }
}

/** 放置棋盘的结果 */
sealed interface PlaceResult {

    /** 放好了 */
    data class Success(val session: GameSession) : PlaceResult

    /** 2x2 放不下 */
    data object NoSpace : PlaceResult

    /** 到了世界棋盘数量上限 */
    data class LimitReached(val limit: Int) : PlaceResult

    /**
     * 占位范围内有 CE 家具阻挡。
     *
     * [blocked] 是被阻挡格子的坐标列表，供调用方发粒子标记。
     * 可能来自 2x2 占位层（同层 hitbox 重合）或正下方承托层（家具不是实体方块，
     * groundLevel 会穿过去导致棋盘错层）。
     */
    data class FurnitureOverlap(val blocked: List<Location>) : PlaceResult
}
