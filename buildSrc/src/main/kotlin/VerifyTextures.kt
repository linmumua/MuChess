import java.io.File
import javax.imageio.ImageIO

/**
 * 贴图产出自检。
 *
 * 贴图画错不会让构建失败：字体没加载上就是一张只有圆圈的空盘，
 * 文件大小照样正常。所以这里靠像素统计验证"字真的画上去了"。
 */
object VerifyTextures {

    /**
     * 与 `config.yml` 的 `piece.base-scale` 默认值一致。
     *
     * 自检要算棋子的世界厚度，必须知道运行期的缩放。这个值改了要同步这里，
     * 否则厚度比例的判断会失真。
     */
    private const val DEFAULT_BASE_SCALE = 0.17

    /** RGBA 四通道在非透明像素区域中的覆盖比例 */
    data class ChannelOpacity(
        val red: Double,
        val green: Double,
        val blue: Double,
        val alpha: Double,
    )

    /** 一枚棋子的检查结果 */
    data class Report(
        val name: String,
        val width: Int,
        val height: Int,
        val inkRatio: Double,
        val centerOpaquePixelCount: Int,
        val opacity: ChannelOpacity,
    )

    /**
     * 统计图中"墨色"像素占圆盘内部的比例。
     *
     * 只画了圆圈没画字时，中心区域几乎没有深色像素，比例会明显偏低。
     */
    fun inspectPiece(file: File): Report {
        val image = ImageIO.read(file) ?: error("棋子贴图读不出来：${file.name}")
        val width = image.width
        val height = image.height

        // 全图统计 RGBA 各通道的非透明覆盖率，防止某一通道或整张轮廓静默丢失。
        var alphaPixels = 0
        var redPixels = 0
        var greenPixels = 0
        var bluePixels = 0
        for (x in 0 until width) {
            for (y in 0 until height) {
                val argb = image.getRGB(x, y)
                val alpha = argb ushr 24 and 0xFF
                if (alpha < 16) {
                    continue
                }
                alphaPixels++
                if (argb shr 16 and 0xFF > 0) redPixels++
                if (argb shr 8 and 0xFF > 0) greenPixels++
                if (argb and 0xFF > 0) bluePixels++
            }
        }
        val pixelCount = width * height.toDouble()
        val opacity = ChannelOpacity(
            red = redPixels / pixelCount,
            green = greenPixels / pixelCount,
            blue = bluePixels / pixelCount,
            alpha = alphaPixels / pixelCount,
        )

        // 只看中心 55% 的方形区域，避开内外两圈边框。
        val from = (width * 0.225).toInt()
        val to = (width * 0.775).toInt()
        val centerGridSize = minOf(50, width, height)
        val centerFromX = (width - centerGridSize) / 2
        val centerUntilX = centerFromX + centerGridSize
        val centerFromY = (height - centerGridSize) / 2
        val centerUntilY = centerFromY + centerGridSize
        var ink = 0
        var total = 0
        var centerOpaquePixelCount = 0
        for (x in from until to) {
            for (y in from until to) {
                val argb = image.getRGB(x, y)
                if ((argb ushr 24 and 0xFF) < 16) {
                    continue
                }
                val r = argb shr 16 and 0xFF
                val g = argb shr 8 and 0xFF
                val b = argb and 0xFF
                total++
                // 木色底大约 (0xF2,0xDE,0xB0)/(0xD9,0xBE,0x86)，字色明显更深。
                if (r + g + b < 360) {
                    ink++
                    if (x in centerFromX until centerUntilX && y in centerFromY until centerUntilY) {
                        centerOpaquePixelCount++
                    }
                }
            }
        }
        val inkRatio = if (total == 0) 0.0 else ink.toDouble() / total
        return Report(file.name, width, height, inkRatio, centerOpaquePixelCount, opacity)
    }

    /** 检查整套贴图，字形或任一颜色通道异常时抛错让构建失败 */
    /**
     * 棋子模型的两个 element 必须都开着 shade。
     *
     * 有侧壁、厚度也够，但 shade 关掉一样是白做：Minecraft 的立体感几乎全靠面的方向性
     * 明暗（顶面最亮、侧面暗一档），关掉之后侧壁和顶面同亮度糊成一片，眼睛读不出厚度。
     * 这一条曾经真的是 false，棋子连加厚两轮仍被反馈"还是一片一片的"。
     *
     * 单独抽出来是为了能直接喂字符串验证这条自检真的会拦住 shade=false ——
     * 校验跑在资源生成任务内部，改产物会被下一次生成覆盖，没法从外部构造失败场景。
     */
    fun verifyPieceShade(pieceBaseText: String) {
        require(!pieceBaseText.contains("\"shade\": false")) {
            "棋子模型有 element 关掉了 shade，侧壁会和顶面同亮度，加厚也看不出立体"
        }
        // 圆盘 1 块 + 侧壁十六边形 8 块 = 9 个 element，每块都要显式开 shade
        val shadeCount = pieceBaseText.split("\"shade\": true").size - 1
        require(shadeCount == 9) {
            "棋子模型应有 9 个 element（圆盘 1 + 侧壁 8）各自显式 \"shade\": true，当前 $shadeCount 处"
        }
    }

    /**
     * 素色子贴图自检。
     *
     * @param baselineAlpha 象棋子的不透明轮廓占比，素色子必须与之一致
     */
    private fun verifyStones(pieceDir: File, baselineAlpha: Double, channelTolerance: Double) {
        val stones = PackSpec.STONES.associateWith { spec ->
            val file = File(pieceDir, "${spec.id}.png")
            require(file.isFile) { "缺少素色子贴图：${spec.id}.png" }
            val image = ImageIO.read(file) ?: error("素色子贴图读不出来：${spec.id}.png")

            var opaque = 0
            var luminanceSum = 0.0
            for (x in 0 until image.width) {
                for (y in 0 until image.height) {
                    val argb = image.getRGB(x, y)
                    if ((argb ushr 24 and 0xFF) < 16) {
                        continue
                    }
                    opaque++
                    val r = argb ushr 16 and 0xFF
                    val g = argb ushr 8 and 0xFF
                    val b = argb and 0xFF
                    // 感知亮度权重，判断"看起来是黑子还是白子"
                    luminanceSum += 0.2126 * r + 0.7152 * g + 0.0722 * b
                }
            }
            val alphaRatio = opaque.toDouble() / (image.width * image.height)
            val luminance = if (opaque == 0) 0.0 else luminanceSum / opaque
            println(
                "[MuChess] 素色子 ${spec.id} ${image.width}x${image.height} " +
                    "轮廓占比 ${"%.3f".format(alphaRatio)}，平均亮度 ${"%.1f".format(luminance)}",
            )
            require(kotlin.math.abs(alphaRatio - baselineAlpha) <= channelTolerance) {
                "素色子 ${spec.id} 的轮廓占比 $alphaRatio 与象棋子基准 $baselineAlpha 差异超过 $channelTolerance，" +
                    "两者应是同一套圆盘几何"
            }
            luminance
        }

        val black = stones.entries.first { it.key.black }
        val white = stones.entries.first { !it.key.black }
        require(black.value < white.value) {
            "黑子 ${black.key.id} 的平均亮度 ${black.value} 不低于白子 ${white.key.id} 的 ${white.value}，" +
                "两者可能画反了"
        }
        // 差 60 是肉眼能明确分辨黑白的下限；画成同色或差得太少时世界里两方的子会看着一样
        require(white.value - black.value >= 60.0) {
            "黑白子的平均亮度只差 ${white.value - black.value}，世界里会分不清哪方的子"
        }
    }

    /**
     * 校验棋盘贴图的格线边距与运行期棋子落点一致。
     *
     * 存在的理由是**围棋真踩过这个坑**：`TextureGen.writeGridBoard` 曾把边距写死成
     * `0.12 / 2.0`，对占位 2x2 的象棋与五子棋恰好正确，而围棋占位 3x3。
     * 运行期 `BoardAnchor.EDGE_INSET` 是**绝对格数**（0.12 格）不是比例，
     * 折算成贴图比例应是 `EDGE_INSET / span`，围棋只有 4%。写死 6% 的结果是
     * 格线比棋子落点每边多内缩 2%（1024px 贴图上 20px，世界里约 6cm，
     * 而围棋格距只有 15cm），表现为最外圈的子明显压不住交叉点。
     *
     * 这种错不会让构建失败，贴图单看也很正常，只能靠量像素抓出来。
     */
    private fun verifyBoardGridInset(packRoot: File) {
        val boardDir = File(packRoot, "resourcepack/assets/${PackSpec.NAMESPACE}/textures/item/board")
        for (board in PackSpec.BOARDS) {
            val file = File(boardDir, "${board.itemId}.png")
            require(file.isFile) { "缺少棋盘贴图：${board.itemId}.png" }
            val image = ImageIO.read(file) ?: error("棋盘贴图读不出来：${file.name}")
            val width = image.width

            // 沿水平中线从左往右找第一个格线色像素，也就是最外框竖线的左边缘。
            // 走中线是为了避开四角可能的装饰，中线上必然穿过外框。
            val middle = image.height / 2
            var firstLine = -1
            for (x in 0 until width) {
                val rgb = image.getRGB(x, middle)
                val red = (rgb shr 16) and 0xFF
                val green = (rgb shr 8) and 0xFF
                // 底色是 0xE8D2A0 的米色，格线是 0x5A4222 的深棕，红绿两通道都能拉开
                if (red < 0xA0 && green < 0x90) {
                    firstLine = x
                    break
                }
            }
            require(firstLine >= 0) {
                "棋盘贴图 ${board.itemId} 的中线上找不到格线，格线可能整体没画上"
            }

            // 期望边距按占位边长算。外框本身有线宽，左边缘会比格点中心再靠左几个像素，
            // 所以容差是不对称的：允许偏左 12px（线宽富余），几乎不允许偏右。
            val expected = width * (0.12 / board.size)
            require(firstLine - expected in -12.0..4.0) {
                "棋盘贴图 ${board.itemId} 的格线边距不对：首条线在 ${firstLine}px，" +
                    "占位 ${board.size}x${board.size} 期望约 ${"%.1f".format(expected)}px。" +
                    "边距必须按占位边长算（EDGE_INSET / span），不能写死 0.12 / 2.0"
            }
            println(
                "[MuChess] 棋盘 ${board.itemId} 占位 ${board.size} " +
                    "格线边距 ${firstLine}px（期望约 ${"%.1f".format(expected)}px）",
            )
        }
    }

    fun verifyAll(
        packRoot: File,
        minInkRatio: Double = 0.04,
        minCenterOpaquePixels: Int = 2,
        channelTolerance: Double = 0.02,
    ) {
        val pieceDir = File(packRoot, "resourcepack/assets/${PackSpec.NAMESPACE}/textures/item/piece")
        val reports = PackSpec.PIECES.map { inspectPiece(File(pieceDir, "${it.id}.png")) }
        for (report in reports) {
            println(
                "[MuChess] 贴图 ${report.name} ${report.width}x${report.height} " +
                    "字形占比 ${"%.3f".format(report.inkRatio)}，" +
                    "中心 50x50 字形像素 ${report.centerOpaquePixelCount}，" +
                    "不透明 R/G/B/A=${"%.3f".format(report.opacity.red)}/" +
                    "${"%.3f".format(report.opacity.green)}/" +
                    "${"%.3f".format(report.opacity.blue)}/" +
                    "${"%.3f".format(report.opacity.alpha)}",
            )
        }
        val blank = reports.filter {
            it.inkRatio < minInkRatio || it.centerOpaquePixelCount < minCenterOpaquePixels
        }
        if (blank.isNotEmpty()) {
            error(
                "以下棋子贴图看起来没有有效字形（中心像素阈值 $minCenterOpaquePixels），" +
                    "可能是中文字体未加载：${blank.joinToString { it.name }}",
            )
        }

        val channelMismatch = reports.filter { report ->
            val ratios = listOf(
                report.opacity.red,
                report.opacity.green,
                report.opacity.blue,
                report.opacity.alpha,
            )
            ratios.maxOrNull()!! - ratios.minOrNull()!! > channelTolerance
        }
        require(channelMismatch.isEmpty()) {
            "以下棋子贴图的 RGBA 不透明像素比差异超过 $channelTolerance：" +
                channelMismatch.joinToString { it.name }
        }
        val baselineAlpha = reports.first().opacity.alpha
        val silhouetteMismatch = reports.filter {
            kotlin.math.abs(it.opacity.alpha - baselineAlpha) > channelTolerance
        }
        require(silhouetteMismatch.isEmpty()) {
            "以下棋子贴图的不透明轮廓与基准差异超过 $channelTolerance：" +
                silhouetteMismatch.joinToString { it.name }
        }

        // 素色子单独自检。不能套用上面那套"字形占比"的判据 ——
        // 素色子本来就没有字，字形占比必然偏低，硬套会让构建永远失败。
        // 这里要盯的是另外两件事：轮廓要与象棋子一致（同一套圆盘几何），
        // 以及黑子与白子的亮度必须真的分得开（画反或画成同色时贴图看着都对，
        // 但世界里两方的子会长得一样）。
        verifyStones(pieceDir, baselineAlpha, channelTolerance)
        verifyBoardGridInset(packRoot)

        val pieceBaseModel = File(
            packRoot,
            "resourcepack/assets/${PackSpec.NAMESPACE}/models/${PackSpec.PIECE_BASE_ID}.json",
        )
        require(pieceBaseModel.isFile) { "缺少棋子双面共享模型：${pieceBaseModel.name}" }
        val pieceBaseText = pieceBaseModel.readText(Charsets.UTF_8)
        require(!pieceBaseText.contains("minecraft:item/generated")) {
            "棋子共享模型不能继续继承平面 item/generated"
        }
        require(pieceBaseText.contains("\"north\"")) { "棋子双面模型缺少 north 正面" }
        require(pieceBaseText.contains("\"south\"")) { "棋子双面模型缺少 south 背面" }
        // north 与 south 必须用左右相反的 U。
        //
        // 这条原来要求两面共用同一组 [0,0,16,16]，理由写的是"避免镜像"，实际正好相反：
        // Minecraft 每个面的 UV 都按"从该面外侧看"映射，朝向相反的两面给同一组 UV，
        // 结果就是两面互为镜像 —— 棋盘上表现为黑方（看到 south 的那一侧）汉字左右翻。
        // 铺平后玩家俯视看到的是 north（绕 X +90° 把 -Z 法线转到 +Y），
        // 所以反向 U 要给 north；给 south 等于翻了一个看不见的面，字照旧反。
        require(pieceBaseText.contains("\"north\": {\"uv\": [16, 0, 0, 16], \"texture\": \"#layer0\"}")) {
            "棋子朝天面 north 的 U 必须反向写成 [16,0,0,16]，否则棋盘上汉字左右翻"
        }
        require(pieceBaseText.contains("\"south\": {\"uv\": [0, 0, 16, 16], \"texture\": \"#layer0\"}")) {
            "棋子背面 south 必须是正向 UV，与 north 互为镜像，图腾转到背面时字才是正的"
        }
        // 侧壁：只有正反两面的话两张圆面之间透光，棋子看起来完全没有厚度。
        for (face in listOf("east", "west", "up", "down")) {
            require(pieceBaseText.contains("\"$face\": {\"uv\": [0, 0, 16, 16], \"texture\": \"#side\"}")) {
                "棋子模型缺少 $face 侧壁，圆盘之间会透光而显得没有厚度"
            }
        }
        require(!pieceBaseText.contains("\"north\": {\"uv\": [0, 0, 16, 16], \"texture\": \"#side\"}")) {
            "侧壁不应声明 north/south，那两面被圆盘挡住，声明了只会 z-fighting"
        }
        // 侧壁必须是八块拼的正十六边形，不能退回八边形或单块方形。
        //
        // 单块内接正方形的四个角虽然抵在圆盘边缘，四条边却整体缩在圆内，低角度看侧壁
        // 轮廓就是个方的 —— 这正是"棋子下面为什么是方的"。八边形把内缩压到 7.6%，
        // 十六边形再压到 1.9%，边基本贴着圆弧。
        val wallCount = pieceBaseText.split("\"texture\": \"#side\"").size - 1
        require(wallCount == 32) {
            "侧壁应为 8 块 × 4 面 = 32 处 #side 引用，当前 $wallCount 处，" +
                "可能有人把十六边形改回了八边形或单块方形"
        }
        require(pieceBaseText.contains("\"angle\": 45")) {
            "侧壁缺少绕 Z 转 45° 的分片，拼不出十六边形"
        }
        // ±22.5° 是十六边形相对八边形多出来的那四个朝向，缺了就退回八边形
        for (angle in listOf("22.5", "-22.5")) {
            require(pieceBaseText.contains("\"angle\": $angle")) {
                "侧壁缺少绕 Z 转 $angle° 的分片，十六边形会退回八边形"
            }
        }
        require(!pieceBaseText.contains("\"rescale\": true")) {
            "侧壁不能开 rescale，那会把分片撑到旋转后的包围盒，十六边形会鼓出圆盘"
        }
        // 十六边形顶点必须正好落在圆盘半径上：顶点由长短半轴构成
        val vertexDistance = Math.hypot(PackSpec.PIECE_HEXDEC_LONG, PackSpec.PIECE_HEXDEC_SHORT)
        require(Math.abs(vertexDistance - PackSpec.PIECE_DISC_RADIUS) <= 0.01) {
            "侧壁十六边形顶点距中心 $vertexDistance 与圆盘半径 ${PackSpec.PIECE_DISC_RADIUS} 不符，" +
                "侧壁会缩在圆盘内或鼓出去"
        }
        verifyPieceShade(pieceBaseText)
        // 厚度要能被看见：光有侧壁不够，0.04 格从低角度只有一两个像素，仍然像纸。
        // 世界厚度 = 1/16 × 深度缩放 × base-scale，可见直径 = base-scale × 0.92（圆盘占比）。
        val worldThickness = 1.0 / 16.0 * PackSpec.PIECE_FIXED_DEPTH_SCALE * DEFAULT_BASE_SCALE
        val visibleDiameter = DEFAULT_BASE_SCALE * 0.92
        val thicknessRatio = worldThickness / visibleDiameter
        require(thicknessRatio >= 0.3) {
            "棋子厚度只占直径的 ${"%.0f".format(thicknessRatio * 100)}%，太薄看不出立体，" +
                "调大 PackSpec.PIECE_FIXED_DEPTH_SCALE"
        }
        require(thicknessRatio <= 0.7) {
            "棋子厚度占到直径的 ${"%.0f".format(thicknessRatio * 100)}%，会变成柱子"
        }
        println(
            "[MuChess] 棋子世界厚度 ${"%.4f".format(worldThickness)} 格，" +
                "占可见直径 ${"%.0f".format(thicknessRatio * 100)}%",
        )

        val pieceSide = File(
            packRoot,
            "resourcepack/assets/${PackSpec.NAMESPACE}/textures/item/piece/${PackSpec.PIECE_SIDE_TEXTURE}.png",
        )
        require(pieceSide.isFile) { "缺少棋子侧壁贴图：${pieceSide.name}" }
        val pieceSideImage = ImageIO.read(pieceSide) ?: error("棋子侧壁贴图读不出来：${pieceSide.name}")
        require(
            pieceSideImage.width == TextureGen.PIECE_SIDE_SIZE &&
                pieceSideImage.height == TextureGen.PIECE_SIDE_SIZE,
        ) {
            "棋子侧壁贴图尺寸应为 ${TextureGen.PIECE_SIDE_SIZE} 见方，" +
                "实际 ${pieceSideImage.width}x${pieceSideImage.height}"
        }
        // 侧壁存在的意义就是挡住透光，出现任何半透明像素都说明画错了
        for (y in 0 until pieceSideImage.height) {
            for (x in 0 until pieceSideImage.width) {
                require(pieceSideImage.getRGB(x, y) ushr 24 == 0xFF) {
                    "棋子侧壁贴图在 ($x,$y) 不是完全不透明，挡不住圆盘之间的透光"
                }
            }
        }
        println(
            "[MuChess] 棋子侧壁贴图 ${pieceSideImage.width}x${pieceSideImage.height} 全不透明，" +
                "十六边形顶点距中心 ${"%.2f".format(vertexDistance)} 对齐圆盘半径 ${PackSpec.PIECE_DISC_RADIUS}",
        )
        val expectedScale =
            "\"scale\": [${PackSpec.PIECE_FIXED_PLANE_SCALE}, ${PackSpec.PIECE_FIXED_PLANE_SCALE}, ${PackSpec.PIECE_FIXED_DEPTH_SCALE}]"
        for (facing in PackSpec.PIECE_DISPLAY_FACINGS) {
            for (red in listOf(true, false)) {
                val modelId = PackSpec.pieceDisplayBaseId(red, facing)
                val rotation = PackSpec.pieceDisplayRotation(red, facing)
                val displayBase = File(
                    packRoot,
                    "resourcepack/assets/${PackSpec.NAMESPACE}/models/$modelId.json",
                )
                require(displayBase.isFile) { "缺少棋盘棋子朝向模型：$modelId" }
                val displayBaseText = displayBase.readText(Charsets.UTF_8)
                require(
                    displayBaseText.contains(
                        "\"parent\": \"${PackSpec.NAMESPACE}:${PackSpec.PIECE_BASE_ID}\"",
                    ),
                ) {
                    "棋盘棋子朝向模型 $modelId 未继承双面共享模型"
                }
                require(displayBaseText.contains("\"rotation\": [0, 0, $rotation]")) {
                    "棋盘棋子朝向模型 $modelId 的 FIXED 角度异常"
                }
                require(displayBaseText.contains(expectedScale)) {
                    "棋盘棋子朝向模型 $modelId 的 FIXED 厚度缩放异常，预期 $expectedScale"
                }
            }
        }
        for (piece in PackSpec.PIECES) {
            val publicModel = File(
                packRoot,
                "resourcepack/assets/${PackSpec.NAMESPACE}/models/item/piece/${piece.id}.json",
            )
            require(publicModel.isFile) { "缺少图腾/物品棋子模型：${piece.id}" }
            require(
                publicModel.readText(Charsets.UTF_8).contains(
                    "\"parent\": \"${PackSpec.NAMESPACE}:${PackSpec.PIECE_BASE_ID}\"",
                ),
            ) {
                "图腾/物品棋子模型 ${piece.id} 未继承双面共享模型"
            }
            for (facing in PackSpec.PIECE_DISPLAY_FACINGS) {
                val displayParent = PackSpec.pieceDisplayBaseId(piece.red, facing)
                val displayModel = File(
                    packRoot,
                    "resourcepack/assets/${PackSpec.NAMESPACE}/models/item/piece/display/${facing.id}/${piece.id}.json",
                )
                require(displayModel.isFile) { "缺少棋盘专用棋子模型：${piece.id}/${facing.id}" }
                require(
                    displayModel.readText(Charsets.UTF_8).contains(
                        "\"parent\": \"${PackSpec.NAMESPACE}:$displayParent\"",
                    ),
                ) {
                    "棋盘专用棋子模型 ${piece.id}/${facing.id} 的阵营或棋盘朝向父模型异常"
                }
            }
        }

        val items = File(packRoot, "configuration/items.yml")
        require(items.isFile) { "缺少 CraftEngine 物品配置：${items.name}" }
        val itemsText = items.readText(Charsets.UTF_8)
        for (piece in PackSpec.PIECES) {
            val color = if (piece.red) "gold" else "aqua"
            val expectedName = "<!i><$color><lang:item.${PackSpec.NAMESPACE}.${piece.id}></$color>"
            require(itemsText.contains(expectedName)) { "棋子 ${piece.id} 的物品名配色异常，预期 $color" }
            val publicItem = "${PackSpec.NAMESPACE}:${piece.id}:"
            require(itemsText.lineSequence().count { it.trim() == publicItem } == 1) {
                "棋子 ${piece.id} 的公开物品定义缺失或重复"
            }
            for (facing in PackSpec.PIECE_DISPLAY_FACINGS) {
                val displayItem =
                    "${PackSpec.NAMESPACE}:${PackSpec.pieceDisplayItemId(piece.id, facing)}:"
                require(itemsText.lineSequence().count { it.trim() == displayItem } == 1) {
                    "棋子 ${piece.id}/${facing.id} 的棋盘专用物品定义缺失或重复"
                }
                require(
                    itemsText.contains(
                        "path: ${PackSpec.NAMESPACE}:item/piece/display/${facing.id}/${piece.id}",
                    ),
                ) {
                    "棋子 ${piece.id}/${facing.id} 的棋盘专用物品没有引用朝向模型"
                }
            }
        }
        val expectedBoardName =
            "<!i><gold><lang:item.${PackSpec.NAMESPACE}.${PackSpec.BOARD_ID}></gold>"
        require(itemsText.contains(expectedBoardName)) { "棋盘物品名必须使用 gold 主色" }
        println(
            "[MuChess] 棋子双面 JSON、8 个棋盘朝向父模型与 " +
                "${PackSpec.PIECES.size * PackSpec.PIECE_DISPLAY_FACINGS.size} 个内部显示物品正常",
        )

        val furniture = File(packRoot, "configuration/furniture.yml")
        require(furniture.isFile) { "缺少 CraftEngine 家具配置：${furniture.name}" }
        val furnitureText = furniture.readText(Charsets.UTF_8)
        require(furnitureText.contains("blocks_building: false")) {
            "棋盘 interaction hitbox 必须允许在下方继续放置方块"
        }
        require(!furnitureText.contains("blocks_building: true")) {
            "棋盘 interaction hitbox 不能占用方块建造空间"
        }
        println("[MuChess] 棋盘 interaction hitbox 未阻塞方块建造")

        val categories = File(packRoot, "configuration/categories.yml")
        require(categories.isFile) { "缺少 CraftEngine 分类配置：${categories.name}" }
        require(categories.readText(Charsets.UTF_8).contains("<gradient:#fbbf24:#7dd3fc><bold>象棋</bold></gradient>")) {
            "象棋分类标题必须使用金色到天蓝色的短渐变"
        }

        val marker = File(
            packRoot,
            "resourcepack/assets/${PackSpec.NAMESPACE}/textures/item/marker/${PackSpec.MOVE_MARKER_ID}.png",
        )
        require(marker.isFile) { "缺少合法落点白圈贴图：${marker.name}" }
        val markerImage = ImageIO.read(marker) ?: error("合法落点白圈贴图读不出来：${marker.name}")
        require(
            markerImage.width == TextureGen.MOVE_MARKER_SIZE &&
                markerImage.height == TextureGen.MOVE_MARKER_SIZE,
        ) {
            "合法落点白圈贴图尺寸应为 ${TextureGen.MOVE_MARKER_SIZE}x${TextureGen.MOVE_MARKER_SIZE}，" +
                "实际 ${markerImage.width}x${markerImage.height}"
        }
        val centerX = markerImage.width / 2
        val centerY = markerImage.height / 2
        val centerAlpha = markerImage.getRGB(centerX, centerY) ushr 24 and 0xFF
        val cornerAlpha = markerImage.getRGB(0, 0) ushr 24 and 0xFF
        require(centerAlpha < 16 && cornerAlpha < 16) { "合法落点白圈的中心与背景必须保持透明" }

        // 细环扩大了透明内孔：内孔基准点应透明，内外径中点必须命中圆环，外侧基准点应再次透明。
        val innerRadius = (markerImage.width * TextureGen.MOVE_MARKER_INNER_DIAMETER_RATIO * 0.4).toInt()
        val ringRadius = (
            markerImage.width *
                (TextureGen.MOVE_MARKER_INNER_DIAMETER_RATIO + TextureGen.MOVE_MARKER_OUTER_DIAMETER_RATIO) /
                4.0
        ).toInt()
        val outerRadius = (
            markerImage.width *
                (TextureGen.MOVE_MARKER_OUTER_DIAMETER_RATIO / 2.0 + 0.04)
        ).toInt()
        fun axisAlphas(radius: Int): List<Int> = listOf(
            markerImage.getRGB(centerX + radius, centerY) ushr 24 and 0xFF,
            markerImage.getRGB(centerX - radius, centerY) ushr 24 and 0xFF,
            markerImage.getRGB(centerX, centerY + radius) ushr 24 and 0xFF,
            markerImage.getRGB(centerX, centerY - radius) ushr 24 and 0xFF,
        )
        require(axisAlphas(innerRadius).all { it < 16 }) { "合法落点白圈内孔过小或未保持透明" }
        require(axisAlphas(ringRadius).all { it >= 64 }) { "合法落点白圈的中心轴圆环像素不可见或发生偏心" }
        require(axisAlphas(outerRadius).all { it < 16 }) { "合法落点白圈外径过大或透明边距不足" }

        var visiblePixels = 0
        var whitePixels = 0
        var maxAlpha = 0
        for (x in 0 until markerImage.width) {
            for (y in 0 until markerImage.height) {
                val argb = markerImage.getRGB(x, y)
                val alpha = argb ushr 24 and 0xFF
                maxAlpha = maxOf(maxAlpha, alpha)
                if (alpha < 64) {
                    continue
                }
                visiblePixels++
                val red = argb shr 16 and 0xFF
                val green = argb shr 8 and 0xFF
                val blue = argb and 0xFF
                if (red + green + blue >= 660) {
                    whitePixels++
                }
            }
        }
        require(visiblePixels >= markerImage.width * markerImage.height / 20) {
            "合法落点白圈可见像素过少，可能生成成了全透明贴图"
        }
        require(whitePixels >= visiblePixels / 3) { "合法落点标记缺少足够的白色圆环像素" }
        require(maxAlpha in 96..108) { "合法落点白圈应为约 40% 不透明度，实际最大 alpha=$maxAlpha" }
        println(
            "[MuChess] 合法落点细白圈 ${markerImage.width}x${markerImage.height} " +
                "外径 ${TextureGen.MOVE_MARKER_OUTER_DIAMETER_RATIO}、内径 ${TextureGen.MOVE_MARKER_INNER_DIAMETER_RATIO}，" +
                "最大 alpha=$maxAlpha，透明内孔与边距正常",
        )

        val board = File(packRoot, "resourcepack/assets/${PackSpec.NAMESPACE}/textures/item/board/${PackSpec.BOARD_ID}.png")
        val boardImage = ImageIO.read(board)
        require(boardImage.width == TextureGen.BOARD_SIZE && boardImage.height == TextureGen.BOARD_SIZE) {
            "棋盘贴图尺寸异常：${boardImage.width}x${boardImage.height}"
        }

        fun hasDarkPixelNear(centerX: Int, centerY: Int, radius: Int = 2): Boolean {
            val fromX = (centerX - radius).coerceAtLeast(0)
            val toX = (centerX + radius).coerceAtMost(boardImage.width - 1)
            val fromY = (centerY - radius).coerceAtLeast(0)
            val toY = (centerY + radius).coerceAtMost(boardImage.height - 1)
            return (fromX..toX).any { x ->
                (fromY..toY).any { y ->
                    val argb = boardImage.getRGB(x, y)
                    val alpha = argb ushr 24 and 0xFF
                    val red = argb shr 16 and 0xFF
                    val green = argb shr 8 and 0xFF
                    val blue = argb and 0xFF
                    alpha >= 64 && red + green + blue < 360
                }
            }
        }

        // 四个炮位都必须有四象限角标；普通交叉点不能误画同样纹样。
        val boardSize = TextureGen.BOARD_SIZE.toDouble()
        val inset = boardSize * (0.12 / 2.0)
        val usable = boardSize - inset * 2
        val fileGap = usable / 8.0
        val rankGap = usable / 9.0
        val markOffset = minOf(fileGap, rankGap) * TextureGen.CANNON_MARK_OFFSET_RATIO
        fun px(file: Int) = inset + file * fileGap
        fun py(rank: Int) = inset + rank * rankGap
        for (file in listOf(1, 7)) {
            for (rank in listOf(2, 7)) {
                for (horizontal in listOf(-1, 1)) {
                    for (vertical in listOf(-1, 1)) {
                        val sampleX = Math.round(px(file) + horizontal * markOffset).toInt()
                        val sampleY = Math.round(py(rank) + vertical * markOffset).toInt()
                        require(hasDarkPixelNear(sampleX, sampleY)) {
                            "炮位 ($file,$rank) 缺少 $horizontal/$vertical 象限角标"
                        }
                    }
                }
            }
        }
        val controlX = Math.round(px(2) + markOffset).toInt()
        val controlY = Math.round(py(2) + markOffset).toInt()
        require(!hasDarkPixelNear(controlX, controlY)) { "普通交叉点被误画成炮位角标" }

        val riverSpread = (
            (TextureGen.CHU_RIVER_ROTATION_DEGREES - TextureGen.HAN_RIVER_ROTATION_DEGREES) % 360.0 + 360.0
        ) % 360.0
        require(riverSpread == 180.0) { "楚河与漢界必须分别面向黑方和红方" }
        // 只查"相差半圈"不够：两个值整体转半圈同样满足，但那时两侧文字都朝着对手，
        // 这正是之前漏掉的情况。河界文字与棋子阵营半圈必须朝同一侧才算对。
        require(TextureGen.HAN_RIVER_ROTATION_DEGREES.toInt() == PackSpec.pieceSideRotation(red = true)) {
            "漢界朝向与红方棋子字面朝向不一致，会出现棋盘文字朝一方、棋子朝另一方"
        }
        require(TextureGen.CHU_RIVER_ROTATION_DEGREES.toInt() == PackSpec.pieceSideRotation(red = false)) {
            "楚河朝向与黑方棋子字面朝向不一致，会出现棋盘文字朝一方、棋子朝另一方"
        }
        println(
            "[MuChess] 完整棋盘贴图 ${boardImage.width}x${boardImage.height} 正常，" +
                "四个炮位角标完整，楚河/漢界朝向相差半圈",
        )

        val side = File(
            packRoot,
            "resourcepack/assets/${PackSpec.NAMESPACE}/textures/item/board/${PackSpec.BOARD_SIDE_TEXTURE}.png",
        )
        require(side.isFile) { "缺少棋盘侧面贴图：${side.name}" }
        val sideImage = ImageIO.read(side) ?: error("棋盘侧面贴图读不出来：${side.name}")
        val expectedSide = TextureGen.BOARD_SIZE / 4
        require(sideImage.width == expectedSide && sideImage.height == expectedSide) {
            "棋盘侧面贴图尺寸应为 ${expectedSide}x$expectedSide，实际 ${sideImage.width}x${sideImage.height}"
        }
        println("[MuChess] 棋盘侧面贴图 ${sideImage.width}x${sideImage.height} 正常")
    }
}
