package linmumua.chess.render

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import linmumua.chess.board.PieceOrientation
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 棋子厚度测试。
 *
 * 棋子看着像一张纸有两个独立原因，这里各钉一条：
 *
 * 1. 厚度本身太小。侧壁补上了也没用，0.04 格从低角度看只有一两个像素。
 * 2. 棋子几何中心压在棋盘面上，一半厚度埋在薄板里，实际只露出半个厚度。
 *
 * 更要紧的是：渲染层算抬升量用的两个常量必须和构建期生成的模型 JSON 一致，
 * 而它们分处 `src/main` 与 `buildSrc` 两个 classpath，编译器管不到。
 * 这里直接读生成物对账，对不上就说明有人只改了一边。
 */
class PieceThicknessTest {

    /** 在两个目标的产物目录里找同一个生成文件；内容一致，取先找到的那个 */
    private fun generated(relative: String): String {
        val candidates = listOf("paper-26.2", "paper-26.1.2").map { target ->
            File("build/$target/generated/craftengine/muchess/resourcepack/assets/muchess/models/$relative")
        }
        val found = candidates.firstOrNull { it.isFile }
        requireNotNull(found) { "读不到生成的模型，先跑一次构建：${candidates.map { it.path }}" }
        return found.readText(Charsets.UTF_8)
    }

    /**
     * 共享基础模型，element 的几何尺寸在这里。
     *
     * 注意它的 `display.fixed.scale` 是 `[1,1,1]`，厚度缩放不在这个文件里。
     */
    private val baseModel: String by lazy { generated("item/piece/xiangqi_piece_base.json") }

    /**
     * 朝向父模型，`display.fixed` 的厚度缩放写在这里。
     *
     * 八个朝向父模型（红黑 × 四向）的 scale 完全相同，只有 rotation 不同，
     * 所以取任意一个都能验证厚度。
     */
    private val displayBaseModel: String by lazy {
        generated("item/piece/xiangqi_piece_red_north_display_base.json")
    }

    /** 从模型 JSON 里读圆盘 element 的 z 跨度，单位模型单位 */
    private fun modelThicknessUnits(): Double {
        val from = Regex("\"from\": \\[0, 0, ([\\d.]+)\\]").find(baseModel)
        val to = Regex("\"to\": \\[16, 16, ([\\d.]+)\\]").find(baseModel)
        requireNotNull(from) { "模型里找不到圆盘 element 的 from" }
        requireNotNull(to) { "模型里找不到圆盘 element 的 to" }
        return to.groupValues[1].toDouble() - from.groupValues[1].toDouble()
    }

    /** 从朝向父模型里读 display.fixed 的厚度轴缩放（scale 的第三个分量） */
    private fun fixedDepthScale(): Double {
        val match = Regex("\"scale\": \\[[\\d.]+, [\\d.]+, ([\\d.]+)\\]").find(displayBaseModel)
        requireNotNull(match) { "朝向父模型里找不到 display.fixed 的 scale" }
        return match.groupValues[1].toDouble()
    }

    /**
     * 渲染层写死的两个常量必须与生成的模型一致。
     *
     * 这是本组最重要的一条。抬升量 = 模型厚度 × 深度缩放 × 当帧缩放 / 2，
     * 任何一个因子跟模型对不上，棋子就会浮在板面上方或者切进板子里。
     * 常量在 `src/main`、模型在 `buildSrc`，只能靠这条测试兜。
     */
    @Test
    @DisplayName("渲染层的厚度常量与生成的模型一致")
    fun `renderer constants match the generated model`() {
        assertEquals(
            modelThicknessUnits() / 16.0,
            RENDERER_MODEL_THICKNESS,
            1.0e-9,
            "BoardRenderer.PIECE_MODEL_THICKNESS 与模型里 element 的 z 跨度不一致",
        )
        assertEquals(
            fixedDepthScale(),
            RENDERER_FIXED_DEPTH_SCALE,
            1.0e-9,
            "BoardRenderer.PIECE_FIXED_DEPTH_SCALE 与 PackSpec 里写进模型的值不一致",
        )
    }

    /**
     * 厚度必须能被看见。
     *
     * 之前是 0.0425 格，再埋掉一半只剩 0.021 格，也就是两厘米，从低角度看就是一张纸。
     * 这里要求世界厚度至少占棋子可见直径的三成，才谈得上有立体感。
     */
    @Test
    @DisplayName("世界厚度占直径的比例足够看出立体感")
    fun `thickness is a visible fraction of the diameter`() {
        val thickness = RENDERER_MODEL_THICKNESS * RENDERER_FIXED_DEPTH_SCALE * BASE_SCALE
        // 贴图圆盘占 92%，可见直径按这个折算
        val diameter = BASE_SCALE * 0.92
        val ratio = thickness / diameter

        assertTrue(ratio >= 0.3, "厚度只占直径的 ${"%.0f".format(ratio * 100)}%，太薄看不出立体")
        assertTrue(ratio <= 0.7, "厚度占到直径的 ${"%.0f".format(ratio * 100)}%，棋子会变成柱子")
    }

    /**
     * 棋子底面必须精确落在棋盘薄板顶面上。
     *
     * 抬升量 = 半个厚度 − surface-offset。半个厚度把几何中心抬到底面齐平；
     * 再扣掉 surface-offset 是因为 `anchor.surfaceY` 已经含了那个偏移 ——
     * 它本是给平面贴图防 z-fighting 用的，棋子是实体盒不需要，留着就悬空一线。
     */
    @Test
    @DisplayName("棋子底面精确贴住棋盘薄板顶面")
    fun `piece bottom rests exactly on the board plate`() {
        val originY = 64.0
        val plateTop = originY + BOARD_THICKNESS
        val surfaceY = plateTop + SURFACE_OFFSET

        for (scale in listOf(BASE_SCALE, SELECTED_SCALE, 0.3)) {
            val thickness = RENDERER_MODEL_THICKNESS * RENDERER_FIXED_DEPTH_SCALE * scale
            val lift = thickness / 2.0 - SURFACE_OFFSET
            val bottom = surfaceY + lift - thickness / 2.0
            assertEquals(
                plateTop,
                bottom,
                1.0e-9,
                "缩放 $scale 下棋子底面没贴住板顶，差 ${bottom - plateTop} 格",
            )
        }
    }

    /**
     * surface-offset 调大也不能把棋子顶起来。
     *
     * 服主为了防别处的 z-fighting 调大这个值时，棋子底面仍应贴住板顶 ——
     * 抬升量里已经把它扣掉了，两者应当互相抵消。
     */
    @Test
    @DisplayName("调大 surface-offset 棋子仍然贴住板面")
    fun `larger surface offset does not lift the piece`() {
        val originY = 64.0
        val plateTop = originY + BOARD_THICKNESS
        val thickness = RENDERER_MODEL_THICKNESS * RENDERER_FIXED_DEPTH_SCALE * BASE_SCALE

        for (offset in listOf(0.0, 0.02, 0.05)) {
            val surfaceY = plateTop + offset
            val bottom = surfaceY + (thickness / 2.0 - offset) - thickness / 2.0
            assertEquals(plateTop, bottom, 1.0e-9, "surface-offset=$offset 时棋子没贴住板顶")
        }
    }

    /**
     * 抬升量必须随缩放变化。
     *
     * 浮起时棋子放大，厚度也跟着变大。抬升量若写成常量，放大的瞬间底面会切进板子。
     */
    @Test
    @DisplayName("抬升量随缩放变化")
    fun `lift scales with the piece`() {
        val atBase = RENDERER_MODEL_THICKNESS * RENDERER_FIXED_DEPTH_SCALE * BASE_SCALE / 2.0
        val atSelected = RENDERER_MODEL_THICKNESS * RENDERER_FIXED_DEPTH_SCALE * SELECTED_SCALE / 2.0

        assertTrue(atSelected > atBase, "选中放大后抬升量必须更大，否则底面会切进棋盘")
    }

    /**
     * 收尾帧不能硬切上一帧的插值。
     *
     * 中间帧每帧下发 1 tick 的位移与变换插值。原来的收尾帧把 teleportDuration 与
     * interpolationDuration 一起设成 0 再硬跳终点，客户端上一帧的插值才走到一半就被
     * 掐断 —— 落地那一下明显一顿并且闪。收尾帧必须沿用同一段插值时长走完。
     *
     * 直接读源码断言，因为这是"两次下发之间的时序",没有实例化 ItemDisplay 就测不到；
     * 项目没有 mock 库，读源码是这里唯一能守住它的办法。
     */
    @Test
    @DisplayName("收尾帧沿用同一段插值，不硬切")
    fun `final frame keeps the interpolation duration`() {
        val source = File("src/main/kotlin/linmumua/chess/render/BoardRenderer.kt")
            .readText(Charsets.UTF_8)
        val finalFrame = source.substringAfter("if (elapsedTicks >= totalTicks) {")
            .substringBefore("finishAnimation(display, token)")

        assertTrue(
            finalFrame.contains("applyInterpolated(display, baseTransformation(), FRAME_INTERPOLATION_TICKS)"),
            "收尾帧必须用 FRAME_INTERPOLATION_TICKS 走完最后一段，不能传 0 硬切",
        )
        assertTrue(
            !finalFrame.contains("teleportDuration = 0"),
            "收尾帧不能把 teleportDuration 设成 0，那会掐断上一帧的位移插值造成落地闪烁",
        )
    }

    /**
     * 反向 U 必须给玩家实际看到的那一面。
     *
     * 两面 UV 相反是为了不互为镜像，但**翻哪一面**取决于铺平后哪面朝天：
     * 渲染层绕 X 轴 +90° 把 north 的法线 −Z 转到 +Y，所以棋盘上朝天的是 north。
     *
     * 这里同时钉住"north 反向"和"south 正向"。只断言"两面相反"是不够的 ——
     * 那样把反向 U 给 south 也能通过，而那面平时压在棋子底下根本看不见，
     * 棋盘上的字照旧是反的。这个错误真实发生过一次。
     */
    @Test
    @DisplayName("反向 U 给朝天的 north，不是看不见的 south")
    fun `flipped u goes to the visible north face`() {
        assertTrue(
            baseModel.contains("\"north\": {\"uv\": [16, 0, 0, 16]"),
            "north 是铺平后朝天的面，它的 U 必须反向，否则棋盘上汉字左右翻",
        )
        assertTrue(
            baseModel.contains("\"south\": {\"uv\": [0, 0, 16, 16]"),
            "south 应保持正向，与 north 互为镜像，图腾转到背面时字才是正的",
        )
        // 铺平角度必须仍是 +90°：换成 -90° 会让 south 朝天，上面两条就全反了
        assertEquals(
            90f,
            PieceOrientation.FLAT_PITCH_DEGREES,
            "铺平角度变了的话，朝天的面会换成 south，反向 U 就给错了面",
        )
    }

    /**
     * 侧壁必须是八块拼的正十六边形，且顶点落在圆盘半径上。
     *
     * 单块内接正方形的边整体缩在圆内，低角度看侧壁轮廓是方的；八边形的平边仍比
     * 外接圆内缩 7.6%，能看出八条直边。十六边形把内缩压到 1.9%。
     */
    @Test
    @DisplayName("侧壁是十六边形且顶点贴合圆盘")
    fun `side wall is a hexadecagon matching the disc`() {
        val wallFaces = baseModel.split("\"texture\": \"#side\"").size - 1
        assertEquals(32, wallFaces, "侧壁应为 8 块 × 4 面")
        assertTrue(baseModel.contains("\"angle\": 45"), "缺少 45° 分片，拼不出十六边形")
        // ±22.5° 是十六边形比八边形多出来的四个朝向，缺一个就退回八边形
        assertTrue(baseModel.contains("\"angle\": 22.5"), "缺少 +22.5° 分片，会退回八边形")
        assertTrue(baseModel.contains("\"angle\": -22.5"), "缺少 -22.5° 分片，会退回八边形")

        val vertex = Math.hypot(HEXDEC_LONG, HEXDEC_SHORT)
        assertEquals(DISC_RADIUS, vertex, 0.01, "十六边形顶点没落在圆盘半径上")
    }

    private companion object {
        /** 与 BoardRenderer.PIECE_MODEL_THICKNESS 保持一致 */
        const val RENDERER_MODEL_THICKNESS = 1.0 / 16.0

        /** 与 BoardRenderer.PIECE_FIXED_DEPTH_SCALE 保持一致 */
        const val RENDERER_FIXED_DEPTH_SCALE = 7.0

        /** 与 config.yml 的 piece.base-scale 默认值一致 */
        const val BASE_SCALE = 0.17

        /** 与 config.yml 的 piece.selected-scale 默认值一致 */
        const val SELECTED_SCALE = 0.21

        /** 与 BoardAnchor.BOARD_THICKNESS 一致 */
        const val BOARD_THICKNESS = 0.125

        /** 与 config.yml 的 board.surface-offset 默认值一致 */
        const val SURFACE_OFFSET = 0.02

        /** 与 PackSpec.PIECE_DISC_RADIUS 一致 */
        const val DISC_RADIUS = 7.36

        /** 与 PackSpec.PIECE_HEXDEC_LONG 一致，`R·cos(11.25°)` */
        const val HEXDEC_LONG = 7.2186

        /** 与 PackSpec.PIECE_HEXDEC_SHORT 一致，`R·sin(11.25°)` */
        const val HEXDEC_SHORT = 1.4359
    }
}
