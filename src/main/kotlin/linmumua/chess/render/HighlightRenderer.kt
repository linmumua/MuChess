package linmumua.chess.render

import java.util.UUID
import linmumua.chess.board.BoardAnchor
import linmumua.chess.board.PieceOrientation
import linmumua.chess.config.ChessSettings
import linmumua.chess.rule.core.Square
import org.bukkit.Color
import org.bukkit.Particle
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f

/**
 * 选中态的世界内提示渲染器。
 *
 * 空合法落点使用透明白圈 ItemDisplay；可吃子直接给目标棋子红色描边，选中的己方棋子
 * 使用橙色描边。白圈没有命中箱，右键会继续落到 CraftEngine 家具的 Interaction hitbox，
 * 再由现有坐标反算逻辑完成走子。
 *
 * 一副棋盘对应一个实例，全部实体都由内存索引维护。所有方法必须在主线程调用。
 */
class HighlightRenderer(
    private val anchor: BoardAnchor,
    private val renderer: BoardRenderer,
    private var settings: ChessSettings,
    /**
     * 所属会话身份，写进每个白圈实体的归属标记。
     *
     * 见 [DisplayMarker]。白圈生命周期比棋子短得多，但同样会因为索引丢引用而残留 ——
     * 玩家选中后会话异常消失，白圈就留在盘面上不会消退。
     */
    private val sessionId: UUID,
) {

    /** 空合法落点 → 白圈显示实体 */
    private val markers = HashMap<Int, ItemDisplay>()

    /** 已渲染的白圈数量，供日志与测试观察 */
    val renderedMarkerCount: Int get() = markers.size

    /**
     * 本渲染器当前在册的白圈实体 UUID。
     *
     * 只读，供清扫构造"活会话在册实体全集"用，见 [OrphanDecision]。
     */
    fun trackedEntityIds(): Set<UUID> = markers.values.mapTo(HashSet()) { it.uniqueId }

    /** 配置重载前由 GameManager 清掉现有选中态，这里只替换参数快照 */
    fun updateSettings(newSettings: ChessSettings) {
        settings = newSettings
    }

    /**
     * 显示一次完整选中态。
     *
     * 重复调用前先清旧状态，保证换选中时不会留下上一枚棋子的白圈或发光。
     */
    fun showSelection(
        selected: Square,
        empties: Collection<Square>,
        captures: Collection<Square>,
    ) {
        clear()
        renderer.showSelectionGlows(selected, captures)
        empties.forEach(::spawnMarker)
    }

    /** 移除全部白圈并恢复本次选中态设置过的棋子描边 */
    fun clear() {
        markers.values.forEach { it.remove() }
        markers.clear()
        renderer.clearSelectionGlows()
    }

    /**
     * 在一个空合法落点生成无碰撞、非持久化的小型白圈。
     *
     * X/Z 必须完整复用 [BoardAnchor.locationOf]，确保圆心与棋盘横纵线交点共用同一坐标真源；
     * 配置只允许在 Y 上增加极小间隙，不能用屏幕方向补偿格点位置。
     */
    private fun spawnMarker(square: Square) {
        val location = anchor.locationOf(square).apply {
            y += settings.highlight.markerYOffset
        }
        val world = location.world ?: return
        val display = world.spawn(location, ItemDisplay::class.java) { entity: ItemDisplay ->
            entity.setItemStack(MoveMarkerAppearanceRegistry.resolve())
            entity.billboard = Display.Billboard.FIXED
            entity.setTransformation(markerTransformation())
            entity.shadowRadius = 0f
            entity.shadowStrength = 0f
            entity.isPersistent = false
            // 归属标记，供索引丢引用后的清扫识别，见 DisplayMarker
            DisplayMarker.mark(entity, sessionId, DisplayMarker.Role.MARKER)
        }
        markers[square.packed] = display
    }

    /** 白圈平躺在棋盘面上；圆形对旋转不敏感，因此不需要按阵营计算 yaw */
    private fun markerTransformation(): Transformation = Transformation(
        Vector3f(0f, 0f, 0f),
        Quaternionf().rotateX(
            Math.toRadians(PieceOrientation.FLAT_PITCH_DEGREES.toDouble()).toFloat(),
        ),
        Vector3f(settings.highlight.markerScale.toFloat()),
        Quaternionf(),
    )

    /**
     * 给被将军的将/帥发一次红色警示粒子。
     *
     * 这是将军事件提示，不属于合法落点路径；合法落点已经完全改用白圈与实体描边。
     */
    fun markCheckedKing(viewers: Collection<Player>, king: Square) {
        if (!settings.highlight.markCheckedKing || viewers.isEmpty()) {
            return
        }
        val location = anchor.locationOf(king).apply { y += CHECK_Y_OFFSET }
        val data = Particle.DustTransition(CHECK_COLOR, CHECK_TO_COLOR, CHECK_DUST_SIZE)
        for (viewer in viewers) {
            if (viewer.world != location.world) {
                continue
            }
            viewer.spawnParticle(
                Particle.DUST_COLOR_TRANSITION,
                location,
                CHECK_PARTICLE_COUNT,
                0.0,
                0.0,
                0.0,
                data,
            )
        }
    }

    /**
     * 在被吃掉的棋子位置放一小团爆炸火光。
     *
     * 只用于炮吃子。这里**不用** `Particle.EXPLOSION`：原版爆炸粒子的显示尺寸写死在
     * 客户端，一团就能盖住小半个棋盘，跟棋子尺寸完全不成比例。改用
     * `DUST_COLOR_TRANSITION` 自己堆一小圈，尺寸由 `capture-explosion.radius` 控制，
     * 默认约等于一枚棋子的大小；颜色从亮黄经橙红过渡到深灰，模拟火光转烟。
     *
     * @param viewers 能看到的玩家，与播报对象一致
     * @param square 被吃掉的棋子所在交叉点
     */
    fun showCaptureExplosion(viewers: Collection<Player>, square: Square) {
        val explosion = settings.captureExplosion
        if (!explosion.enabled || viewers.isEmpty()) {
            return
        }
        val center = anchor.locationOf(square).apply { y += EXPLOSION_Y_OFFSET }
        val world = center.world ?: return
        val radius = explosion.radius
        val data = Particle.DustTransition(
            EXPLOSION_CORE_COLOR,
            EXPLOSION_SMOKE_COLOR,
            EXPLOSION_DUST_SIZE,
        )
        for (viewer in viewers) {
            if (viewer.world != world) {
                continue
            }
            // 圆心一粒作核心，四周按固定角度撒一圈，避免用随机偏移导致每次形状差别太大
            viewer.spawnParticle(Particle.DUST_COLOR_TRANSITION, center, 1, 0.0, 0.0, 0.0, data)
            for (index in 0 until EXPLOSION_RING_COUNT) {
                val angle = 2.0 * Math.PI * index / EXPLOSION_RING_COUNT
                val point = center.clone().add(
                    Math.cos(angle) * radius,
                    // 轻微抬高一点，让这圈火光看起来是从棋子上鼓出来而不是贴在棋盘上
                    EXPLOSION_RING_LIFT * radius,
                    Math.sin(angle) * radius,
                )
                viewer.spawnParticle(Particle.DUST_COLOR_TRANSITION, point, 1, 0.0, 0.0, 0.0, data)
            }
        }
    }

    private companion object {
        const val CHECK_PARTICLE_COUNT = 2
        const val CHECK_Y_OFFSET = 0.08
        const val CHECK_DUST_SIZE = 0.7f
        val CHECK_COLOR: Color = Color.fromRGB(0xFD, 0xA4, 0xAF)
        val CHECK_TO_COLOR: Color = Color.fromRGB(0xDC, 0x26, 0x26)

        /** 爆炸火光离棋盘面的高度，与棋子厚度相当 */
        const val EXPLOSION_Y_OFFSET = 0.05

        /** 火圈粒子数。8 粒够看出是一圈，再多在棋子尺度上会糊成一坨 */
        const val EXPLOSION_RING_COUNT = 8

        /** 火圈相对半径的抬升比例，让火光呈微微鼓起的形状 */
        const val EXPLOSION_RING_LIFT = 0.35

        /** 单粒尺寸。棋子只有 0.17 大，粒子再大就盖住棋盘线了 */
        const val EXPLOSION_DUST_SIZE = 0.55f

        /** 爆心亮黄 */
        val EXPLOSION_CORE_COLOR: Color = Color.fromRGB(0xFF, 0xD1, 0x66)

        /** 收尾的深灰烟色 */
        val EXPLOSION_SMOKE_COLOR: Color = Color.fromRGB(0x57, 0x53, 0x4E)
    }
}
