package linmumua.chess.render

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 走子弧线测试。
 *
 * 走子是**一条连续弧线**：平移与升降同时进行，中途不停顿，全程 2 tick（0.1 秒）。
 * 这里钉住"连续"这件事本身 —— 一旦有人又把它拆成抬起/平移/落下三段，
 * 或者让某一段水平不动，这些用例就会失败。
 *
 * 2 tick 是加载器允许的下限，中途只有 1 帧。这是最紧的情形：弧线的顶点、
 * 缩放的峰值、水平的中段全都压在那一帧上，任何"只在多帧下才成立"的写法都会在这里露出来。
 */
class BoardRendererAnimationTest {

    private val totalTicks = 2
    private val liftHeight = 0.12
    private val selectedScaleFactor = 0.21 / 0.17

    /** 取一帧，默认是机器人走子（从棋盘面起弧、基础缩放） */
    private fun frame(
        tick: Int,
        startHeight: Double = 0.0,
        startScaleFactor: Double = 1.0,
    ): HandFrame = handMoveFrame(
        tick = tick,
        totalTicks = totalTicks,
        liftHeight = liftHeight,
        startHeight = startHeight,
        startScaleFactor = startScaleFactor,
        selectedScaleFactor = selectedScaleFactor,
    )

    /**
     * 总时长必须是 2 tick，也就是 0.1 秒。
     *
     * 这是"抬起 → 飞过去 → 落下"的整段时长，同时是输入锁定时长，
     * 改长了会明显影响连续落子的手感。
     */
    @Test
    @DisplayName("走子总时长是 2 tick")
    fun `move finishes in two ticks`() {
        assertEquals(2, totalTicks)
        assertEquals(0.0, frame(totalTicks).height, 1.0e-9, "第 2 帧必须已经落地")
        assertEquals(1.0, frame(totalTicks).planarProgress, 1.0e-9, "第 2 帧必须已经到位")
    }

    /**
     * 升降与平移必须同时发生。
     *
     * 这是"连续弧线"与旧三段动画的根本区别：旧实现在抬起段水平进度恒为 0，
     * 现在只要棋子离开了棋盘面，水平就必须已经在动。
     */
    @Test
    @DisplayName("棋子一离开棋盘面水平就在动，没有原地抬起段")
    fun `vertical and planar motion happen together`() {
        for (tick in 1 until totalTicks) {
            val f = frame(tick)
            assertTrue(f.height > 0.0, "第 $tick 帧应该在空中")
            assertTrue(f.planarProgress > 0.0, "第 $tick 帧在空中却没有水平位移，说明又分段了")
            assertTrue(f.planarProgress < 1.0, "第 $tick 帧还在空中就已经到位，说明落下段被单独拆出来了")
        }
    }

    /**
     * 高度是一条以中点为顶的弧线：先升后降，起点终点都在棋盘面。
     */
    @Test
    @DisplayName("高度先升后降，顶点在中途")
    fun `height rises then falls in one arc`() {
        val heights = (0..totalTicks).map { frame(it).height }

        assertEquals(0.0, heights.first(), 1.0e-9, "起点在棋盘面")
        assertEquals(0.0, heights.last(), 1.0e-9, "终点回到棋盘面")

        val peakIndex = heights.indexOf(heights.max())
        assertTrue(peakIndex in 1 until totalTicks, "顶点必须在中途，不能在两端")
        // 顶点之前单调升，之后单调降，中间不能有第二个波峰
        assertTrue(
            heights.take(peakIndex + 1).zipWithNext().all { (a, b) -> b >= a },
            "顶点之前必须单调上升",
        )
        assertTrue(
            heights.drop(peakIndex).zipWithNext().all { (a, b) -> b <= a },
            "顶点之后必须单调下降",
        )
    }

    /**
     * 棋盘面下方是 0.125 厚的实心薄板（BoardAnchor.BOARD_THICKNESS）。
     * 高度取负会让棋子沉进板子里穿模。
     */
    @Test
    @DisplayName("高度全程不为负，不能沉进棋盘薄板")
    fun `height never goes below the board surface`() {
        for (tick in -2..totalTicks + 2) {
            assertTrue(frame(tick).height >= 0.0, "第 $tick 帧高度不能为负")
        }
    }

    /**
     * 玩家先选中的棋子已经浮在空中，弧线要从当前高度接上，
     * 否则会先"掉"回棋盘再起弧，看起来像卡了一下。
     */
    @Test
    @DisplayName("已浮起的棋子从当前高度接上弧线")
    fun `selected piece continues from hover height`() {
        val hoverHeight = 0.12
        val first = frame(0, startHeight = hoverHeight, startScaleFactor = selectedScaleFactor)

        assertEquals(hoverHeight, first.height, 1.0e-9, "起手高度必须等于当前悬空高度")
        assertEquals(
            selectedScaleFactor,
            first.scaleFactor,
            1.0e-9,
            "起手缩放必须等于选中时的放大倍数，否则会跳变一下",
        )
        // 全程仍然不能出现负高度，且最后精确落地
        for (tick in 0..totalTicks) {
            assertTrue(frame(tick, hoverHeight, selectedScaleFactor).height >= 0.0)
        }
        assertEquals(0.0, frame(totalTicks, hoverHeight, selectedScaleFactor).height, 1.0e-9)
    }

    /**
     * 缩放跟着高度走：抬得最高时最大，落地回到基础尺寸。
     * 落地缩放不精确回到 1.0 会让棋子留在放大状态，和邻格压边。
     */
    @Test
    @DisplayName("缩放跟着弧线起落，落地回到基础尺寸")
    fun `scale follows the arc and returns to base`() {
        assertEquals(1.0, frame(0).scaleFactor, 1.0e-9, "机器人起手就是基础尺寸")
        assertEquals(1.0, frame(totalTicks).scaleFactor, 1.0e-9, "落地必须精确回到基础尺寸")

        val peak = (1 until totalTicks).maxOf { frame(it).scaleFactor }
        assertTrue(peak > 1.0, "中途必须放大，否则弧线看不出高低")
        assertTrue(peak <= selectedScaleFactor + 1.0e-9, "放大不该超过选中尺寸")
    }

    /**
     * 平移单调不回退，且两端精确对齐、越界被钳住。
     *
     * 调度抖动或配置改动不能让棋子倒退、冲过目标格。这几条与总时长无关，
     * 默认的 2 tick 下同样必须成立。
     */
    @Test
    @DisplayName("平移单调不回退，越界被钳住")
    fun `planar progress never reverses and clamps`() {
        val progresses = (0..totalTicks).map { frame(it).planarProgress }
        val steps = progresses.zipWithNext { a, b -> b - a }

        assertEquals(0.0, progresses.first(), 1.0e-9)
        assertEquals(1.0, progresses.last(), 1.0e-9)
        assertTrue(progresses.all { it in 0.0..1.0 }, "进度必须始终落在 0..1")
        assertTrue(steps.all { it >= 0.0 }, "进度必须单调不回退")

        assertEquals(0.0, frame(-5).planarProgress, 1.0e-9, "负 tick 应钳到起点")
        assertEquals(1.0, frame(totalTicks + 5).planarProgress, 1.0e-9, "超出总时长应钳到终点")
    }

    /**
     * 平移是慢-快-慢。
     *
     * 这条只在总时长 ≥ 4 时才有意义：需要至少 3 个步长才谈得上"中段快于两端"。
     * 默认的 2 tick 只有 2 个步长，缓动在那里退化成两个各占一半的等长步
     * （进度 0 → 0.5 → 1），不存在中段 —— 拿它去断言"中段更快"必然失败，
     * 那是断言本身越界，不是缓动写错了。
     */
    @Test
    @DisplayName("时长够长时平移呈慢快慢")
    fun `planar progress eases when there is room for it`() {
        for (total in listOf(4, 6, 20)) {
            val progresses = (0..total).map { tick ->
                handMoveFrame(
                    tick = tick,
                    totalTicks = total,
                    liftHeight = liftHeight,
                    startHeight = 0.0,
                    startScaleFactor = 1.0,
                    selectedScaleFactor = selectedScaleFactor,
                ).planarProgress
            }
            val steps = progresses.zipWithNext { a, b -> b - a }
            val middle = steps[steps.size / 2]

            assertTrue(middle > steps.first(), "总时长 $total 的中段位移应大于起步帧")
            assertTrue(middle > steps.last(), "总时长 $total 的中段位移应大于收尾帧")
        }
    }

    /**
     * 默认 2 tick 下两个半程等长且各占一半。
     *
     * 这是缓动在下限处的实际形态。写成用例是为了说明"这里没有慢快慢"是已知且刻意的，
     * 不是哪天被改坏的 —— 同时钉住中间那一帧正好在半程，别偏到某一侧。
     */
    @Test
    @DisplayName("2 tick 下两个半程各占一半")
    fun `two tick move splits evenly`() {
        assertEquals(2, totalTicks, "这条用例专门描述下限处的形态")
        assertEquals(0.5, frame(1).planarProgress, 1.0e-9, "中间那一帧应正好在半程")

        val steps = (0..totalTicks).map { frame(it).planarProgress }
            .zipWithNext { a, b -> b - a }
        assertEquals(steps[0], steps[1], 1.0e-9, "两个半程应等长")
    }

    /**
     * 服主把总时长改掉之后，弧线的这些性质仍要成立。
     */
    @Test
    @DisplayName("换成自定义总时长后弧线性质不变")
    fun `arc holds for custom durations`() {
        // 2 是下限、4 是旧默认、20 是明显长的配置，三档都要成立
        for (total in listOf(2, 4, 6, 20)) {
            val samples = (0..total).map { tick ->
                handMoveFrame(
                    tick = tick,
                    totalTicks = total,
                    liftHeight = liftHeight,
                    startHeight = 0.0,
                    startScaleFactor = 1.0,
                    selectedScaleFactor = selectedScaleFactor,
                )
            }
            assertEquals(0.0, samples.first().height, 1.0e-9, "总时长 $total 的起点应在棋盘面")
            assertEquals(0.0, samples.last().height, 1.0e-9, "总时长 $total 的终点应回到棋盘面")
            assertEquals(1.0, samples.last().planarProgress, 1.0e-9, "总时长 $total 的终点应到位")
            assertEquals(1.0, samples.last().scaleFactor, 1.0e-9, "总时长 $total 的终点应回到基础尺寸")
            assertTrue(samples.all { it.height >= 0.0 }, "总时长 $total 出现了负高度")
            assertTrue(
                samples.map { it.planarProgress }.zipWithNext().all { (a, b) -> b >= a },
                "总时长 $total 的水平进度出现回退",
            )
        }
    }
}
