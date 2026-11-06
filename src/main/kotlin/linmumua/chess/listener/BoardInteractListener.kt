package linmumua.chess.listener

import kotlin.math.abs
import linmumua.chess.board.BoardFootprint
import linmumua.chess.config.ChessConfig
import linmumua.chess.game.GameManager
import linmumua.chess.game.GameSession
import org.bukkit.block.Block
import linmumua.chess.interact.BoardMenu
import linmumua.chess.interact.MoveExecutor
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import taboolib.common.platform.event.SubscribeEvent
import taboolib.common.platform.function.info

/**
 * 棋盘右键命中解析。
 *
 * 需求里的命中方式是 CraftEngine 的 `FurnitureInteractEvent`。CE 的
 * `interactionPoint()` 可能落在家具判定箱侧面，而不是棋盘平面；这里会沿同一条
 * 点击射线投影到棋盘面后再反算交叉点，避免准星对着棋子却算成空格。
 *
 * CE 事件监听属于桥接层职责（`compat/`），而且 CE 可能不存在，所以这里仍保留
 * **原版兜底命中**：玩家右键点到棋盘所在方块时，用 Paper 给出的顶面落点反算。
 * 两条路径最终都只调用 [MoveExecutor.handleClick]，不重复实现交互规则。
 */
object BoardInteractListener {

    /**
     * 原版右键兜底。
     *
     * 只处理右键方块，且必须点在棋盘附近，否则一律放过。
     * 不取消事件的话玩家会同时触发放置方块之类的原版行为，所以命中棋盘时要取消。
     */
    @SubscribeEvent
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK || event.hand != EquipmentSlot.HAND) {
            return
        }
        val block = event.clickedBlock ?: return
        val player = event.player

        if (player.isSneaking) {
            val held = player.inventory.itemInMainHand.type
            // 潜行拿方块时让原版/CE 继续处理放置，保住棋盘下方可建造的既有约定。
            if (!held.isAir && held.isBlock) {
                return
            }
            val session = GameManager.sessionCovering(block.location) ?: return
            event.isCancelled = true
            BoardMenu.open(player, session)
            return
        }

        // 先按方块位置粗筛，避免每次右键都去遍历全部棋盘
        val session = GameManager.nearestSession(block.location) ?: return

        // CE 家具在场时这条兜底必须让路：家具自己的 hitbox 会发 FurnitureInteractEvent，
        // 棋盘上的点击已经由那条路径处理完了。继续往下走只会把**棋盘之外**的右键也吞掉 ——
        // 典型症状就是棋盘正下方放不了方块，而 shift+右键因为上面那条持方块例外反而能放。
        if (session.visualHandle != null) {
            return
        }

        // 兜底只认棋盘那一层和它的承托层。squareAt 只比对 x/z、不看 Y，
        // 少了这道限制的话棋盘 2x2 立柱上下任意高度的右键都会被当成落子并取消事件。
        if (!isBoardPlane(session, block)) {
            return
        }

        // 用玩家准星与棋盘面的交点做精确落点：点在棋盘方块的顶面上时最准
        val point = interactionPoint(event) ?: return
        val square = session.anchor.squareAt(point) ?: return

        event.isCancelled = true
        MoveExecutor.handleClick(player, session, square)
    }

    /**
     * 被点的方块是否就是棋盘平面本身。
     *
     * CE 缺失时棋盘那一层是空气（棋盘本体是家具实体），玩家能点到的是它下面的承托方块，
     * 顶面正好落在棋盘平面上。所以这两层都算命中：
     *
     * - `blockY == originY`：棋盘所在层，该层是实体方块时点它的顶面；
     * - `blockY == originY - 1`：承托层，它的顶面就是棋盘平面。
     *
     * 其余高度一律放过，交回原版处理，否则棋盘上下的建造都会被吞。
     */
    private fun isBoardPlane(session: GameSession, block: Block): Boolean {
        val origin = session.anchor.origin
        if (session.anchor.world != block.world) {
            return false
        }
        return BoardFootprint.containsBoardPlane(
            origin.blockX,
            origin.blockY,
            origin.blockZ,
            block.x,
            block.y,
            block.z,
            // 按该会话自己的棋种边长判，围棋是 3x3
            session.gameType.footprintSize,
        )
    }

    /**
     * 取右键的精确落点。
     *
     * Paper 的 [PlayerInteractEvent.getInteractionPoint] 给的是准星与方块的交点，
     * 正好是我们需要的"点在棋盘面的哪个位置"。拿不到时退回方块中心，
     * 那样精度只有一格，会落在最近的交叉点上。
     */
    private fun interactionPoint(event: PlayerInteractEvent): Location? =
        event.interactionPoint ?: event.clickedBlock?.location?.add(0.5, 1.0, 0.5)

    /**
     * 供 CraftEngine 桥接层调用的家具落子入口。
     *
     * 家具句柄优先精确定位会话；拿不到句柄才退回附近查找。CE 返回的命中点可能在
     * 判定箱侧面，因此沿“玩家眼睛 → 原始命中点”的同一射线投影到棋盘面，再反算格点。
     */
    fun handleFurniture(player: Player, visualHandle: String?, point: Location) {
        val byHandle = visualHandle?.let { GameManager.sessionByVisualHandle(it) }
        val session = byHandle ?: GameManager.nearestSession(point)
        if (session == null) {
            debug("棋盘右键解析失败：找不到会话，handle=${visualHandle ?: "missing"}，point=${describe(point)}")
            return
        }
        if (player.isSneaking) {
            BoardMenu.open(player, session)
            return
        }

        val projected = projectToSurface(player, point, session.anchor.surfaceY)
        val projectedSquare = projected?.let { session.anchor.squareAt(it) }
        val rawSquare = session.anchor.squareAt(point)
        val square = projectedSquare ?: rawSquare
        if (square == null) {
            debug(
                "棋盘右键解析失败：命中点不在格点容差内，session=${session.id}，" +
                    "raw=${describe(point)}，projected=${projected?.let(::describe) ?: "unavailable"}",
            )
            return
        }

        MoveExecutor.handleClick(player, session, square)
    }

    /**
     * 供原版兜底路径调用的精确落点入口。
     *
     * Paper 的方块交互点已经在棋盘顶面，不需要再次投影。
     */
    fun handlePoint(player: Player, point: Location) {
        val session = GameManager.nearestSession(point) ?: return
        val square = session.anchor.squareAt(point) ?: return
        MoveExecutor.handleClick(player, session, square)
    }

    /** 把家具判定箱上的原始命中点沿点击射线投影到真实棋盘面 */
    private fun projectToSurface(player: Player, point: Location, surfaceY: Double): Location? {
        val eye = player.eyeLocation
        if (eye.world != point.world) {
            return null
        }
        val ray = point.toVector().subtract(eye.toVector())
        if (abs(ray.y) < RAY_VERTICAL_EPSILON) {
            return null
        }
        val factor = (surfaceY - eye.y) / ray.y
        if (factor <= 0.0) {
            return null
        }
        return eye.clone().add(ray.multiply(factor))
    }

    /** 调试模式下记录命中解析阶段；默认配置关闭，不影响正常控制台 */
    private fun debug(message: String) {
        if (ChessConfig.settings.debug) {
            info(message)
        }
    }

    /** 控制台诊断用坐标，不属于玩家可见文案 */
    private fun describe(location: Location): String = "${location.x},${location.y},${location.z}"

    private const val RAY_VERTICAL_EPSILON = 1.0e-6
}
