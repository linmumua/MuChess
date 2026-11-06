/**
 * CraftEngine 资源包的内容定义。
 *
 * 棋子字形与 id 在这里集中列一遍，构建期据此产出贴图、CE 配置与原版模型 JSON。
 * 运行期的 `linmumua.chess.rule.core.Piece.assetKey` 拼出的就是这里的 [PieceSpec.id]，
 * 两边必须对齐，改了一侧要同步改另一侧。
 */
object PackSpec {

    /** CE 包名与命名空间 */
    const val NAMESPACE = "muchess"

    /** 玩家持有并放置的象棋棋盘家具物品 id */
    const val BOARD_ID = "xiangqi_board"

    /** 只供家具 item_display 使用，不加入物品分类菜单 */
    const val BOARD_MODEL_ID = "xiangqi_board_model"

    /**
     * 一个棋种的棋盘家具定义。
     *
     * 每个棋种一块棋盘家具：物品 id、内部视觉物品 id、占位边长、格点数、显示名。
     * 这几项必须成套 —— 边长与格点数决定贴图画多少条线、hitbox 多宽、
     * `alignment` 用 corner 还是 center，改一处漏一处就会让棋子与贴图错位。
     *
     * [size] 是占位方块数：偶数边长（2）家具绕方块角旋转，用 `corner`；
     * 奇数边长（3，围棋）绕方块中心旋转，必须用 `center`，否则棋盘会偏半格。
     *
     * 运行期对应 `linmumua.chess.game.GameType`，[itemId] 与 `GameType.furnitureId` 一致。
     */
    data class BoardSpec(
        /** 玩家物品与家具 id */
        val itemId: String,
        /**
         * 占位边长（方块数）。
         *
         * **上限是 3。** 薄板模型顶点按 `8 ± 8×size` 算（见 `PackWriter.writeBoardModel`），
         * size=3 得到 -16..32，正好压在 Minecraft JSON 模型坐标的合法边界上；
         * size=4 会算出 -24..40 直接越界，资源包加载即报错且提示与本字段毫无关联。
         */
        val size: Int,
        /** 纵线数（贴图与格点换算用） */
        val files: Int,
        /** 横线数 */
        val ranks: Int,
        /** CE 物品显示名 */
        val displayName: String,
    ) {
        /** 家具内部视觉物品 id，只供 item_display 使用 */
        val modelId: String get() = "${itemId}_model"

        /** 占位边长为偶数时绕方块角旋转，奇数时绕方块中心 */
        val alignment: String get() = if (size % 2 == 0) "corner" else "center"
    }

    /**
     * 全部棋盘家具。
     *
     * 象棋 9×10 铺 2x2；五子棋 15×15 同样铺 2x2（格距 0.126 格，比象棋密但够放子）；
     * 围棋 19×19 铺 3x3（格距 0.153 格，比五子棋略宽）。
     *
     * 围棋是唯一奇数边长的一副，[BoardSpec.alignment] 自动给它 `center`，
     * 家具绕方块中心旋转而不是绕角。
     */
    val BOARDS = listOf(
        BoardSpec(BOARD_ID, size = 2, files = 9, ranks = 10, displayName = "象棋棋盘"),
        BoardSpec("gomoku_board", size = 2, files = 15, ranks = 15, displayName = "五子棋棋盘"),
        BoardSpec("go_board", size = 3, files = 19, ranks = 19, displayName = "围棋棋盘"),
    )

    /** 14 枚棋子与将死图腾共用的双面 JSON 模型父级 */
    const val PIECE_BASE_ID = "item/piece/xiangqi_piece_base"

    /** 只供棋盘 ItemDisplay 使用的内部物品 id 分隔片段 */
    const val PIECE_DISPLAY_ITEM_SEGMENT = "_display_"

    /** FIXED 场景沿棋子平面的缩放，配合 ItemDisplay 保持世界显示直径 */
    const val PIECE_FIXED_PLANE_SCALE = 1.0

    /**
     * FIXED 场景沿模型厚度轴的缩放。
     *
     * 模型本体厚 1 单位（z 7.5..8.5），世界厚度 = 1/16 × 本值 × `piece.base-scale`。
     * 取 7.0 时是 1/16 × 7 × 0.17 ≈ 0.0744 格，约为棋子直径（0.156 格）的 48%，
     * 接近一枚厚棋子的比例。
     *
     * 之前是 4.0，只有 0.0425 格；再加上棋子几何中心原本压在棋盘面上、一半厚度埋进板里，
     * 实际露出来不到 0.021 格（约两厘米），低角度看就是一张纸。
     * 现在配合 [linmumua.chess.render.BoardRenderer] 把棋子抬起半个厚度，整段厚度才真正可见。
     */
    const val PIECE_FIXED_DEPTH_SCALE = 7.0

    /**
     * 棋盘视觉朝向的模型变体。
     *
     * id 与运行期 BoardFacing 小写名一致；rotation 直接写进 display.fixed，运行期不能再按
     * 棋盘或阵营旋转文字。
     */
    data class PieceDisplayFacingSpec(val id: String, val rotation: Int)

    val PIECE_DISPLAY_FACINGS = listOf(
        PieceDisplayFacingSpec("north", 0),
        PieceDisplayFacingSpec("east", 90),
        PieceDisplayFacingSpec("south", 180),
        PieceDisplayFacingSpec("west", 270),
    )

    /** 某阵营、某棋盘朝向对应的共享模型父级 id */
    fun pieceDisplayBaseId(red: Boolean, facing: PieceDisplayFacingSpec): String {
        val side = if (red) "red" else "black"
        return "item/piece/xiangqi_piece_${side}_${facing.id}_display_base"
    }

    /**
     * 阵营半圈差。
     *
     * 每方必须读到自己面前的字是正的，这是象棋盘的基本要求。红方底线在 rank 9、
     * 黑方在 rank 0；棋子模型贴到世界棋盘面之后，`display.fixed` 的 0 度是背对 rank 0
     * 那一侧的（模型 UV 与铺平俯仰共同作用），所以红方要转半圈，黑方用 0 度。
     *
     * 写成函数是因为 PackWriter 生成、VerifyTextures 自检两处都要用同一份值。
     * 之前各写一份 `if (red) 0 else 180`，改一处漏一处就会让自检失去意义。
     */
    fun pieceSideRotation(red: Boolean): Int = if (red) 180 else 0

    /** 某阵营、某棋盘朝向下写进 `display.fixed` 的最终角度 */
    fun pieceDisplayRotation(red: Boolean, facing: PieceDisplayFacingSpec): Int =
        (facing.rotation + pieceSideRotation(red)) % 360

    /** 某棋子、某棋盘朝向对应的内部 CE 物品 id */
    fun pieceDisplayItemId(pieceId: String, facing: PieceDisplayFacingSpec): String =
        "$pieceId$PIECE_DISPLAY_ITEM_SEGMENT${facing.id}"

    /** 只供空合法落点 ItemDisplay 使用，不加入物品分类菜单 */
    const val MOVE_MARKER_ID = "move_marker"

    /**
     * 围棋开局背景音乐的声音事件 id（不含命名空间）。
     *
     * 完整 id 是 `muchess:music.go_opening`，运行期 `config.yml` 的
     * `go.opening-bgm.sound` 默认值就是它。**运行期不能引用本对象**（buildSrc 不在
     * 运行期 classpath），那边是写死的字面量，改这里要同步改 `ChessSettings` 的默认值
     * 与 `config.yml`，情形与 `PieceSpec.id` 对 `Piece.assetKey` 一样。
     *
     * 事件 id 用点号分段（`music.go_opening`）而文件路径用斜杠（[GO_BGM_SOUND_PATH]），
     * 这是原版 sounds.json 的约定，两者不是同一个东西。
     */
    const val GO_BGM_SOUND_ID = "music.go_opening"

    /** 音频在资源包内的相对路径，不含命名空间与 `.ogg` 扩展名 */
    const val GO_BGM_SOUND_PATH = "music/go_opening"

    /**
     * 音频素材的源文件名，位于项目的 `assets-src/audio/`。
     *
     * 素材来源：服主（本项目使用者）提供的音频《一叶一花一菩提》，
     * 用途仅限本插件围棋对局的开局背景音乐。**本插件不主张该音频的任何权利**，
     * 版权责任在提供方，二次分发前请自行确认授权。
     *
     * 这是**预先转好的 ogg**（`-vn -c:a libvorbis -b:a 96k -ar 44100`，
     * 剥掉了源 mp3 内嵌的封面图流），构建期只做字节复制。
     * 不在构建期调 ffmpeg 转码：那会让没装 ffmpeg 的机器直接构建失败。
     */
    const val GO_BGM_SOURCE_FILE = "go_opening.ogg"

    /**
     * 自带曲子的时长，秒（ffprobe 实测 65.55）。
     *
     * 只用于给 `config.yml` 的 `loop-period-seconds` 定默认值（向上取整到 66）：
     * 重播周期必须**略大于**时长，小了同一段音乐会在客户端叠着放。
     */
    const val GO_BGM_DURATION_SECONDS = 65.55

    /** 家具薄板侧面与底面共用的木色贴图 id 片段 */
    const val BOARD_SIDE_TEXTURE = "board_side"

    /** 棋子圆盘侧壁的木色贴图 id 片段 */
    const val PIECE_SIDE_TEXTURE = "piece_side"

    /**
     * 棋子侧壁在模型 x/y 上的内缩边界。
     *
     * 棋子贴图的圆盘直径占贴图 92%（margin 4%），半径 7.36 模型单位。
     *
     * 这两个常量是**单块内接正方形**侧壁的边界，已不再用于棋子模型：内接正方形的四个角
     * 虽然抵在圆盘边缘上，但它的四条边整体缩在圆内，低角度看过去侧壁轮廓就是个方的，
     * 正是"棋子下面为什么是方的"那个问题。现在侧壁改用 [PIECE_HEXDEC_LONG] /
     * [PIECE_HEXDEC_SHORT] 拼的正十六边形。
     *
     * 保留这两个常量是因为棋盘薄板等处仍在引用，删掉会牵动无关代码。
     */
    const val PIECE_SIDE_MIN = 2.8

    /** 棋子侧壁在模型 x/y 上的外缘，与 [PIECE_SIDE_MIN] 关于 8 对称 */
    const val PIECE_SIDE_MAX = 13.2

    /** 棋子圆盘半径，模型单位。贴图圆盘占 92%，16 单位宽换算即得 */
    const val PIECE_DISC_RADIUS = 7.36

    /**
     * 侧壁正十六边形的半平边距（中心到平边的距离），`R·cos(11.25°) = 7.36 × 0.98079`。
     *
     * 八块矩形按 22.5° 一档铺满半圈，并集是正十六边形：十六个顶点都落在
     * [PIECE_DISC_RADIUS] 上，平边到中心距离是这个值。
     *
     * 为什么从八边形升到十六边形：正八边形的平边只到 `R·cos(22.5°) = 0.924R`，
     * 相对外接圆内缩 7.6%，低角度看侧壁仍读得出八条直边。十六边形内缩降到 1.9%，
     * 肉眼基本就是圆。
     */
    const val PIECE_HEXDEC_LONG = 7.2186

    /** 侧壁十六边形的平边半长，`R·sin(11.25°) = 7.36 × 0.19509` */
    const val PIECE_HEXDEC_SHORT = 1.4359

    /**
     * 一枚棋子的资源定义。
     *
     * @param id 资源 id 片段，与 Piece.assetKey 一致，形如 red_horse
     * @param glyph 棋子上的汉字
     * @param red 是否红方
     */
    data class PieceSpec(val id: String, val glyph: String, val red: Boolean)

    /**
     * 14 枚棋子。
     *
     * 红黑用字遵传统写法：红方 帥仕相兵，黑方 將士象卒，馬車两方同字，
     * 黑炮传统写作"砲"。
     */
    val PIECES = listOf(
        PieceSpec("red_king", "帥", true),
        PieceSpec("red_advisor", "仕", true),
        PieceSpec("red_elephant", "相", true),
        PieceSpec("red_horse", "馬", true),
        PieceSpec("red_chariot", "車", true),
        PieceSpec("red_cannon", "炮", true),
        PieceSpec("red_pawn", "兵", true),
        PieceSpec("black_king", "將", false),
        PieceSpec("black_advisor", "士", false),
        PieceSpec("black_elephant", "象", false),
        PieceSpec("black_horse", "馬", false),
        PieceSpec("black_chariot", "車", false),
        PieceSpec("black_cannon", "砲", false),
        PieceSpec("black_pawn", "卒", false),
    )

    /**
     * 五子棋与围棋的棋子。
     *
     * 这两个棋种共用同一对黑白子资源：都是没有字的素色圆盘，
     * 只有世界显示尺寸不同（由各棋种的 `base-scale` 决定），贴图与模型没有区别，
     * 所以不必产出两套。
     *
     * id 按**实际颜色**命名（black_stone 就是黑子），不跟象棋那套"先行方叫 red"的规则。
     * 象棋沿用 red/black 是因为服主的语言文件与已有资源都是那么写的，
     * 而素色子是新资源，没有历史包袱，没必要背上"red_stone 其实是黑子"这种错位 ——
     * 那种命名迟早会有人照字面改错。运行期的映射见 `Piece.assetKey`。
     */
    val STONES = listOf(
        StoneSpec("black_stone", black = true),
        StoneSpec("white_stone", black = false),
    )

    /**
     * 一枚素色棋子的资源定义。
     *
     * @param id 资源 id 片段，与 Piece.assetKey 一致
     * @param black 画黑子还是白子
     */
    data class StoneSpec(val id: String, val black: Boolean)
}
