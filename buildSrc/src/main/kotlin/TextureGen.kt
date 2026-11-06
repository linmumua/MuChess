import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * 棋盘与棋子贴图的程序化生成。
 *
 * 不从网上抓图：来源不明的图片授权说不清楚，而程序化生成是确定的、可复现的，
 * 换台机器构建出来的贴图完全一致。服主要换成自己的美术资源，直接覆盖
 * `plugins/CraftEngine/resources/muchess/resourcepack/` 下的同名 PNG 即可。
 *
 * 中文字形依赖系统 CJK 字体，优先楷体（象棋惯用字体），缺失时逐级回退。
 */
object TextureGen {

    /** 棋子贴图边长 */
    const val PIECE_SIZE = 128

    /** 空合法落点白圈贴图边长 */
    const val MOVE_MARKER_SIZE = 128

    /**
     * 棋子侧壁贴图边长。
     *
     * 纯色贴图不需要分辨率，但原版要求贴图边长是 2 的幂且不小于 16。
     * 取 16 就够，省下 128×128 那点体积也没意义，主要是避免误以为这里有细节。
     */
    const val PIECE_SIDE_SIZE = 16

    /** 白圈可见外径占贴图边长的比例；保留透明边距，让落点提示略小于棋子 */
    const val MOVE_MARKER_OUTER_DIAMETER_RATIO = 0.84

    /** 白圈透明内孔占贴图边长的比例；较大的内孔让圆环保持轻薄 */
    const val MOVE_MARKER_INNER_DIAMETER_RATIO = 0.64

    /** 棋盘贴图边长，2x2 家具顶面要保持清晰所以给到 1024 */
    const val BOARD_SIZE = 1024

    /** 炮位角标离交叉点的距离，占较短格距的比例 */
    const val CANNON_MARK_OFFSET_RATIO = 0.16

    /** 炮位角标每条短边长度，占较短格距的比例 */
    const val CANNON_MARK_ARM_RATIO = 0.18

    /**
     * “楚河”面向黑方。
     *
     * 贴图 Y 轴在贴到世界棋盘面上时是翻过来的（模型 UV + 家具 yaw 共同作用），
     * 所以图像空间的 0 度落到世界里是背对 rank 0 那一侧。黑方底线在 rank 0，
     * 因此楚河要用 0 度才朝黑方，不能按"图像里正着写就是朝红方"来推。
     */
    const val CHU_RIVER_ROTATION_DEGREES = 0.0

    /**
     * “漢界”面向红方。
     *
     * 同上，红方底线在 rank 9，需要相对图像默认朝向转半圈才真正朝红方。
     */
    const val HAN_RIVER_ROTATION_DEGREES = 180.0

    /** 候选字体，按优先级排列。楷体是象棋棋子的传统字体 */
    private val FONT_CANDIDATES = listOf(
        "C:/Windows/Fonts/simkai.ttf",
        "C:/Windows/Fonts/simhei.ttf",
        "C:/Windows/Fonts/simsun.ttc",
        "C:/Windows/Fonts/msyh.ttc",
        "/usr/share/fonts/truetype/arphic/ukai.ttc",
        "/System/Library/Fonts/Songti.ttc",
    )

    /** 棋子木质底色 */
    private val DISC_LIGHT = Color(0xF2, 0xDE, 0xB0)
    private val DISC_DARK = Color(0xD9, 0xBE, 0x86)
    private val DISC_EDGE = Color(0x8B, 0x6B, 0x3D)
    private val RED_INK = Color(0xB3, 0x1E, 0x1E)
    private val BLACK_INK = Color(0x1F, 0x1F, 0x24)

    /** 棋盘底色与线色 */
    private val BOARD_BG = Color(0xE8, 0xD2, 0xA0)
    private val BOARD_LINE = Color(0x5A, 0x42, 0x22)

    /**
     * 五子棋与围棋的棋子颜色。
     *
     * 这两个棋种的子是没有字的素色圆盘，靠黑白区分阵营，所以不复用象棋那套木质底色 ——
     * 木底加黑字与"黑子"在远处看很容易混。
     * 黑子不用纯黑：纯黑在棋盘暗线上会糊成一片，留一点亮度才看得出是一枚子。
     */
    private val STONE_BLACK_LIGHT = Color(0x5A, 0x5A, 0x62)
    private val STONE_BLACK_DARK = Color(0x1A, 0x1A, 0x1F)
    private val STONE_BLACK_EDGE = Color(0x0D, 0x0D, 0x10)
    private val STONE_WHITE_LIGHT = Color(0xFF, 0xFF, 0xFF)
    private val STONE_WHITE_DARK = Color(0xD2, 0xD2, 0xD8)
    private val STONE_WHITE_EDGE = Color(0x8A, 0x8A, 0x92)

    /**
     * 加载 CJK 字体。
     *
     * 找不到任何候选字体时返回 null，调用方退化为不画字，
     * 让构建仍然能过 —— 构建机器上没装中文字体不该导致整个项目构建失败。
     */
    private fun loadFont(size: Float): Font? {
        for (path in FONT_CANDIDATES) {
            val file = File(path)
            if (!file.isFile) {
                continue
            }
            try {
                return Font.createFont(Font.TRUETYPE_FONT, file).deriveFont(Font.BOLD, size)
            } catch (error: Exception) {
                // 字体文件存在但解析失败（例如 ttc 里没有可用字体），换下一个候选
                println("[MuChess] 字体加载失败，跳过 $path: ${error.message}")
            }
        }
        println("[MuChess] 未找到可用的中文字体，棋子贴图将不带字形")
        return null
    }

    private fun newCanvas(size: Int): Pair<BufferedImage, Graphics2D> {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        return image to g
    }

    /**
     * 画一枚棋子。
     *
     * 传统样式：木色圆盘 + 双圈边框 + 中心字，红方红字，黑方黑字。
     */
    fun writePiece(target: File, glyph: String, red: Boolean) {
        val (image, g) = newCanvas(PIECE_SIZE)
        val size = PIECE_SIZE.toDouble()
        val margin = size * 0.04
        val diameter = size - margin * 2

        // 圆盘底：径向渐变做出一点木头的立体感
        g.paint = java.awt.RadialGradientPaint(
            java.awt.geom.Point2D.Double(size * 0.4, size * 0.36),
            (diameter * 0.72).toFloat(),
            floatArrayOf(0f, 1f),
            arrayOf(DISC_LIGHT, DISC_DARK),
        )
        g.fill(Ellipse2D.Double(margin, margin, diameter, diameter))

        val ink = if (red) RED_INK else BLACK_INK

        // 外圈
        g.color = DISC_EDGE
        g.stroke = BasicStroke((size * 0.035).toFloat())
        g.draw(Ellipse2D.Double(margin, margin, diameter, diameter))

        // 内圈用棋子本方颜色，红黑一眼能分开
        g.color = ink
        g.stroke = BasicStroke((size * 0.022).toFloat())
        val innerMargin = size * 0.13
        g.draw(Ellipse2D.Double(innerMargin, innerMargin, size - innerMargin * 2, size - innerMargin * 2))

        // 中心字
        val font = loadFont((size * 0.56).toFloat())
        if (font != null) {
            g.font = font
            g.color = ink
            val metrics = g.fontMetrics
            val bounds = metrics.getStringBounds(glyph, g)
            val x = (size - bounds.width) / 2 - bounds.x
            // 视觉居中比几何居中更重要，汉字基线偏下一点看起来才正
            val y = (size - bounds.height) / 2 - bounds.y
            g.drawString(glyph, x.toFloat(), y.toFloat())
        }

        g.dispose()
        target.parentFile.mkdirs()
        ImageIO.write(image, "PNG", target)
    }

    /**
     * 画五子棋与围棋的棋子：素色圆盘，没有字。
     *
     * 与 [writePiece] 分开而不是加个"不画字"的开关，是因为两者的视觉体系不同 ——
     * 象棋子是木底加字，这里是玻璃质感的黑白子，高光位置与配色都不一样。
     *
     * 高光偏左上（0.34, 0.30）而不是正中：偏一点才像一颗有弧面的子，
     * 正中高光看起来是平的。
     *
     * @param black true 画黑子，false 画白子
     */
    fun writeStone(target: File, black: Boolean) {
        val (image, g) = newCanvas(PIECE_SIZE)
        val size = PIECE_SIZE.toDouble()
        val margin = size * 0.04
        val diameter = size - margin * 2

        val light = if (black) STONE_BLACK_LIGHT else STONE_WHITE_LIGHT
        val dark = if (black) STONE_BLACK_DARK else STONE_WHITE_DARK
        val edge = if (black) STONE_BLACK_EDGE else STONE_WHITE_EDGE

        // 径向渐变做弧面：高光偏左上，暗部落在右下
        g.paint = java.awt.RadialGradientPaint(
            java.awt.geom.Point2D.Double(size * 0.34, size * 0.30),
            (diameter * 0.78).toFloat(),
            floatArrayOf(0f, 1f),
            arrayOf(light, dark),
        )
        g.fill(Ellipse2D.Double(margin, margin, diameter, diameter))

        // 描边：白子尤其需要，否则在浅色棋盘上边界会看不清
        g.color = edge
        g.stroke = BasicStroke((size * 0.03).toFloat())
        g.draw(Ellipse2D.Double(margin, margin, diameter, diameter))

        // 左上角一点镜面高光，让子看起来是玻璃或云石而不是纸片。
        // 高光本身也要用径向渐变淡出：直接填一个半透明椭圆会留下清晰的边，
        // 在黑子上尤其明显 —— 看起来是贴了一块灰片，不像反光。
        val glossAlpha = if (black) 120 else 165
        val glossW = diameter * 0.30
        val glossH = glossW * 0.70
        val glossX = margin + diameter * 0.18
        val glossY = margin + diameter * 0.15
        g.paint = java.awt.RadialGradientPaint(
            java.awt.geom.Point2D.Double(glossX + glossW / 2, glossY + glossH / 2),
            (glossW / 2).toFloat(),
            floatArrayOf(0f, 1f),
            arrayOf(Color(255, 255, 255, glossAlpha), Color(255, 255, 255, 0)),
        )
        g.fill(Ellipse2D.Double(glossX, glossY, glossW, glossH))

        g.dispose()
        target.parentFile.mkdirs()
        ImageIO.write(image, "PNG", target)
    }

    /**
     * 画空合法落点使用的白圈标记。
     *
     * 外径缩到贴图边长的 84%，让世界内落点提示略小于棋子；透明内孔扩大到 64%，
     * 圆环径向厚度约为贴图边长的 10%。主体保持 40% 不透明白色，既能看清落点，
     * 又不会像一枚没有字的白棋子那样压住大面积棋盘线。
     */
    fun writeMoveMarker(target: File) {
        val (image, g) = newCanvas(MOVE_MARKER_SIZE)
        val size = MOVE_MARKER_SIZE.toDouble()
        val outerDiameter = size * MOVE_MARKER_OUTER_DIAMETER_RATIO
        val outerMargin = (size - outerDiameter) / 2

        // 0.4 × 255 ≈ 102；整圈只使用白色与透明度，不额外混入暗色描边。
        g.color = Color(0xF8, 0xFA, 0xFC, 102)
        g.fill(Ellipse2D.Double(outerMargin, outerMargin, outerDiameter, outerDiameter))

        // 中心保持完全透明，扩大内孔以收细圆环。
        g.composite = AlphaComposite.Clear
        val holeDiameter = size * MOVE_MARKER_INNER_DIAMETER_RATIO
        val holeMargin = (size - holeDiameter) / 2
        g.fill(Ellipse2D.Double(holeMargin, holeMargin, holeDiameter, holeDiameter))

        g.dispose()
        target.parentFile.mkdirs()
        ImageIO.write(image, "PNG", target)
    }

    /**
     * 画棋盘面。
     *
     * 9 纵线 × 10 横线，河界处纵线断开（最外两条纵线连通，这是象棋盘的传统画法），
     * 两侧九宫画斜线，四个炮位画传统角标；河界中“楚河”朝黑方、“漢界”朝红方。
     *
     * 格点的边距必须与运行期 [linmumua.chess.board.BoardAnchor.EDGE_INSET] 对齐，
     * 否则贴图上的交叉点和棋子实际落点会错开。这里用同样的 0.12/2.0 = 6% 比例。
     */
    fun writeBoard(target: File) {
        val (image, g) = newCanvas(BOARD_SIZE)
        drawBoard(g)
        g.dispose()
        target.parentFile.mkdirs()
        ImageIO.write(image, "PNG", target)
    }

    /**
     * 画纯格线棋盘面（五子棋、围棋）。
     *
     * 与象棋盘的区别：没有河界、九宫与炮位角标，只有 [files] × [ranks] 条贯通格线，
     * 外框加粗。星位点按传统画法标出：15 路盘标天元与四个花点，19 路盘标九个星位。
     *
     * 边距必须按**本盘占位边长** [span] 算，不能写死 6%：运行期
     * `linmumua.chess.board.BoardGeometry` 的可用宽度是 `span - 2 × EDGE_INSET`，
     * 而 `EDGE_INSET` 是绝对值（0.12 格）不是比例。折算成贴图比例就是
     * `EDGE_INSET / span` —— 五子棋 span=2 得 6%，围棋 span=3 只有 4%。
     * 写死 6% 会让 19 路盘的格线比棋子落点每边内缩 2%（最外圈约 6cm），
     * 表现是围棋最外圈的子明显压不住格线交叉点。
     */
    fun writeGridBoard(target: File, files: Int, ranks: Int, span: Int) {
        val (image, g) = newCanvas(BOARD_SIZE)
        val size = BOARD_SIZE.toDouble()

        g.color = BOARD_BG
        g.fillRect(0, 0, BOARD_SIZE, BOARD_SIZE)

        // 0.12 是运行期 BoardAnchor.EDGE_INSET（绝对格数），除以占位边长才得到贴图比例
        val inset = size * (0.12 / span)
        val usable = size - inset * 2
        val fileGap = usable / (files - 1)
        val rankGap = usable / (ranks - 1)
        fun px(file: Int) = inset + file * fileGap
        fun py(rank: Int) = inset + rank * rankGap

        g.color = BOARD_LINE
        // 线数比象棋多，线宽相应细一档，否则密处会糊成一片
        g.stroke = BasicStroke((size * 0.0022).toFloat())
        for (rank in 0 until ranks) {
            g.drawLine(px(0).toInt(), py(rank).toInt(), px(files - 1).toInt(), py(rank).toInt())
        }
        for (file in 0 until files) {
            g.drawLine(px(file).toInt(), py(0).toInt(), px(file).toInt(), py(ranks - 1).toInt())
        }

        // 星位：15 路取 3/7/11 线（0 起算），19 路取 3/9/15 线，都是传统位置
        val stars = if (files >= 19) listOf(3, 9, 15) else listOf(3, 7, 11)
        val dotRadius = minOf(fileGap, rankGap) * 0.22
        for (file in stars) {
            for (rank in stars) {
                if (file >= files || rank >= ranks) continue
                g.fillOval(
                    (px(file) - dotRadius).toInt(),
                    (py(rank) - dotRadius).toInt(),
                    (dotRadius * 2).toInt(),
                    (dotRadius * 2).toInt(),
                )
            }
        }

        g.stroke = BasicStroke((size * 0.005).toFloat())
        g.drawRect(px(0).toInt(), py(0).toInt(), usable.toInt(), usable.toInt())

        g.dispose()
        target.parentFile.mkdirs()
        ImageIO.write(image, "PNG", target)
    }

    /**
     * 把整副棋盘画到给定画布上。
     *
     * 玩家物品图标与家具薄板顶面共用这一张完整贴图，保证两种显示方式的棋盘线一致。
     */
    private fun drawBoard(g: Graphics2D) {
        val size = BOARD_SIZE.toDouble()

        // 底色
        g.color = BOARD_BG
        g.fillRect(0, 0, BOARD_SIZE, BOARD_SIZE)

        // 与 BoardAnchor.EDGE_INSET / BOARD_SPAN 保持一致的内缩比例
        val inset = size * (0.12 / 2.0)
        val usable = size - inset * 2
        val fileGap = usable / 8.0
        val rankGap = usable / 9.0

        fun px(file: Int) = inset + file * fileGap
        fun py(rank: Int) = inset + rank * rankGap

        g.color = BOARD_LINE
        val thin = BasicStroke((size * 0.0035).toFloat())
        val thick = BasicStroke((size * 0.007).toFloat())

        // 10 条横线，横向贯通
        g.stroke = thin
        for (rank in 0..9) {
            g.drawLine(px(0).toInt(), py(rank).toInt(), px(8).toInt(), py(rank).toInt())
        }

        // 9 条纵线：最外两条贯通，中间七条在河界处断开
        for (file in 0..8) {
            if (file == 0 || file == 8) {
                g.drawLine(px(file).toInt(), py(0).toInt(), px(file).toInt(), py(9).toInt())
            } else {
                g.drawLine(px(file).toInt(), py(0).toInt(), px(file).toInt(), py(4).toInt())
                g.drawLine(px(file).toInt(), py(5).toInt(), px(file).toInt(), py(9).toInt())
            }
        }

        // 九宫斜线：黑方 rank 0..2，红方 rank 7..9
        for (top in listOf(0, 7)) {
            g.drawLine(px(3).toInt(), py(top).toInt(), px(5).toInt(), py(top + 2).toInt())
            g.drawLine(px(5).toInt(), py(top).toInt(), px(3).toInt(), py(top + 2).toInt())
        }

        // 四个炮位的传统角标：角标与交叉点留出空隙，炮移走后仍能看出初始位置。
        g.stroke = thin
        val markUnit = minOf(fileGap, rankGap)
        val markOffset = markUnit * CANNON_MARK_OFFSET_RATIO
        val markArm = markUnit * CANNON_MARK_ARM_RATIO
        for (file in listOf(1, 7)) {
            for (rank in listOf(2, 7)) {
                drawPositionMark(g, px(file), py(rank), markOffset, markArm)
            }
        }

        // 外框加粗，棋盘边界更清楚
        g.stroke = thick
        g.drawRect(px(0).toInt(), py(0).toInt(), usable.toInt(), usable.toInt())

        // 河界文字分向双方：楚河朝黑方（rank 0 一侧），漢界朝红方（rank 9 一侧）。
        // 具体角度看两个常量上的说明，贴图 Y 轴贴到世界棋盘面时是翻过来的。
        val riverFont = loadFont((rankGap * 0.62).toFloat())
        if (riverFont != null) {
            g.font = riverFont
            g.color = BOARD_LINE
            val centerY = (py(4) + py(5)) / 2
            drawCentered(
                g,
                "楚 河",
                (px(0) + px(4)) / 2,
                centerY,
                CHU_RIVER_ROTATION_DEGREES,
            )
            drawCentered(
                g,
                "漢 界",
                (px(4) + px(8)) / 2,
                centerY,
                HAN_RIVER_ROTATION_DEGREES,
            )
        }
    }

    /**
     * 棋子圆盘侧壁贴图。
     *
     * 棋子模型原来只有正反两个面，两张圆面之间是空的，斜着看就是两张纸中间透光。
     * 这张纯木色贴图用来填那层空隙，做出真实棋子的厚度。
     *
     * 用圆盘边缘色而不是底色：侧壁在阴影里，用深一档的颜色才像木头的切面，
     * 用底色会显得棋子边缘发白。整张不透明，因为它就是要挡住透光。
     */
    fun writePieceSide(target: File) {
        val (image, g) = newCanvas(PIECE_SIDE_SIZE)
        g.composite = AlphaComposite.Src
        g.color = DISC_EDGE
        g.fillRect(0, 0, PIECE_SIDE_SIZE, PIECE_SIDE_SIZE)
        g.dispose()
        target.parentFile.mkdirs()
        ImageIO.write(image, "PNG", target)
    }

    /**
     * 棋盘家具薄板的侧面与底面贴图。
     *
     * 纯木质底色加一道顶边描边，不画棋盘线，避免薄板侧面出现压缩后的棋盘格。
     */
    fun writeBoardSide(target: File) {
        val size = BOARD_SIZE / 4
        val (image, g) = newCanvas(size)
        g.color = BOARD_BG
        g.fillRect(0, 0, size, size)
        // 顶边压一道深色，让薄板侧面看起来有厚度而不是一块纯色
        g.color = BOARD_LINE
        g.fillRect(0, 0, size, (size * 0.06).toInt().coerceAtLeast(1))
        g.dispose()
        target.parentFile.mkdirs()
        ImageIO.write(image, "PNG", target)
    }

    /** 在交叉点四个象限画向外展开的 L 形角标 */
    private fun drawPositionMark(
        g: Graphics2D,
        centerX: Double,
        centerY: Double,
        offset: Double,
        arm: Double,
    ) {
        for (horizontal in listOf(-1, 1)) {
            for (vertical in listOf(-1, 1)) {
                val cornerX = centerX + horizontal * offset
                val cornerY = centerY + vertical * offset
                g.drawLine(
                    Math.round(cornerX).toInt(),
                    Math.round(cornerY).toInt(),
                    Math.round(cornerX + horizontal * arm).toInt(),
                    Math.round(cornerY).toInt(),
                )
                g.drawLine(
                    Math.round(cornerX).toInt(),
                    Math.round(cornerY).toInt(),
                    Math.round(cornerX).toInt(),
                    Math.round(cornerY + vertical * arm).toInt(),
                )
            }
        }
    }

    /** 以给定点为中心画一段文字；需要时绕文字中心旋转 */
    private fun drawCentered(
        g: Graphics2D,
        text: String,
        centerX: Double,
        centerY: Double,
        rotationDegrees: Double = 0.0,
    ) {
        val originalTransform = g.transform
        if (rotationDegrees != 0.0) {
            g.rotate(Math.toRadians(rotationDegrees), centerX, centerY)
        }
        val bounds = g.fontMetrics.getStringBounds(text, g)
        val x = centerX - bounds.width / 2 - bounds.x
        val y = centerY - bounds.height / 2 - bounds.y
        g.drawString(text, x.toFloat(), y.toFloat())
        g.transform = originalTransform
    }
}
