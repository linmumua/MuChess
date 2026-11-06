package linmumua.chess.game

import java.util.UUID
import linmumua.chess.board.BoardFacing

/**
 * [BoardRecord] 与 YAML 映射之间的转换。
 *
 * 独立成对象是为了能单测往返：坐标或朝向写错不会抛异常，
 * 只会让恢复出来的棋盘悄悄偏一格，必须靠 round-trip 测试兜住。
 *
 * 写出用 [LinkedHashMap] 保序，符合项目对 SnakeYAML 输出的要求。
 */
object BoardRecordCodec {

    /** 顶层键，boards.yml 的结构是 `boards: [ {...}, {...} ]` */
    const val ROOT_KEY = "boards"

    /** 单条记录 → YAML 映射 */
    fun encode(record: BoardRecord): Map<String, Any?> = linkedMapOf(
        "id" to record.id.toString(),
        "world" to record.world,
        "origin-x" to record.originX,
        "origin-y" to record.originY,
        "origin-z" to record.originZ,
        "surface-y" to record.surfaceY,
        "facing" to record.facing.name,
        "game-type" to record.gameType.id,
        "fen" to record.fen,
        "phase" to record.phase.name,
        "red-player" to record.redPlayer?.toString(),
        "black-player" to record.blackPlayer?.toString(),
        "visual-handle" to record.visualHandle,
        "bgm-enabled" to record.bgmEnabled,
        // null 照样写出来：SnakeYAML 会写成 `turn-timeout-seconds: null`，
        // 服主看得见这一项存在、当前是"跟随全局"，比键干脆消失更容易理解
        "turn-timeout-seconds" to record.turnTimeoutSeconds,
    )

    /** 整份存档 → YAML 映射 */
    fun encodeAll(records: Collection<BoardRecord>): Map<String, Any?> =
        linkedMapOf(ROOT_KEY to records.map { encode(it) })

    /**
     * YAML 映射 → 单条记录。
     *
     * 数据坏掉时返回 null 而不是抛异常：一副棋盘的记录写坏了，
     * 应该跳过这一副并记日志，不该让其余几十副棋盘一起恢复不了。
     * 调用方负责把 null 的那条写进警告日志。
     */
    fun decode(node: Map<String, Any?>): BoardRecord? {
        val id = node.uuid("id") ?: return null
        val world = node["world"] as? String ?: return null
        val fen = node["fen"] as? String ?: return null
        val facing = (node["facing"] as? String)
            ?.let { name -> BoardFacing.entries.firstOrNull { it.name == name } }
            ?: return null
        val phase = (node["phase"] as? String)
            ?.let { name -> GamePhase.entries.firstOrNull { it.name == name } }
            ?: GamePhase.WAITING
        // 老存档没有 game-type：1.10.0 之前只有象棋，缺字段一律当象棋。
        // 写错棋种名（服主手改存档）也退象棋而不是丢掉整条记录 ——
        // 局面串还在，退象棋至少能让棋盘恢复出来。
        val gameType = (node["game-type"] as? String)
            ?.let { GameType.byId(it) }
            ?: GameType.XIANGQI
        return BoardRecord(
            id = id,
            world = world,
            originX = node.int("origin-x") ?: return null,
            originY = node.int("origin-y") ?: return null,
            originZ = node.int("origin-z") ?: return null,
            surfaceY = node.double("surface-y") ?: return null,
            facing = facing,
            gameType = gameType,
            fen = fen,
            phase = phase,
            redPlayer = node.uuid("red-player"),
            blackPlayer = node.uuid("black-player"),
            visualHandle = node["visual-handle"] as? String,
            // 1.12.0 之前没有这个键：缺字段当"没人关过音乐"，即跟随全局总闸。
            // 类型写错（服主手改成字符串）也退 true，理由相同 —— 音乐开关不值得丢整条棋盘记录。
            bgmEnabled = node["bgm-enabled"] as? Boolean ?: true,
            // 缺字段、写成 null、或类型写错都当 null（跟随全局），理由见
            // BoardRecord.turnTimeoutSeconds：这里不能退成 0，那是"不限时"这个显式选择。
            // 负数同样退 null —— 手改出 -1 时按跟随全局处理比按不限时处理更接近服主本意。
            turnTimeoutSeconds = node.int("turn-timeout-seconds")?.takeIf { it >= 0 },
        )
    }

    /**
     * 整份存档 → 记录列表。
     *
     * 坏掉的条目直接丢弃，返回值只含能用的记录。
     * 丢弃了几条由调用方对比数量后记日志。
     */
    @Suppress("UNCHECKED_CAST")
    fun decodeAll(root: Map<String, Any?>): List<BoardRecord> {
        val list = root[ROOT_KEY] as? List<*> ?: return emptyList()
        return list.mapNotNull { entry ->
            (entry as? Map<String, Any?>)?.let { decode(it) }
        }
    }

    /** 取 UUID，格式不对当缺失处理 */
    private fun Map<String, Any?>.uuid(key: String): UUID? {
        val raw = this[key] as? String ?: return null
        return runCatching { UUID.fromString(raw) }.getOrNull()
    }

    private fun Map<String, Any?>.int(key: String): Int? = (this[key] as? Number)?.toInt()

    private fun Map<String, Any?>.double(key: String): Double? = (this[key] as? Number)?.toDouble()
}
