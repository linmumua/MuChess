package linmumua.chess.render

import java.util.UUID
import kotlin.math.pow
import linmumua.chess.board.BoardAnchor
import linmumua.chess.board.PieceOrientation
import linmumua.chess.config.ChessSettings
import linmumua.chess.game.GameType
import linmumua.chess.rule.core.Board
import linmumua.chess.rule.core.Piece
import linmumua.chess.rule.core.Square
import org.bukkit.Color
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import taboolib.common.platform.function.warning
import taboolib.platform.BukkitPlugin

/**
 * 棋子显示实体的渲染器。
 *
 * 一副棋盘对应一个渲染器实例，维护"交叉点 → 显示实体"的内存索引。
 * 项目明确禁止用轮询扫实体来找棋子，所以这个索引是棋子实体的唯一真源，
 * 任何增删都必须经过本类，不允许外部自己 spawn 或 remove。
 *
 * 所有方法都必须在主线程调用：Bukkit 的实体操作不是线程安全的。
 *
 * @param anchor 坐标标定，提供交叉点 ↔ 世界坐标换算
 * @param settings 配置快照，取缩放与动画参数
 */
class BoardRenderer(
    val anchor: BoardAnchor,
    private var settings: ChessSettings,
    /**
     * 本副棋盘的棋种。
     *
     * 棋子缩放按棋种取：五子棋 15 路盘铺在同样的 2x2 上，格点间距只有象棋的一半，
     * 沿用 `piece.base-scale` 会让相邻子直接叠在一起，所以各棋种一个缩放值。
     */
    private val gameType: GameType = GameType.XIANGQI,
    /**
     * 所属会话身份，写进每枚棋子实体的归属标记。
     *
     * 见 [DisplayMarker]：内存索引丢了引用之后，标记是世界里唯一能证明
     * 这枚棋子属于本插件的东西，否则它既认不出也删不掉。
     *
     * 刻意不给默认值：一旦有默认值，将来新增的调用点漏传也能编译过，
     * 于是静默生成一批打不上标记、清扫也救不回来的实体。
     */
    private val sessionId: UUID,
) {

    /** 本棋种的棋子基础缩放，见 [gameType] */
    private val pieceBaseScale: Double
        get() = when (gameType) {
            GameType.XIANGQI -> settings.piece.baseScale
            GameType.GOMOKU -> settings.gomoku.baseScale
            GameType.GO -> settings.go.baseScale
        }

    /**
     * 选中放大后的缩放。
     *
     * 象棋直接用配置值；五子棋与围棋按象棋的放大**倍率**折算，避免服主改了
     * `piece.selected-scale` 之后落子棋的选中子放大到压住整片相邻子。
     * 这两个棋种是落子棋没有选中态，这里只为落子动画的插值起点服务。
     */
    private val pieceSelectedScale: Double
        get() = when (gameType) {
            GameType.XIANGQI -> settings.piece.selectedScale
            // 五子棋与围棋同样按倍率折算，只是各自的 base 不同
            GameType.GOMOKU, GameType.GO ->
                pieceBaseScale * (settings.piece.selectedScale / settings.piece.baseScale)
        }

    /**
     * 交叉点 → 显示实体的索引。
     *
     * 键用 [Square.packed] 而不是 Square 本身，因为 Square 是 value class，
     * 作为 Map 键会装箱；packed 是 Int，索引更省。
     */
    private val pieces = HashMap<Int, ItemDisplay>()

    // 字面方向由红黑双方 × 四种棋盘朝向的模型固化，渲染器不再维护角度索引。

    /** 当前浮起的那枚子所在点，没有则为 null。用于落子时判断要不要先落下 */
    private var hovered: Square? = null

    /** 本次选中态设置过发光的格点，只清这些实体，不扫描整副棋盘 */
    private val selectionGlows = HashSet<Int>()

    /**
     * 显示实体 → 当前动画代次。
     *
     * 走子期间由逐 tick 任务按抬起 → 平移 → 落下三段刷新位置、高度与缩放。
     * 棋盘清理、重同步或同一枚子再次移动时，旧任务必须失效，不能把新画面改回旧状态；
     * 代次校验比保存并逐个取消 PlatformTask 更简单可靠。
     */
    private val animationTokens = HashMap<UUID, Long>()

    /** 主线程递增即可，配合实体 UUID 区分同一枚棋子的前后两次动画 */
    private var animationSequence = 0L

    /** 已渲染的棋子数量，供日志与测试观察 */
    val renderedCount: Int get() = pieces.size

    /** 是否仍有棋子处于走子弧线中，用于阻止下一步与当前动画重叠。 */
    fun isMoveAnimating(): Boolean = animationTokens.isNotEmpty()

    /** 配置重载后换一份参数，已存在的实体在下一次刷新时跟上 */
    fun updateSettings(newSettings: ChessSettings) {
        settings = newSettings
    }

    /** 取某点上的显示实体，没有返回 null */
    fun entityAt(square: Square): ItemDisplay? = pieces[square.packed]

    /**
     * 按局面铺满整副棋子。
     *
     * 会先清掉已有实体，保证不会因为重复调用而叠出两副棋子。
     */
    fun renderAll(board: Board) {
        clear()
        for (square in board.spec.squares()) {
            val piece = board[square] ?: continue
            spawn(square, piece)
        }
    }

    /**
     * 在某点生成一枚棋子实体。
     *
     * 同一点已有实体时先移除旧的，避免索引里留下孤儿实体。
     */
    fun spawn(square: Square, piece: Piece) {
        remove(square)
        // anchor.surfaceY 已经包含薄板厚度与 surface-offset，不在渲染层重复抬高
        val location = anchor.locationOf(square)
        val world = location.world ?: run {
            warning("棋盘所在世界不可用，跳过棋子渲染：$square")
            return
        }
        val display = world.spawn(location, ItemDisplay::class.java) { entity: ItemDisplay ->
            // 用显式 setter 而不是属性写法：Paper 对 ItemDisplay.getItemStack 标了可空注解，
            // Kotlin 由此合成出来的是只读属性，赋值会编译不过。
            // 红黑与棋盘四向已经写入内部物品模型，这里只按 anchor.facing 选择模型。
            entity.setItemStack(PieceAppearanceRegistry.resolve(piece, anchor.facing))
            // ItemDisplay 的 FIXED 使用模型 display.fixed 缩放与字面方向；billboard 固定朝向。
            entity.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
            entity.billboard = Display.Billboard.FIXED
            entity.setTransformation(baseTransformation())
            // 棋子不该有阴影，否则 32 枚叠在一起会糊成一片
            entity.shadowRadius = 0f
            entity.shadowStrength = 0f
            // 不持久化：显示实体由插件索引管着，存进区块反而会在重启后变成孤儿实体
            entity.isPersistent = false
            // 归属标记与上面那行是两层防护：isPersistent 防重启后残留，
            // 标记防服务端不停、索引丢了引用的残留。见 DisplayMarker。
            DisplayMarker.mark(entity, sessionId, DisplayMarker.Role.PIECE)
        }
        pieces[square.packed] = display
    }

    /** 移除某点的棋子实体，没有则什么都不做 */
    fun remove(square: Square) {
        pieces.remove(square.packed)?.let { display ->
            animationTokens.remove(display.uniqueId)
            display.remove()
        }
        selectionGlows.remove(square.packed)
        if (hovered == square) {
            hovered = null
        }
    }

    /**
     * 批量移除多个点上的棋子实体，供围棋提子用。
     *
     * 围棋一手可提一整块，[linmumua.chess.rule.core.Move.captured] 只装得下一枚，
     * 所以提子点位由 `MoveAttempt.Success.captured` 带过来，在这里逐个删。
     * 不做也不能做兜底扫描 —— 项目规则禁止轮询实体找棋子，漏删的表现是
     * 世界里留下一枚"盘面上没有但看得见"的幽灵子。
     *
     * 空列表时直接返回，象棋与五子棋每手都会走到这里但不该有任何开销。
     */
    fun removeAll(squares: List<Square>) {
        if (squares.isEmpty()) {
            return
        }
        squares.forEach { remove(it) }
    }

    /**
     * 本渲染器当前在册的棋子实体 UUID。
     *
     * 只读，供清扫构造"活会话在册实体全集"用，见 [OrphanDecision]。
     * 不 spawn 也不 remove，所以不破坏"棋子实体增删只经过本类"这条纪律。
     */
    fun trackedEntityIds(): Set<UUID> = pieces.values.mapTo(HashSet()) { it.uniqueId }

    /**
     * 清掉本棋盘的全部棋子实体。
     *
     * 关服与终局都要调用。项目要求关服后世界里不能残留显示实体，
     * 所以这里必须把索引也清空，不能只 remove 实体。
     */
    fun clear() {
        pieces.values.forEach { it.remove() }
        pieces.clear()
        selectionGlows.clear()
        animationTokens.clear()
        hovered = null
    }

    /**
     * 棋子平躺时的基础变换。
     *
     * 红黑相对方向与棋盘四向已经由所选物品模型的 `display.fixed.rotation` 固化；运行期只绕
     * X 轴 +90° 把自定义双面模型从 XY 平面铺到棋盘面，不能再叠加任何字面 yaw。
     */
    private fun baseTransformation(): Transformation = Transformation(
        Vector3f(0f, restingLift(pieceBaseScale), 0f),
        layFlat(),
        Vector3f(pieceBaseScale.toFloat()),
        Quaternionf(),
    )

    /**
     * 选中态变换：浮起并放大。
     *
     * 平移量给在 Y 上，因为旋转走的是 leftRotation，translation 不受它影响，仍然是世界
     * 坐标系下的偏移。模型自带的字面方向不会因浮起、落下或走子动画发生变化。
     */
    private fun hoverTransformation(): Transformation = Transformation(
        Vector3f(
            0f,
            restingLift(pieceSelectedScale) + settings.piece.hoverHeight.toFloat(),
            0f,
        ),
        layFlat(),
        Vector3f(pieceSelectedScale.toFloat()),
        Quaternionf(),
    )

    /** 走子帧变换：平面位置交给 teleport 插值，这里只负责弧线高度与缩放 */
    private fun moveTransformation(height: Double, scale: Double): Transformation = Transformation(
        Vector3f(0f, restingLift(scale) + height.toFloat(), 0f),
        layFlat(),
        Vector3f(scale.toFloat()),
        Quaternionf(),
    )

    /**
     * 棋子坐在棋盘面上所需的抬升量，单位方块。
     *
     * ItemDisplay 的模型以几何中心为原点，铺平之后棋子的厚度是**上下对半**分布的。
     * 不抬的话有一半厚度埋进棋盘薄板里，露在外面的只有半个厚度 —— 这正是之前
     * 补了侧壁仍然像一张纸的原因：0.0744 格的厚度只露出 0.0372 格。
     *
     * 抬起半个厚度之后，棋子底面正好贴在棋盘面上，整段厚度都在板面之上。
     *
     * 还要再减掉 `board.surface-offset`。`anchor.surfaceY` 是"板顶 + surface-offset"，
     * 那个偏移本来是给平面贴图防 z-fighting 用的；棋子现在是有厚度的实体盒，底面压在
     * 板顶上不会闪，留着偏移只会让棋子悬空一线（默认 0.02 格，低角度看得很清楚）。
     * 所以这里把它扣回去，让底面精确落在板顶。
     *
     * 厚度随缩放变化，所以按当帧的 [scale] 算，不能写成常量：浮起时棋子放大，
     * 抬升量也要跟着变大，否则放大的瞬间底面会切进板子。
     */
    private fun restingLift(scale: Double): Float {
        val halfThickness = PIECE_MODEL_THICKNESS * PIECE_FIXED_DEPTH_SCALE * scale / 2.0
        return (halfThickness - settings.board.surfaceOffset).toFloat()
    }

    /** 只负责把模型平放到棋盘面，不参与任何文字朝向计算。 */
    private fun layFlat(): Quaternionf =
        Quaternionf().rotateX(Math.toRadians(PieceOrientation.FLAT_PITCH_DEGREES.toDouble()).toFloat())

    /**
     * 让某点的棋子浮起放大。
     *
     * Display 的 Transformation 由客户端线性插值。目标变换先写入，再用
     * interpolationDuration 与 interpolationDelay=0 触发本次补间；delay setter 会强制
     * 刷新开始时刻，因此连续选中、取消也不会沿用上一段动画。
     *
     * animation-ticks 配成 0 时不插值，直接跳到目标形态。
     */
    fun hover(square: Square) {
        val display = pieces[square.packed] ?: return
        // 同一时刻只允许一枚浮起，切换选中时先把上一枚放回去
        hovered?.takeIf { it != square }?.let { settle(it) }
        invalidateAnimation(display)
        applyInterpolated(display, hoverTransformation(), settings.piece.animationTicks)
        hovered = square
    }

    /** 让某点的棋子落回棋盘面 */
    fun settle(square: Square) {
        val display = pieces[square.packed] ?: return
        invalidateAnimation(display)
        applyInterpolated(display, baseTransformation(), settings.piece.animationTicks)
        if (hovered == square) {
            hovered = null
        }
    }

    /** 当前浮起的棋子所在点 */
    fun hoveredSquare(): Square? = hovered

    /** 把当前浮起的那枚放回去，没有则什么都不做 */
    fun settleHovered() {
        hovered?.let { settle(it) }
    }

    /**
     * 显示本次选中态：自己的棋子橙色发光，可吃的敌方棋子红色发光。
     *
     * Display 自带 glow color override，不需要创建 scoreboard team，因而不会污染
     * 玩家队伍或其他插件的发光颜色。
     */
    fun showSelectionGlows(selected: Square, captures: Collection<Square>) {
        clearSelectionGlows()
        applySelectionGlow(selected, SELECTED_GLOW)
        captures.forEach { applySelectionGlow(it, CAPTURE_GLOW) }
    }

    /** 清掉本次选中态设置过的全部发光描边 */
    fun clearSelectionGlows() {
        selectionGlows.forEach { packed ->
            pieces[packed]?.let { display ->
                display.setGlowing(false)
                display.setGlowColorOverride(null)
            }
        }
        selectionGlows.clear()
    }

    /** 给某点棋子设置发光颜色，没有棋子时直接忽略 */
    private fun applySelectionGlow(square: Square, color: Color) {
        val display = pieces[square.packed] ?: return
        display.setGlowColorOverride(color)
        display.setGlowing(true)
        selectionGlows += square.packed
    }

    /**
     * 线性插值到目标变换。
     *
     * 目标 Transformation 必须先写入，最后设置 interpolationDelay=0 来刷新客户端的
     * 起始 tick。duration 为 0 时直接切换，不保留上一段动画时长。
     */
    private fun applyInterpolated(display: ItemDisplay, transformation: Transformation, duration: Int) {
        display.setTransformation(transformation)
        display.interpolationDuration = duration.coerceAtLeast(0)
        display.interpolationDelay = 0
    }

    /** 清掉某实体当前的走子动画代次，让已排队的旧回调自行失效 */
    private fun invalidateAnimation(display: ItemDisplay) {
        animationTokens.remove(display.uniqueId)
    }

    /** 为本次走子登记新代次；同一枚子再次移动时会覆盖旧代次 */
    private fun beginAnimation(display: ItemDisplay): Long {
        animationSequence++
        animationTokens[display.uniqueId] = animationSequence
        return animationSequence
    }

    /** 仅清理仍属于当前代次的动画锁，不能误删同一实体后来启动的新动画。 */
    private fun finishAnimation(display: ItemDisplay, token: Long) {
        if (animationTokens[display.uniqueId] == token) {
            animationTokens.remove(display.uniqueId)
        }
    }

    /** 逐 tick 回调只认当前实体、当前目标格与当前代次，不能回写已经变化的新画面 */
    private fun isCurrentAnimation(display: ItemDisplay, targetSquare: Square, token: Long): Boolean =
        display.isValid &&
            pieces[targetSquare.packed] === display &&
            animationTokens[display.uniqueId] == token

    /**
     * 沿一条连续弧线推进走子：平移与升降同时进行，中途不停顿。
     *
     * 曲线本身在 [handMoveFrame] 里，这里只负责把每帧姿态发给客户端。
     * ItemDisplay 自带的长距离 teleport 插值只能匀速，所以水平位移拆成逐 tick 短帧，
     * 每帧给一段 1 tick 的插值保持平滑，整体节奏由服务端曲线控制。
     * 服务端局面与索引在 [applyMove] 里已经即时落到目标点，这里只改表现。
     */
    private fun startMoveArc(
        display: ItemDisplay,
        targetSquare: Square,
        target: org.bukkit.Location,
        startHeight: Double,
        startScaleFactor: Double,
        token: Long,
    ) {
        if (!isCurrentAnimation(display, targetSquare, token)) {
            finishAnimation(display, token)
            return
        }

        val start = display.location.clone()
        val piece = settings.piece
        val baseScale = pieceBaseScale
        val selectedScaleFactor = pieceSelectedScale / baseScale
        val totalTicks = piece.moveTicks
        // 每 tick 只插值到下一个短帧，客户端仍然平滑，但整体速度由服务端曲线控制。
        display.teleportDuration = FRAME_INTERPOLATION_TICKS
        applyInterpolated(
            display,
            moveTransformation(startHeight, startScaleFactor * baseScale),
            FRAME_INTERPOLATION_TICKS,
        )

        var elapsedTicks = 0
        object : BukkitRunnable() {
            override fun run() {
                if (!isCurrentAnimation(display, targetSquare, token)) {
                    finishAnimation(display, token)
                    cancel()
                    return
                }

                try {
                    elapsedTicks++
                    val frame = handMoveFrame(
                        tick = elapsedTicks,
                        totalTicks = totalTicks,
                        liftHeight = piece.moveLiftHeight,
                        startHeight = startHeight,
                        startScaleFactor = startScaleFactor,
                        selectedScaleFactor = selectedScaleFactor,
                    )
                    val frameTarget = start.clone().add(
                        (target.x - start.x) * frame.planarProgress,
                        (target.y - start.y) * frame.planarProgress,
                        (target.z - start.z) * frame.planarProgress,
                    )

                    if (elapsedTicks >= totalTicks) {
                        // 收尾帧：位置与形态都落到精确终点，但**保持和中间帧同一段插值时长**。
                        //
                        // 原来这里把 teleportDuration 与 interpolationDuration 一起设成 0
                        // 再硬跳到终点。上一帧刚下发过 1 tick 的插值，客户端才走到一半就被
                        // 硬切，画面上就是落地那一下明显一顿、还闪一下。
                        //
                        // 现在用同样的 1 tick 插值走完最后一段，客户端把上一帧的插值自然
                        // 接到终点，没有突变。终点值本身是精确的 target 与 baseTransformation，
                        // 所以不会留下浮点偏移。
                        display.teleport(target)
                        applyInterpolated(display, baseTransformation(), FRAME_INTERPOLATION_TICKS)
                        finishAnimation(display, token)
                        cancel()
                        return
                    }

                    display.teleport(frameTarget)
                    applyInterpolated(
                        display,
                        moveTransformation(frame.height, frame.scaleFactor * baseScale),
                        FRAME_INTERPOLATION_TICKS,
                    )
                } catch (error: Throwable) {
                    warning(
                        "棋子走子动画异常，已清理动画锁：target=$targetSquare，" +
                            "${error.javaClass.simpleName} ${error.message}",
                    )
                    finishAnimation(display, token)
                    cancel()
                }
            }
        }.runTaskTimer(BukkitPlugin.getInstance(), 1L, 1L)
    }

    /**
     * 走子：按 `piece.move-ticks` 配的总时长走一条连续弧线。
     *
     * [alreadyLifted] 为 true 表示玩家已先选中这枚子，弧线从当前悬空高度接上，
     * 不会先掉回棋盘；机器人等未预先选中的棋子从棋盘面起弧。
     *
     * 渲染索引在成功走子时立即从起点迁到终点，被吃实体也立即移除；BukkitRunnable
     * 只刷新视觉帧。每 tick 都会校验实体、目标格和动画代次，旧回调不能覆盖新局面。
     *
     * @return 落地所需的 tick 数。调用方（[linmumua.chess.interact.MoveExecutor]）用它
     *   排落子音效与终局播报，所以必须返回按配置算出的总时长而不是常量，否则会错拍。
     *   动画关闭时返回 0，保持"立即结算"的既有行为。
     */
    /**
     * 落一枚新子：五子棋与围棋的落子表现。
     *
     * 与 [applyMove] 是两件事：走子是把已有实体从起点搬到终点，
     * 落子是**凭空生成**一枚。所以不能复用 applyMove ——
     * 那边第一步就是 `pieces.remove(from)`，落子式的起点上本来没有实体，
     * 会直接 return 0，棋子永远不出现。
     *
     * 表现是从空中落到棋盘面：起始帧抬到 [linmumua.chess.config.PieceSettings.arcHeight]
     * 的高度并略微放大，然后插值回常态。没有"抬起"那一段，因为落子式没有选中过程。
     *
     * @return 落地所需的 tick 数，调用方用它排音效与终局播报
     */
    fun applyPlacement(square: Square, piece: Piece): Int {
        clearSelectionGlows()
        // 该点若还残留实体（局面不同步），先清掉再生成，避免两枚叠在一起
        spawn(square, piece)
        val display = pieces[square.packed] ?: return 0

        val ticks = settings.piece.animationTicks
        if (ticks <= 0) {
            // 动画关掉时 spawn 出来就已经是常态帧，不用再动
            return 0
        }

        val token = beginAnimation(display)
        // 起始帧：悬在走子弧线的最高处并略微放大，视觉上是"从手里放下来"
        applyInterpolated(
            display,
            moveTransformation(settings.piece.moveLiftHeight, pieceSelectedScale),
            0,
        )

        // 下一 tick 起插值回常态，高度与缩放一起收敛。
        // 用 BukkitRunnable 与走子动画保持一致，不另引一套调度。
        object : BukkitRunnable() {
            private var started = false

            override fun run() {
                if (!isCurrentAnimation(display, square, token)) {
                    finishAnimation(display, token)
                    cancel()
                    return
                }
                if (!started) {
                    started = true
                    applyInterpolated(display, baseTransformation(), ticks)
                    return
                }
                finishAnimation(display, token)
                cancel()
            }
        }.runTaskTimer(BukkitPlugin.getInstance(), 1L, ticks.toLong().coerceAtLeast(1L))

        return ticks + 1
    }

    fun applyMove(from: Square, to: Square, alreadyLifted: Boolean = false): Int {
        // 走子前先清描边，避免被吃实体或移动后的实体把旧选中颜色带到新局面。
        clearSelectionGlows()
        val display = pieces.remove(from.packed) ?: return 0
        val selectedRaised = alreadyLifted || hovered == from
        val animationInProgress = animationTokens.containsKey(display.uniqueId)

        // 终点原有的子是被吃掉的，实体与未完成动画都要立即销毁。
        pieces.remove(to.packed)?.let { captured ->
            animationTokens.remove(captured.uniqueId)
            captured.remove()
        }
        // 字面方向已经固定在这枚显示物品的模型里，走子只迁移实体索引。
        pieces[to.packed] = display
        if (hovered == from || hovered == to) {
            hovered = null
        }

        val target = anchor.locationOf(to)
        if (settings.piece.animationTicks <= 0) {
            invalidateAnimation(display)
            display.teleportDuration = 0
            display.teleport(target)
            applyInterpolated(display, baseTransformation(), 0)
            return 0
        }

        val token = beginAnimation(display)
        val startsRaised = selectedRaised || animationInProgress
        // 玩家已选中时从现有悬空高度与放大尺寸接上，避免抬手前先跳变一次。
        val startHeight = if (startsRaised) settings.piece.hoverHeight else 0.0
        val startScaleFactor = if (startsRaised) {
            pieceSelectedScale / pieceBaseScale
        } else {
            1.0
        }
        startMoveArc(display, to, target, startHeight, startScaleFactor, token)
        return settings.piece.moveTicks
    }

    /**
     * 按局面校正一遍索引。
     *
     * 用于从存档恢复或发现渲染与局面不一致时兜底重建。
     * 不做增量 diff，直接重铺 —— 32 枚实体的重建开销远小于维护 diff 的复杂度，
     * 而且这条路径只在异常或恢复时走，不是每步棋都走。
     */
    fun resync(board: Board) {
        renderAll(board)
    }

    private companion object {
        /**
         * 棋子模型本体厚度，单位方块（模型里 z 7.5..8.5 共 1 单位，1 单位 = 1/16 格）。
         *
         * 必须与 buildSrc 的 `PackWriter.writePieceModels` 里那个 element 的 z 跨度一致。
         * 这里不能直接引用 `PackSpec`：那是构建期 buildSrc 的类，不在插件运行期 classpath 上。
         */
        const val PIECE_MODEL_THICKNESS = 1.0 / 16.0

        /**
         * 模型 `display.fixed` 里的厚度轴缩放。
         *
         * 必须与 `PackSpec.PIECE_FIXED_DEPTH_SCALE` 保持一致，改一处要同时改另一处。
         * 两边对不上会让棋子底面浮在板面上方或切进板子里。
         */
        const val PIECE_FIXED_DEPTH_SCALE = 7.0

        /** 每 tick 给客户端一段短插值，避免逐帧更新 Transformation 时出现台阶感 */
        const val FRAME_INTERPOLATION_TICKS = 1

        /** 自己选中的棋子用橙色描边 */
        val SELECTED_GLOW: Color = Color.fromRGB(0xF5, 0x9E, 0x0B)

        /** 可以直接吃掉的敌方棋子用红色描边 */
        val CAPTURE_GLOW: Color = Color.fromRGB(0xEF, 0x44, 0x44)
    }
}

/**
 * 一帧的手部姿态。
 *
 * @param planarProgress 水平进度，0 在起点、1 在终点，全程单调不回退
 * @param height 相对棋盘面的高度，全程非负（棋盘薄板有 0.125 厚度，负值会穿模）
 * @param scaleFactor 相对 `piece.base-scale` 的缩放倍数，1.0 表示基础大小
 */
internal data class HandFrame(
    val planarProgress: Double,
    val height: Double,
    val scaleFactor: Double,
)

/**
 * 算出走子第 [tick] 帧的姿态。
 *
 * 抬起与落下不再是两段独立动作，而是**一条连续弧线**：棋子一边平移一边升到最高点，
 * 再一边平移一边落回棋盘面，整个过程没有"停在空中"的中间段。
 * 总时长很短（默认 4 tick，0.2 秒），分段反而会让每段只剩一两帧，看起来像卡顿。
 *
 * - 水平：ease-in-out（慢-快-慢），起步和收尾都柔和，不会像匀速那样显得机械。
 * - 垂直：以中点为顶的抛物线，起点接当前高度，终点精确回到棋盘面。
 * - 缩放：跟着高度走，抬得越高放得越大，落地回到基础尺寸。
 *
 * @param startHeight 起始高度。玩家已选中的棋子从当前悬空高度接上弧线，
 *   不会先掉回棋盘面；机器人走子传 0，从棋盘面起弧。
 * @param startScaleFactor 起始缩放倍数，已选中的棋子传选中倍数以避免跳变
 * @param selectedScaleFactor 弧线最高点的缩放倍数
 */
internal fun handMoveFrame(
    tick: Int,
    totalTicks: Int,
    liftHeight: Double,
    startHeight: Double,
    startScaleFactor: Double,
    selectedScaleFactor: Double,
): HandFrame {
    val total = totalTicks.coerceAtLeast(2)
    val clamped = tick.coerceIn(0, total)
    // 落地帧必须精确归位，不能让浮点误差留下零点几毫米的悬空或穿模
    if (clamped >= total) {
        return HandFrame(1.0, 0.0, 1.0)
    }

    val t = clamped.toDouble() / total
    // 归一化的弧线高度：t=0 时 0，t=0.5 时 1，t=1 时 0
    val arc = 4.0 * t * (1.0 - t)
    // 起始高度按剩余行程线性收掉，保证已浮起的棋子不会在弧线结束前提前贴地
    val carried = startHeight * (1.0 - t)

    return HandFrame(
        planarProgress = easeInOut(t),
        // 两项相加仍然只在 t=1 归零，中途单调升到顶再单调落下，全程非负
        height = carried + (liftHeight - startHeight).coerceAtLeast(0.0) * arc,
        // 缩放跟着弧线走：先线性收回基础尺寸打底，再叠一个与高度同相的凸起，
        // 于是抬得最高时最大、落地正好回到基础尺寸
        scaleFactor = startScaleFactor + (1.0 - startScaleFactor) * t +
            (selectedScaleFactor - 1.0) * arc,
    )
}

/**
 * 慢-快-慢的平移曲线。
 *
 * 两端各用一段二次曲线拼成：起步慢、中段最快、临近终点再慢下来。
 * 这条曲线单调递增且钳制在 0..1，棋子不会倒退也不会冲过目标格。
 */
private fun easeInOut(progress: Double): Double {
    val t = progress.coerceIn(0.0, 1.0)
    return if (t <= 0.5) {
        2.0 * t * t
    } else {
        1.0 - 2.0 * (1.0 - t).pow(2.0)
    }
}
