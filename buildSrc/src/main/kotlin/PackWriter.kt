import java.io.File

/**
 * 产出 CraftEngine 资源包的文本内容。
 *
 * 目录结构按 CE 要求：`pack.yml` + `configuration` 下的 yml + `resourcepack/assets/<ns>/`。
 * 其中 `resourcepack` 下的模型 JSON 是原版资源包格式的硬性要求，由代码生成，
 * 不是给服主手改的配置 —— 项目规范里对"只用 YAML"的唯一例外就是这里。
 *
 * 参考：CraftEngine 文档 items / furniture 章节（https://ce.gtemc.cn/zh-Hans/）
 */
object PackWriter {

    private const val NS = PackSpec.NAMESPACE

    private fun write(target: File, text: String) {
        target.parentFile.mkdirs()
        target.writeText(text, Charsets.UTF_8)
    }

    /** pack.yml，author 只能是 linmumua */
    fun writePackMeta(root: File, version: String) {
        write(
            File(root, "pack.yml"),
            """
            author: linmumua
            version: $version
            description: MuChess 棋类插件的棋盘、棋子与交互标记资源
            namespace: $NS
            enable: true
            """.trimIndent() + "\n",
        )
    }

    /**
     * items.yml：14 枚棋子 + 1 个玩家棋盘物品 + 2 个内部视觉物品。
     *
     * 棋子、白圈标记与玩家棋盘物品用 paper 作基础材料并配 `model.generation`；玩家棋盘物品的
     * `furniture_item` behavior 只开放 ground，并按四方向吸附到方块角。内部视觉物品
     * 只供显示实体引用，不写进 categories.yml。
     * 物品名走 `<lang:...>`，实际文本在 CE 的 lang 配置里；红方名用 gold、黑方名用 aqua，
     * 棋盘物品沿用系统主色 gold，颜色只包住名称，不污染 lore 或其他组件。
     */
    fun writeItems(root: File) {
        val builder = StringBuilder()
        builder.appendLine("items:")

        for (piece in PackSpec.PIECES) {
            val color = if (piece.red) "gold" else "aqua"
            builder.appendLine("  $NS:${piece.id}:")
            builder.appendLine("    material: paper")
            builder.appendLine("    data:")
            builder.appendLine("      item_name: '<!i><$color><lang:item.$NS.${piece.id}></$color>'")
            builder.appendLine("    model:")
            builder.appendLine("      type: minecraft:model")
            builder.appendLine("      path: $NS:item/piece/${piece.id}")
            for (facing in PackSpec.PIECE_DISPLAY_FACINGS) {
                builder.appendLine("  $NS:${PackSpec.pieceDisplayItemId(piece.id, facing)}:")
                builder.appendLine("    material: paper")
                builder.appendLine("    model:")
                builder.appendLine("      type: minecraft:model")
                builder.appendLine("      path: $NS:item/piece/display/${facing.id}/${piece.id}")
            }
        }

        // 五子棋与围棋的素色子。与象棋棋子一样产出四个朝向的内部物品：
        // 圆盘转向看不出区别，但渲染层统一走 displayItemKey(assetKey, facing)，
        // 少一个朝向就得在渲染层为素色子开特例，不值得。
        for (stone in PackSpec.STONES) {
            val color = if (stone.black) "dark_gray" else "white"
            builder.appendLine("  $NS:${stone.id}:")
            builder.appendLine("    material: paper")
            builder.appendLine("    data:")
            builder.appendLine("      item_name: '<!i><$color><lang:item.$NS.${stone.id}></$color>'")
            builder.appendLine("    model:")
            builder.appendLine("      type: minecraft:model")
            builder.appendLine("      path: $NS:item/piece/${stone.id}")
            for (facing in PackSpec.PIECE_DISPLAY_FACINGS) {
                builder.appendLine("  $NS:${PackSpec.pieceDisplayItemId(stone.id, facing)}:")
                builder.appendLine("    material: paper")
                builder.appendLine("    model:")
                builder.appendLine("      type: minecraft:model")
                builder.appendLine("      path: $NS:item/piece/display/${facing.id}/${stone.id}")
            }
        }

        // 内部合法落点标记：透明背景白圈，只供插件生成的 ItemDisplay 使用。
        builder.appendLine("  $NS:${PackSpec.MOVE_MARKER_ID}:")
        builder.appendLine("    material: paper")
        builder.appendLine("    model:")
        builder.appendLine("      type: minecraft:model")
        builder.appendLine("      path: $NS:item/marker/${PackSpec.MOVE_MARKER_ID}")
        builder.appendLine("      generation:")
        builder.appendLine("        parent: minecraft:item/generated")
        builder.appendLine("        textures:")
        builder.appendLine("          layer0: $NS:item/marker/${PackSpec.MOVE_MARKER_ID}")

        // 玩家棋盘物品：CE 26.8 默认 furniture_item 写法把放置规则放在 rules 下。
        // 只声明 ground，因此不能贴墙或挂顶；alignment 按占位边长奇偶取（见 BoardSpec.alignment）。
        // 物品图标继续使用整张棋盘图，背包里看到的是完整棋盘。
        for (board in PackSpec.BOARDS) {
            builder.appendLine("  $NS:${board.itemId}:")
            builder.appendLine("    material: paper")
            builder.appendLine("    data:")
            builder.appendLine("      item-name: \"<!i><gold><lang:item.$NS.${board.itemId}></gold>\"")
            builder.appendLine("    model:")
            builder.appendLine("      type: minecraft:model")
            builder.appendLine("      path: $NS:item/board/${board.itemId}")
            builder.appendLine("      generation:")
            builder.appendLine("        parent: minecraft:item/generated")
            builder.appendLine("        textures:")
            builder.appendLine("          layer0: $NS:item/board/${board.itemId}")
            builder.appendLine("    behavior:")
            builder.appendLine("      type: furniture_item")
            builder.appendLine("      rules:")
            builder.appendLine("        ground:")
            builder.appendLine("          rotation: four")
            builder.appendLine("          alignment: ${board.alignment}")
            builder.appendLine("      furniture: $NS:${board.itemId}")

            // 内部视觉物品直接引用手写的完整薄板模型，不生成名称，也不进入分类菜单。
            builder.appendLine("  $NS:${board.modelId}:")
            builder.appendLine("    material: paper")
            builder.appendLine("    model:")
            builder.appendLine("      type: minecraft:model")
            builder.appendLine("      path: $NS:item/board/${board.modelId}")
        }

        write(File(root, "configuration/items.yml"), builder.toString())
    }

    /**
     * furniture.yml：一副棋盘只对应一个 CE 家具。
     *
     * 写法对齐本机 CraftEngine 26.8 默认 furniture 配置：外观放在 elements，
     * 点击范围放在 interaction hitbox，但不阻塞方块建造，让玩家仍能补齐棋盘下方地面。
     * 模型以家具原点为中心覆盖 2x2；
     * ItemDisplay 的模型空间以 Y=8 为中心，薄板本身位于 Y=0..2，因此要整体上移 0.5 格，
     * 才能让板底贴住家具原点而不是埋进地面。element 本地 yaw 额外转 180°，
     * 抵消 ground 放置时 base yaw 自带的半圈偏移。
     */
    fun writeFurniture(root: File) {
        val builder = StringBuilder()
        builder.appendLine("furniture:")
        for (board in PackSpec.BOARDS) {
            builder.appendLine("  $NS:${board.itemId}:")
            builder.appendLine("    settings:")
            builder.appendLine("      item: $NS:${board.itemId}")
            builder.appendLine("      hit_times: 3")
            builder.appendLine("      sounds:")
            builder.appendLine("        break: minecraft:block.wood.break")
            builder.appendLine("        place: minecraft:block.wood.place")
            builder.appendLine("        hit: minecraft:block.wood.hit")
            builder.appendLine("    variants:")
            builder.appendLine("      ground:")
            builder.appendLine("        elements:")
            builder.appendLine("          - type: item_display")
            builder.appendLine("            item: $NS:${board.modelId}")
            // 原版模型坐标 Y=0 位于 ItemDisplay 原点下方半格；抬 0.5 后薄板底面才落在家具原点。
            builder.appendLine("            position: \"0,0.5,0\"")
            builder.appendLine("            display_transform: none")
            builder.appendLine("            billboard: fixed")
            builder.appendLine("            yaw: 180")
            builder.appendLine("        hitboxes:")
            builder.appendLine("          - type: interaction")
            // 薄板高 2/16 格；判定箱稍加厚到 0.16，中心仍放在板面厚度中部。
            builder.appendLine("            position: \"0,0.0625,0\"")
            // 判定箱必须盖住整块棋盘，宽度跟着占位边长走
            builder.appendLine("            width: ${board.size}.0")
            builder.appendLine("            height: 0.16")
            // 只保留点击命中，不占用建造空间；否则玩家无法在棋盘下方补放方块。
            builder.appendLine("            blocks_building: false")
            builder.appendLine("            interactive: true")
            builder.appendLine("            invisible: true")
            // 潜行手持方块时继续走原版放置；其他棋盘交互仍由运行期监听器取消并转入棋局逻辑。
            builder.appendLine("            can_use_item_on: true")
            builder.appendLine("            can_be_hit_by_projectile: false")
        }

        write(File(root, "configuration/furniture.yml"), builder.toString())
    }

    /**
     * 写出家具内部视觉物品使用的单一完整薄板模型，每个棋种一份。
     *
     * 原版模型的 X/Z 中心落在 (8, 8)，一格是 16 模型单位，所以边长 N 格的薄板
     * X/Z 从 `8 - 8N` 到 `8 + 8N`（2x2 就是 -8..24）。Y=0..2 是 0.125 格薄板，
     * 家具元素再通过 0.5 格位置偏移把板底对齐世界原点。顶面一次映射整张棋盘纹理，
     * 四侧与底面使用单独的木色贴图。
     */
    fun writeBoardModel(root: File) {
        for (board in PackSpec.BOARDS) {
            writeBoardModel(root, board)
        }
    }

    /** 写出单个棋种的薄板模型 */
    private fun writeBoardModel(root: File, board: PackSpec.BoardSpec) {
        val half = 8 * board.size
        val from = 8 - half
        val to = 8 + half
        write(
            File(root, "resourcepack/assets/$NS/models/item/board/${board.modelId}.json"),
            """
            {
              "ambientocclusion": false,
              "textures": {
                "top": "$NS:item/board/${board.itemId}",
                "side": "$NS:item/board/${PackSpec.BOARD_SIDE_TEXTURE}",
                "particle": "$NS:item/board/${PackSpec.BOARD_SIDE_TEXTURE}"
              },
              "elements": [
                {
                  "from": [$from, 0, $from],
                  "to": [$to, 2, $to],
                  "faces": {
                    "down": { "uv": [0, 0, 16, 16], "texture": "#side" },
                    "up": { "uv": [0, 0, 16, 16], "texture": "#top" },
                    "north": { "uv": [0, 0, 16, 16], "texture": "#side" },
                    "south": { "uv": [0, 0, 16, 16], "texture": "#side" },
                    "west": { "uv": [0, 0, 16, 16], "texture": "#side" },
                    "east": { "uv": [0, 0, 16, 16], "texture": "#side" }
                  }
                }
              ]
            }
            """.trimIndent() + "\n",
        )
    }

    /**
     * 写出棋子双面模型、棋盘专用朝向父级与 14 组纹理子模型。
     *
     * 基础模型明确声明 north/south 两个面。两面**必须用左右相反的 U**，不能共用同一组 UV：
     * 每个面的 UV 都按"从该面外侧看"映射，朝向相反的两面给同一组 `[0,0,16,16]`，
     * 结果是两面互为镜像。
     *
     * 谁翻由"玩家实际看到哪个面"决定：渲染层绕 X 轴 +90° 铺平，把 north 的法线 −Z
     * 转到 +Y，所以棋盘上朝天、玩家俯视看到的是 **north**。因此 north 写反向 U
     * `[16,0,0,16]` 把字翻正，south 留正向，两面仍互为镜像 ——
     * 将死图腾动画绕 Y 转到背面时字也是正的。
     *
     * 曾经翻错过一次：只翻了 south。那面平时被压在下面根本看不见，改完字照旧反。
     *
     * 棋盘上的红黑半圈差与四种棋盘朝向全部写进 display 父模型（只用绕 Z 的整圈旋转，
     * 不会引入镜像），运行期不再旋转文字。
     *
     * 后四个 element 是圆盘侧壁：只有正反两面的话，两张圆面之间是空的，斜着看就是两张纸
     * 中间透光，完全没有厚度。侧壁用木色贴图把这层空隙填上。
     *
     * 侧壁必须是**八块拼的正十六边形**，不能用单块内接正方形。内接正方形的四个角虽然抵在
     * 圆盘边缘，四条边却整体缩在圆内，低角度看侧壁轮廓就是个方的，正是"棋子下面为什么是方的"
     * 那个问题。八块同样的长条矩形按 22.5° 一档铺满半圈，并集是正十六边形：十六个顶点落在
     * 圆盘半径上，平边只比外接圆内缩 1.9%。
     * 原版模型格式没有圆柱，十六边形是在"每块只能绕一个轴、角度只能取 ±45/±22.5/0"
     * 这两条限制下能做到的最接近的近似。
     *
     * 每块都不声明 north/south，那两面本来就被圆盘挡住，声明了只会和圆盘 z-fighting。
     *
     * 两个 element 都必须 `"shade": true`。原来是 false —— 那是模型还只有一张平面时留下的，
     * 平面没有侧壁，明暗无所谓。但补了侧壁之后 shade 关着等于白补：Minecraft 的立体感几乎
     * 全靠面的方向性明暗（顶面最亮、侧面暗一档），关掉之后所有面同亮度，侧壁和顶面糊成
     * 一片，几何上再厚也读不出厚度，看着仍然是一张纸。
     */
    /**
     * 侧壁的八块十六边形分片。
     *
     * 每块都是同一个长条矩形（长 2L、宽 2S），只是朝向不同。八块的朝向按 22.5° 一档
     * 铺满半圈（矩形是中心对称的，超过 180° 与前面重复），并集就是正十六边形：
     * 每块贡献两条对边，8 × 2 = 16 条边，顶点全部落在圆盘半径上。
     *
     * 原版模型格式有两条硬限制：每个 element 只能绕**一个**轴转，角度只能取
     * ±45 / ±22.5 / 0。所以 67.5° 以上的朝向不能直接写 rotation，靠**交换长短边**
     * （等价于再转 90°）补出来：
     *
     * | 目标朝向 | 写法 |
     * |---|---|
     * | 0°     | 长边沿 x，不转 |
     * | 22.5°  | 长边沿 x，转 22.5° |
     * | 45°    | 长边沿 x，转 45° |
     * | 67.5°  | 长边沿 y，转 -22.5° |
     * | 90°    | 长边沿 y，不转 |
     * | 112.5° | 长边沿 y，转 22.5° |
     * | 135°   | 长边沿 y，转 45° |
     * | 157.5° | 长边沿 x，转 -22.5° |
     *
     * rescale 不能开：那会把 element 撑大以填满旋转后的包围盒，十六边形会鼓出圆盘。
     */
    private fun hexadecagonWalls(): String {
        val long = PackSpec.PIECE_HEXDEC_LONG
        val short = PackSpec.PIECE_HEXDEC_SHORT
        val faces = """"east": {"uv": [0, 0, 16, 16], "texture": "#side"}, """ +
            """"west": {"uv": [0, 0, 16, 16], "texture": "#side"}, """ +
            """"up": {"uv": [0, 0, 16, 16], "texture": "#side"}, """ +
            """"down": {"uv": [0, 0, 16, 16], "texture": "#side"}"""

        // angle 必须是 Double：十六边形要用 ±22.5，原版只接受 ±45/±22.5/0 这五个字面值，
        // 写成 22 会被 Minecraft 判为非法角度而整块模型加载失败。
        fun block(halfX: Double, halfY: Double, angle: Double): String {
            val rotation = if (angle == 0.0) {
                ""
            } else {
                """
                  "rotation": {"angle": ${fmt(angle)}, "axis": "z", "origin": [8, 8, 8]},"""
            }
            return """
                {
                  "from": [${fmt(8 - halfX)}, ${fmt(8 - halfY)}, 7.5],
                  "to": [${fmt(8 + halfX)}, ${fmt(8 + halfY)}, 8.5],$rotation
                  "shade": true,
                  "faces": {$faces}
                }"""
        }

        return listOf(
            // 长边沿 x：0° / 22.5° / 45° / 157.5°(= -22.5°)
            block(long, short, 0.0),
            block(long, short, 22.5),
            block(long, short, 45.0),
            block(long, short, -22.5),
            // 长边沿 y 等价于额外转 90°：90° / 112.5° / 135° / 67.5°(= 90-22.5°)
            block(short, long, 0.0),
            block(short, long, 22.5),
            block(short, long, 45.0),
            block(short, long, -22.5),
        ).joinToString(",")
    }

    /** 模型 JSON 里的小数，去掉多余零，避免 7.3600000001 之类的尾差 */
    private fun fmt(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else "%.4f".format(value)

    fun writePieceModels(root: File) {
        write(
            File(root, "resourcepack/assets/$NS/models/${PackSpec.PIECE_BASE_ID}.json"),
            """
            {
              "ambientocclusion": false,
              "gui_light": "front",
              "textures": {
                "particle": "#layer0",
                "side": "$NS:item/piece/${PackSpec.PIECE_SIDE_TEXTURE}"
              },
              "elements": [
                {
                  "from": [0, 0, 7.5],
                  "to": [16, 16, 8.5],
                  "shade": true,
                  "faces": {
                    "north": {"uv": [16, 0, 0, 16], "texture": "#layer0"},
                    "south": {"uv": [0, 0, 16, 16], "texture": "#layer0"}
                  }
                },
${hexadecagonWalls()}
              ],
              "display": {
                "gui": {
                  "rotation": [0, 0, 0],
                  "translation": [0, 0, 0],
                  "scale": [1, 1, 1]
                },
                "ground": {
                  "rotation": [0, 0, 0],
                  "translation": [0, 2, 0],
                  "scale": [0.5, 0.5, 0.5]
                },
                "head": {
                  "rotation": [0, 180, 0],
                  "translation": [0, 13, 7],
                  "scale": [1, 1, 1]
                },
                "thirdperson_righthand": {
                  "rotation": [0, 0, 0],
                  "translation": [0, 3, 1],
                  "scale": [0.55, 0.55, 0.55]
                },
                "firstperson_righthand": {
                  "rotation": [0, -90, 25],
                  "translation": [1.13, 3.2, 1.13],
                  "scale": [0.68, 0.68, 0.68]
                },
                "fixed": {
                  "rotation": [0, 0, 0],
                  "translation": [0, 0, 0],
                  "scale": [1, 1, 1]
                }
              }
            }
            """.trimIndent() + "\n",
        )
        for (facing in PackSpec.PIECE_DISPLAY_FACINGS) {
            for (red in listOf(true, false)) {
                // 阵营半圈差的取值与理由都收在 PackSpec.pieceSideRotation，
                // 生成与自检共用一份，避免改一处漏一处。
                writePieceDisplayBaseModel(
                    root = root,
                    modelId = PackSpec.pieceDisplayBaseId(red, facing),
                    rotation = PackSpec.pieceDisplayRotation(red, facing),
                )
            }
        }
        for (piece in PackSpec.PIECES) {
            val texture = "$NS:item/piece/${piece.id}"
            write(
                File(root, "resourcepack/assets/$NS/models/item/piece/${piece.id}.json"),
                """
                {
                  "parent": "$NS:${PackSpec.PIECE_BASE_ID}",
                  "textures": {
                    "layer0": "$texture"
                  }
                }
                """.trimIndent() + "\n",
            )
            for (facing in PackSpec.PIECE_DISPLAY_FACINGS) {
                val displayParent = PackSpec.pieceDisplayBaseId(piece.red, facing)
                write(
                    File(
                        root,
                        "resourcepack/assets/$NS/models/item/piece/display/${facing.id}/${piece.id}.json",
                    ),
                    """
                    {
                      "parent": "$NS:$displayParent",
                      "textures": {
                        "layer0": "$texture"
                      }
                    }
                    """.trimIndent() + "\n",
                )
            }
        }

        // 素色子的模型。父级一律借黑方那套（pieceDisplayBaseId(red = false)）：
        // 阵营半圈差存在的唯一理由是让每方读到自己面前的字是正的，
        // 而素色圆盘是旋转对称的，转半圈看不出区别，没必要产出两套父模型。
        for (stone in PackSpec.STONES) {
            val texture = "$NS:item/piece/${stone.id}"
            write(
                File(root, "resourcepack/assets/$NS/models/item/piece/${stone.id}.json"),
                """
                {
                  "parent": "$NS:${PackSpec.PIECE_BASE_ID}",
                  "textures": {
                    "layer0": "$texture"
                  }
                }
                """.trimIndent() + "\n",
            )
            for (facing in PackSpec.PIECE_DISPLAY_FACINGS) {
                val displayParent = PackSpec.pieceDisplayBaseId(red = false, facing = facing)
                write(
                    File(
                        root,
                        "resourcepack/assets/$NS/models/item/piece/display/${facing.id}/${stone.id}.json",
                    ),
                    """
                    {
                      "parent": "$NS:$displayParent",
                      "textures": {
                        "layer0": "$texture"
                      }
                    }
                    """.trimIndent() + "\n",
                )
            }
        }
    }

    /** 棋盘 ItemDisplay 专用父模型：FIXED 同时固化阵营、棋盘方向与厚度缩放。 */
    private fun writePieceDisplayBaseModel(root: File, modelId: String, rotation: Int) {
        write(
            File(root, "resourcepack/assets/$NS/models/$modelId.json"),
            """
            {
              "parent": "$NS:${PackSpec.PIECE_BASE_ID}",
              "display": {
                "fixed": {
                  "rotation": [0, 0, $rotation],
                  "translation": [0, 0, 0],
                  "scale": [${PackSpec.PIECE_FIXED_PLANE_SCALE}, ${PackSpec.PIECE_FIXED_PLANE_SCALE}, ${PackSpec.PIECE_FIXED_DEPTH_SCALE}]
                }
              }
            }
            """.trimIndent() + "\n",
        )
    }

    /**
     * lang.yml：CE 客户端语言文件，棋子与棋盘的物品名。
     *
     * 这些是物品 tooltip 上的名字，走 CE 的 lang 机制下发到客户端，
     * 与插件自己的 lang/zh_CN.yml 是两套东西，不要混。
     */
    fun writeLang(root: File) {
        val builder = StringBuilder()
        builder.appendLine("lang:")
        builder.appendLine("  zh_cn:")
        for (piece in PackSpec.PIECES) {
            val side = if (piece.red) "红" else "黑"
            builder.appendLine("    item.$NS.${piece.id}: \"$side${piece.glyph}\"")
        }
        for (stone in PackSpec.STONES) {
            // 素色子两个棋种共用，所以名字不写"五子棋"或"围棋"，只说颜色
            builder.appendLine("    item.$NS.${stone.id}: \"${if (stone.black) "黑子" else "白子"}\"")
        }
        for (board in PackSpec.BOARDS) {
            builder.appendLine("    item.$NS.${board.itemId}: \"${board.displayName}\"")
        }
        // 声音字幕：开了"显示字幕"的玩家会在右下角看到这行。
        // 键名格式 subtitles.<命名空间>.<事件 id> 由 sounds.yml 的 subtitle 字段指定，
        // 两处必须一致，VerifyAudio 会核对。
        builder.appendLine(
            "    subtitles.$NS.${PackSpec.GO_BGM_SOUND_ID}: \"围棋 · 一叶一花一菩提\"",
        )
        write(File(root, "configuration/lang.yml"), builder.toString())
    }

    /**
     * sounds.yml：注册围棋开局 BGM 的声音事件。
     *
     * 写法照 CE 文档的 sounds 配置（<https://ce.gtemc.cn/zh-Hans/>）：顶层 `sounds:`，
     * 每个键是完整事件 id，`sounds` 列表里的 `name` 指向资源包内
     * `assets/<命名空间>/sounds/<路径>.ogg`（不写扩展名）。
     *
     * 两个字段值得单独说明：
     * - `stream: true`：长音乐必须流式播放。不开的话客户端会把整段解码进内存，
     *   65 秒的曲子每次触发都占一块内存，且首次播放有明显卡顿。
     *   原版对 music 类音效同样开 stream。
     * - `attenuation_distance: 24`：默认 16 格外就听不见了，棋盘 3x3 加上围观距离偏紧，
     *   放到 24 格让旁观者也在音场内。再大就会打扰附近无关玩家。
     */
    fun writeSounds(root: File) {
        val builder = StringBuilder()
        builder.appendLine("sounds:")
        builder.appendLine("  $NS:${PackSpec.GO_BGM_SOUND_ID}:")
        builder.appendLine("    subtitle: subtitles.$NS.${PackSpec.GO_BGM_SOUND_ID}")
        builder.appendLine("    sounds:")
        builder.appendLine("      - name: $NS:${PackSpec.GO_BGM_SOUND_PATH}")
        builder.appendLine("        stream: true")
        builder.appendLine("        volume: 1.0")
        builder.appendLine("        attenuation_distance: 24")
        write(File(root, "configuration/sounds.yml"), builder.toString())
    }

    /**
     * categories.yml：把本插件的物品单独归一类。
     *
     * 不做分类的话这 15 个物品会散在 CE 物品浏览器的默认分类里，
     * 和服主自己的资源混在一起翻不出来。
     *
     * 写法照 CE 文档与本机实例（MUZ 的 muz/configuration/categories.yml、
     * CE 自带的 default_assets/configuration/categories.yml）：
     * `name` 走 MiniMessage，`icon` 给一个物品 id 做图标，`list` 列出成员。
     *
     * 图标用棋盘：它是唯一能放置的物品，也是玩家真正要找的那个。
     * 顺序上棋盘排第一，其余按红黑分组，跟棋谱习惯一致（帥仕相馬車炮兵）。
     */
    fun writeCategories(root: File) {
        val builder = StringBuilder()
        builder.appendLine("categories:")
        builder.appendLine("  $NS:xiangqi:")
        // 分类属于菜单标题，可以使用短渐变；<!i> 关掉 CE 默认斜体。
        builder.appendLine("    name: <!i><gradient:#fbbf24:#7dd3fc><bold>象棋</bold></gradient>")
        builder.appendLine("    icon: $NS:${PackSpec.BOARD_ID}")
        builder.appendLine("    list:")
        // 棋盘放最前：它们是唯一可放置的物品，各棋种一块
        for (board in PackSpec.BOARDS) {
            builder.appendLine("      - $NS:${board.itemId}")
        }
        for (piece in PackSpec.PIECES) {
            builder.appendLine("      - $NS:${piece.id}")
        }
        // 素色子排在象棋子之后：它们属于五子棋与围棋，但共用同一个分类，
        // 单独开一类只有两个物品，翻起来反而更麻烦。
        for (stone in PackSpec.STONES) {
            builder.appendLine("      - $NS:${stone.id}")
        }
        write(File(root, "configuration/categories.yml"), builder.toString())
    }

    /**
     * 产出整套包内容。
     *
     * @param audioSourceDir 音频素材目录（项目的 `assets-src/audio`），
     *   里面的 ogg 是预先转好的，构建期只按字节复制过去，不调用 ffmpeg —— 见
     *   [PackSpec.GO_BGM_SOURCE_FILE] 的说明。
     */
    fun writeAll(root: File, version: String, audioSourceDir: File) {
        writePackMeta(root, version)
        writeItems(root)
        writeFurniture(root)
        writeCategories(root)
        writeLang(root)
        writeSounds(root)
        writeBoardModel(root)
        writePieceModels(root)

        val textureRoot = File(root, "resourcepack/assets/$NS/textures")
        for (piece in PackSpec.PIECES) {
            TextureGen.writePiece(File(textureRoot, "item/piece/${piece.id}.png"), piece.glyph, piece.red)
        }
        for (stone in PackSpec.STONES) {
            TextureGen.writeStone(File(textureRoot, "item/piece/${stone.id}.png"), stone.black)
        }
        TextureGen.writeMoveMarker(
            File(textureRoot, "item/marker/${PackSpec.MOVE_MARKER_ID}.png"),
        )
        // 同一张完整棋盘图既是玩家物品图标，也是家具薄板的整个顶面。
        // 象棋盘有河界九宫，五子棋/围棋盘只是纯格线，所以按棋种分别画。
        for (board in PackSpec.BOARDS) {
            val target = File(textureRoot, "item/board/${board.itemId}.png")
            if (board.itemId == PackSpec.BOARD_ID) {
                TextureGen.writeBoard(target)
            } else {
                TextureGen.writeGridBoard(target, board.files, board.ranks, board.size)
            }
        }
        TextureGen.writeBoardSide(
            File(textureRoot, "item/board/${PackSpec.BOARD_SIDE_TEXTURE}.png"),
        )
        TextureGen.writePieceSide(
            File(textureRoot, "item/piece/${PackSpec.PIECE_SIDE_TEXTURE}.png"),
        )

        // 音频：从素材目录直接复制进资源包。
        // 缺文件当场报错而不是静默跳过 —— 静默跳过的结果是包发出去了但围棋没声音，
        // 而 sounds.yml 已经注册了事件，客户端只会安静地什么也不放。
        val audioSource = File(audioSourceDir, PackSpec.GO_BGM_SOURCE_FILE)
        if (!audioSource.isFile) {
            error(
                "找不到围棋 BGM 素材：${audioSource.path}。" +
                    "该文件应由 ffmpeg 预先转好并随仓库提交，构建期不做转码。",
            )
        }
        val audioTarget = File(root, "resourcepack/assets/$NS/sounds/${PackSpec.GO_BGM_SOUND_PATH}.ogg")
        audioTarget.parentFile.mkdirs()
        audioSource.copyTo(audioTarget, overwrite = true)
    }
}
