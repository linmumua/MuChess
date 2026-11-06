package linmumua.chess.game

import linmumua.chess.board.BoardAnchor
import linmumua.chess.board.BoardFacing
import linmumua.chess.board.BoardFootprint
import linmumua.chess.compat.CraftEngineBridge
import linmumua.chess.config.BoardSettings
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.util.Vector

/**
 * 棋盘放置的选址与校验。
 *
 * 棋盘逻辑占位仍是 2x2。棋盘只由 CE 家具放置：以四格交界的整数 pivot 为真源，
 * 再反推出最小角。原来还有一条"命令在玩家脚下建"的路径，随 `/chess place`
 * 一起去掉了，现在只剩这一套坐标规则，不存在分叉。
 */
object BoardPlacement {

    /**
     * 按 CE 家具实体的位置算标定。
     *
     * 家具中心的落点取决于 CE `alignment`，而它由占位边长的奇偶决定，所以浮点位置
     * 取整成 pivot 这一步不能一刀切用 round —— 交给 [BoardFootprint.pivotFromFurnitureCenter]
     * 按边长奇偶分流：2x2 是 corner（中心在整数交界，round 兼做浮点尾差防御），
     * 围棋 3x3 是 center（中心在格心，即整数 + 0.5，必须 floor）。
     * 早先这里写死 round，围棋整盘沿 +X/+Z 偏了一整格。
     *
     * CE 的 base yaw 比玩家/视觉 yaw 多半圈，朝向换算统一交给 [BoardFacing.fromFurnitureYaw]。
     */
    fun anchorForFurniture(
        location: Location,
        settings: BoardSettings,
        gameType: GameType = GameType.XIANGQI,
    ): BoardAnchor {
        val world = location.world ?: error("放置棋盘时家具所在世界为空")
        val (pivotX, pivotZ) = BoardFootprint.pivotFromFurnitureCenter(
            location.x,
            location.z,
            gameType.footprintSize,
        )
        // Y 不能直接用 location.blockY：CE 允许把家具放进草这类可替换方块所在的格子，
        // 那时 blockY 取到的是草那一层，棋盘就浮在草高上。往下找到第一个实体方块，
        // 棋盘落在它顶上，草地与裸土的放置高度才一致。
        val pivotY = groundLevel(world, pivotX, location.blockY, pivotZ)
        // pivot → 最小角要按本棋种的占位边长反推，围棋 3x3 的偏移与 2x2 不同
        val (originX, originY, originZ) =
            BoardFootprint.originOfPivot(pivotX, pivotY, pivotZ, gameType.footprintSize)
        return anchor(
            world = world,
            originX = originX,
            originY = originY,
            originZ = originZ,
            facing = BoardFacing.fromFurnitureYaw(location.yaw),
            settings = settings,
            gameType = gameType,
        )
    }

    /**
     * 按显式 origin 与朝向算标定，供 `/chess debug test` 压测放置用。
     *
     * 与 [anchorForFurniture] 的区别：那边要从家具实体位置反推 origin 并向下找承托层，
     * 这里 origin 由调用方按网格算好，Y 直接用给定值不再下修 —— 压测在平地上铺网格，
     * 逐副再扫一次地面只会让计时掺进无关的方块查询。
     */
    fun anchorAt(
        world: World,
        origin: Vector,
        facing: BoardFacing,
        settings: BoardSettings,
        gameType: GameType,
    ): BoardAnchor = anchor(
        world = world,
        originX = origin.blockX,
        originY = origin.blockY,
        originZ = origin.blockZ,
        facing = facing,
        settings = settings,
        gameType = gameType,
    )

    /** 统一构造标定，棋盘面位于薄板顶面再加配置抬升 */
    private fun anchor(
        world: World,
        originX: Int,
        originY: Int,
        originZ: Int,
        facing: BoardFacing,
        settings: BoardSettings,
        gameType: GameType,
    ): BoardAnchor = BoardAnchor(
        world = world,
        origin = Vector(originX.toDouble(), originY.toDouble(), originZ.toDouble()),
        surfaceY = originY.toDouble() + BoardAnchor.BOARD_THICKNESS + settings.surfaceOffset,
        facing = facing,
        spec = gameType.spec,
        span = gameType.span,
    )

    /**
     * 找出棋盘该占用的格子 Y：向下定位承托方块，棋盘放在它正上方一格。
     *
     * 为什么必须做这一步：CE 的 ground 放置是"点到的面 + 法线方向偏移"，不像原版放方块那样
     * 会把草替换掉。点裸土顶面给出的落点正好在土上方一格，是对的；但草地上点过去，草仍占着
     * 一格，落点就在草的**上方**，棋盘比在裸土上高出一格 —— 这就是棋盘浮空。
     *
     * 所以从落点往下扫，跳过空气与草、花、雪层、藤蔓这些 `isReplaceable` 的植被，
     * 找到第一个真正的实体方块，棋盘落在它顶上。裸土场景下第一步就命中，行为不变。
     *
     * 只向下找不向上：CE 已经判过该处可放，往上挪会离开玩家点的位置。
     *
     * 深度上限是 [MAX_GROUND_SEARCH]（4 格），够穿过两格高草加余量。
     * **超过这个深度就静默退回 startY**，也就是棋盘仍留在 CE 给的落点上。
     * 所以这条修复只能纠正"落点比地面高几格"这一类偏差；如果落点离地面更远
     * （比如从高处俯视点地），棋盘不会被拉下来。
     *
     * @param startY CE 给出的落点 Y
     * @return 棋盘占用的格子 Y；扫不到承托方块时退回 [startY]，保持原行为
     */
    private fun groundLevel(world: World, x: Int, startY: Int, z: Int): Int {
        // 只负责取方块，选址判断全在 selectGroundY 里，那样才测得到
        val probes = (0..MAX_GROUND_SEARCH).map { step ->
            val y = startY - step
            if (y <= world.minHeight) {
                // 探到世界底：当成"没有承托"，交给纯函数退回 startY
                GroundProbe(empty = true, replaceable = true)
            } else {
                val block = world.getBlockAt(x, y, z)
                GroundProbe(empty = block.isEmpty, replaceable = block.blockData.isReplaceable)
            }
        }
        return selectGroundY(startY, probes)
    }

    /**
     * 向下扫描时每一格的判定输入。
     *
     * 只留 [groundLevel] 真正用到的两个属性，让选址逻辑不依赖 Bukkit 而能单测。
     *
     * @param empty 是否空气，对应 `Block.isEmpty`
     * @param replaceable 是否可被放置替换，对应 `BlockData.isReplaceable`
     */
    data class GroundProbe(val empty: Boolean, val replaceable: Boolean)

    /**
     * 从落点向下挑出棋盘该占用的格子 Y。
     *
     * 抽成纯函数是为了能真正测到：`Block` 需要服务端实例，项目没有引 mock 库，
     * 而"哪一格算承托"正是棋盘浮空的判定核心。之前这段逻辑埋在 [groundLevel] 里，
     * 测试只能另建一个同构的局部函数去断言，[groundLevel] 改坏了测试照样绿。
     *
     * 规则：按顺序看每一格，第一个"非空且不可替换"的实体方块就是承托层，
     * 棋盘落在它上面一格。草、花、雪层、藤蔓与空气全部跳过。
     *
     * @param startY CE 给出的落点 Y
     * @param probes 从 startY 开始向下逐格的判定输入，索引 0 即 startY
     * @return 棋盘占用的格子 Y；扫完都没有承托方块时退回 [startY]，保持原行为
     */
    fun selectGroundY(startY: Int, probes: List<GroundProbe>): Int {
        probes.forEachIndexed { step, probe ->
            if (!probe.empty && !probe.replaceable) {
                return startY - step + 1
            }
        }
        return startY
    }

    /** 向下找地面的最大格数，防止在悬空处一路探到世界底 */
    private const val MAX_GROUND_SEARCH = 4

    /**
     * 占位区域内的全部方块。
     *
     * @param size 占位边长，默认 2x2；围棋传 3
     */
    fun footprint(
        world: World,
        origin: Vector,
        size: Int = BoardFootprint.SIZE,
    ): List<Block> =
        BoardFootprint.blocksOf(origin.blockX, origin.blockY, origin.blockZ, size).map { (x, y, z) ->
            world.getBlockAt(x, y, z)
        }

    /**
     * 2x2 范围是否放得下。
     *
     * 家具是实体，不会像旧 `block_item` 那样先占住其中一格，所以两条放置路径都
     * 可以直接检查同一范围，不再需要 `ignore` 例外。
     *
     * 草、花、雪层这类可替换方块不算障碍：放棋盘和放方块一样，它们会被顶掉，
     * 清除交给 [clearReplaceable]。
     */
    fun canPlace(
        world: World,
        origin: Vector,
        settings: BoardSettings,
        size: Int = BoardFootprint.SIZE,
    ): Boolean {
        val checked = selectChecked(footprint(world, origin, size), settings.requireFlatArea)
        return checked.all { it.isPassable || it.isEmpty }
    }

    /**
     * 清掉 2x2 占位范围内的可替换方块。
     *
     * 不清的话棋盘只能贴在草尖上：草是 1 格高的实体方块，棋盘薄板压在它上面就会
     * 整体抬高一截，而棋子按 anchor 的 surfaceY 落点，于是棋盘浮在草上、棋子留在
     * 草根，两者对不上。放方块时草会被顶掉，棋盘按同样的规则处理。
     *
     * 只清 `isReplaceable` 的方块（草、花、雪层、藤蔓等），实体方块一律不动 ——
     * 那是 [canPlace] 的职责，能走到这里说明该范围已经判过可放。
     *
     * 不掉落物品，和放方块顶掉草的表现一致。
     *
     * @return 实际清掉的方块数，供日志与测试核对
     */
    fun clearReplaceable(
        world: World,
        origin: Vector,
        settings: BoardSettings,
        size: Int = BoardFootprint.SIZE,
    ): Int {
        val checked = selectChecked(footprint(world, origin, size), settings.requireFlatArea)
        var cleared = 0
        checked.forEach { block ->
            if (shouldClear(empty = block.isEmpty, replaceable = block.blockData.isReplaceable)) {
                // applyPhysics=false：不触发邻块更新，避免顶掉一株草引发连带掉落
                block.setType(Material.AIR, false)
                cleared++
            }
        }
        return cleared
    }

    /**
     * 某个方块该不该在放棋盘时被顶掉。
     *
     * 抽成纯函数是为了能单测：Bukkit 的 `Block` 需要服务端实例，项目没有引 mock 库，
     * 而"哪些方块该清"这条判断本身是最容易出错的地方。
     *
     * 空气也满足 `isReplaceable`，必须先排掉，否则会对整片空气白跑 `setType`
     * 并把清除数量虚报成 4，日志和测试都会失去意义。
     *
     * @param empty 方块是否为空气，对应 `Block.isEmpty`
     * @param replaceable 方块是否可被放置替换，对应 `BlockData.isReplaceable`
     */
    fun shouldClear(empty: Boolean, replaceable: Boolean): Boolean = !empty && replaceable

    /** require-flat-area 关闭时只检查 origin 所在的首格 */
    fun <T> selectChecked(all: List<T>, requireFlatArea: Boolean): List<T> =
        if (requireFlatArea) all else all.take(1)

    /**
     * 扫描棋盘 2x2 占位层与正下方承托层内是否有 CE 家具阻挡。
     *
     * CE 家具不是实体方块，[canPlace] 的方块检查完全看不见它们。本方法作为补充校验，
     * 扫描占位层（同层重合会导致 hitbox 串道）与承托层（承托层是家具时 groundLevel
     * 会穿过去，棋盘沉进家具里或错层）。
     *
     * 自我误判防护由 [CraftEngineBridge.furnitureBlocking] 负责：传入 [excludeHandle]
     * 可排除本次放置自身的家具，避免 `FurniturePlaceEvent` 触发时自检永远失败。
     *
     * @param world 棋盘所在世界
     * @param origin CE 定位后的 2x2 最小角坐标
     * @param excludeHandle base entity UUID 字符串，排除自身；null 表示不排除
     * @return 被 CE 家具阻挡的方块 Location 列表；无阻挡或 CE 不可用时返回空列表
     */
    fun blockingFurniture(
        world: World,
        origin: Vector,
        excludeHandle: String?,
        size: Int = BoardFootprint.SIZE,
    ): List<Location> {
        val blocks = BoardFootprint.blocksWithSupport(
            origin.blockX,
            origin.blockY,
            origin.blockZ,
            size,
        )
        return CraftEngineBridge.furnitureBlocking(world, blocks, excludeHandle)
    }
}
