package linmumua.chess.game

import java.io.File
import linmumua.chess.config.ChessConfig
import linmumua.chess.config.Yaml
import taboolib.common.platform.function.info
import taboolib.common.platform.function.warning

/**
 * boards.yml 的读写。
 *
 * 项目禁止在主线程做文件 I/O，所以对外提供的写入接口都要求调用方在异步线程里跑；
 * 这里只负责"给我一份记录，我写文件"，不自己起线程也不回主线程 ——
 * 线程调度归调用方（[linmumua.chess.ChessPlugin] 的调度任务）管，
 * 避免存储层偷偷切线程导致时序难以推理。
 *
 * YAML 只走 [Yaml]（SnakeYAML），不使用 Bukkit YamlConfiguration。
 */
object BoardStorage {

    /** 运行时数据文件名 */
    private const val FILE_NAME = "boards.yml"

    /** 数据文件位置 */
    private fun file(): File = ChessConfig.dataFile(FILE_NAME)

    /**
     * 读出全部棋盘记录。
     *
     * 文件不存在时返回空列表 —— 首次启动没有存档是正常情况，不该报错。
     * 文件存在但解析失败时返回空列表并记警告：这时候宁可不恢复棋盘，
     * 也不能拿半截数据摆出错位的棋局。
     */
    fun load(): List<BoardRecord> {
        val target = file()
        if (!target.exists()) {
            return emptyList()
        }
        return try {
            val root = target.inputStream().use { Yaml.loadMap(it) }
            val records = BoardRecordCodec.decodeAll(root)
            val rawCount = (root[BoardRecordCodec.ROOT_KEY] as? List<*>)?.size ?: 0
            if (rawCount > records.size) {
                warning("$FILE_NAME 里有 ${rawCount - records.size} 条棋盘记录格式不对，已跳过")
            }
            records
        } catch (error: Exception) {
            // 不允许吞异常：存档读不了服主必须知道原因
            warning("$FILE_NAME 解析失败，本次不恢复棋盘：${error.message}")
            emptyList()
        }
    }

    /**
     * 写出全部棋盘记录。
     *
     * **必须在异步线程调用。** 先写临时文件再原子替换，
     * 避免写一半时关服导致存档只剩半截 YAML。
     */
    fun save(records: Collection<BoardRecord>) {
        val target = file()
        try {
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, "$FILE_NAME.tmp")
            temp.writer(Charsets.UTF_8).use { writer ->
                Yaml.dump(BoardRecordCodec.encodeAll(records), writer)
            }
            // File.renameTo 在同一分区上是原子的，跨分区才会失败；
            // 临时文件与目标在同一目录，所以这里可以依赖它
            if (!temp.renameTo(target)) {
                // 某些文件系统上目标存在时 renameTo 会失败，退回先删再改名
                target.delete()
                if (!temp.renameTo(target)) {
                    warning("$FILE_NAME 落盘失败：临时文件改名没成功")
                    return
                }
            }
        } catch (error: Exception) {
            warning("$FILE_NAME 落盘失败：${error.message}")
        }
    }

    /** 落盘并记一行日志，供关服与定时任务复用 */
    fun flush(records: Collection<BoardRecord>) {
        save(records)
        info("已保存 ${records.size} 副棋盘")
    }
}
