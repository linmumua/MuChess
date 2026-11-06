package linmumua.chess.game

import java.util.UUID
import linmumua.chess.board.BoardFacing

/**
 * boards.yml 里一副棋盘的存档记录。
 *
 * 故意只用原始类型与字符串：不含 Bukkit 的 World / Location，
 * 因此序列化与反序列化能脱离服务端单测，也避免恢复时世界还没加载就拿不到对象。
 *
 * 局面用规则层的 FEN 串存（[fen]），不自己另造一套棋子编码。
 */
data class BoardRecord(
    /** 棋盘身份，与 [GameSession.id] 一致 */
    val id: UUID,
    /** 世界名，恢复时按名字查世界 */
    val world: String,
    /** 2x2 占位区域最小角的方块坐标 */
    val originX: Int,
    val originY: Int,
    val originZ: Int,
    /** 棋盘面高度；新版本恢复时按当前家具厚度重算，字段保留用于兼容旧存档 */
    val surfaceY: Double,
    /** 棋盘朝向 */
    val facing: BoardFacing,
    /**
     * 棋种。
     *
     * 老存档（1.10.0 之前）没有这个字段，那时只有象棋，读取时按 [GameType.XIANGQI] 兜底。
     * 不能靠猜 —— FEN 串的行数虽然能区分 9×10 与 15×15，但那是巧合而不是约定，
     * 明确存一份棋种标记才不会在加新棋种时出错。
     */
    val gameType: GameType,
    /** 局面 FEN 串，由对应棋种的规则层产出 */
    val fen: String,
    /** 对局阶段 */
    val phase: GamePhase,
    /** 红方玩家，空座位为 null */
    val redPlayer: UUID?,
    /** 黑方玩家，空座位为 null */
    val blackPlayer: UUID?,
    /** 棋盘家具句柄 id，CE 缺失时为 null */
    val visualHandle: String?,
    /**
     * 这一局是否允许放开局背景音乐（只对围棋有意义）。
     *
     * 它是**每局的闸**，`config.yml` 的 `go.opening-bgm.enabled` 是总闸，两个都为真才响。
     * 默认 `true` 表示"这局没人关过音乐"，所以总闸默认关着的情况下也不会有人被吵到。
     *
     * 带默认值是为了让老存档与既有构造点不必逐个改：1.12.0 之前的 boards.yml 没有这个键，
     * 读出来当没关过（见 [BoardRecordCodec.decode]）。
     */
    val bgmEnabled: Boolean = true,
    /**
     * 本局单独设定的每步时限，单位秒；null 表示跟随 config.yml 的全局值。
     *
     * 与 [bgmEnabled] 的兜底方向不同：这里的"缺字段"必须落成 null 而不是某个数字，
     * 因为 0（这局不限时）是玩家可能显式选的值。缺字段当 0 会把所有旧棋盘变成
     * "永久不限时"，服主之后调全局时限对它们全都失效。
     *
     * 详见 [ChessGame.turnTimeoutSeconds]。
     */
    val turnTimeoutSeconds: Int? = null,
)

/** 存档中的棋盘外观句柄格式，纯字符串解析，不依赖 Bukkit 或 CE */
sealed interface StoredBoardVisualHandle {

    /** 当前单家具句柄：base entity UUID */
    data class Furniture(val uuid: UUID) : StoredBoardVisualHandle

    /** 1.1.0 四方块句柄：world:x:y:z */
    data class LegacyBlocks(
        val world: String,
        val x: Int,
        val y: Int,
        val z: Int,
    ) : StoredBoardVisualHandle

    /** 无法识别的损坏或未来格式 */
    data class Unknown(val raw: String) : StoredBoardVisualHandle
}

/** 当前 UUID 句柄与旧坐标句柄的兼容解析器 */
object BoardVisualHandleFormat {

    fun parse(raw: String?): StoredBoardVisualHandle? {
        if (raw == null) {
            return null
        }
        runCatching { UUID.fromString(raw) }.getOrNull()?.let {
            return StoredBoardVisualHandle.Furniture(it)
        }

        // 世界名可能含冒号，所以从右向左取三段坐标，剩余整体是世界名。
        val parts = raw.split(':')
        if (parts.size >= 4) {
            val z = parts[parts.lastIndex].toIntOrNull()
            val y = parts[parts.lastIndex - 1].toIntOrNull()
            val x = parts[parts.lastIndex - 2].toIntOrNull()
            val world = parts.subList(0, parts.size - 3).joinToString(":")
            if (x != null && y != null && z != null && world.isNotEmpty()) {
                return StoredBoardVisualHandle.LegacyBlocks(world, x, y, z)
            }
        }
        return StoredBoardVisualHandle.Unknown(raw)
    }
}
