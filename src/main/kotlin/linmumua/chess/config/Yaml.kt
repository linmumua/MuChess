package linmumua.chess.config

import java.io.InputStream
import java.io.Writer
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.representer.Representer

/**
 * 本项目唯一的 YAML 读写入口。
 *
 * 全项目运行期 YAML 只走 SnakeYAML，不使用 Bukkit YamlConfiguration，也不使用 Jackson YAML。
 * 所有服主可改的文件都必须经过这里，以保证 [LoaderOptions] 的安全限制不会被绕过。
 */
object Yaml {

    /**
     * 服主可编辑文件的解析限制。
     *
     * 这些限制不是可选项：配置文件是外部输入，缺了限制就等于把解析器暴露给
     * 别名炸弹和超深嵌套。重复键直接报错而不是静默取后者，避免服主改错了却不知道。
     */
    private fun loaderOptions(): LoaderOptions = LoaderOptions().apply {
        isAllowDuplicateKeys = false
        maxAliasesForCollections = 64
        codePointLimit = 8 * 1024 * 1024
        nestingDepthLimit = 32
        isProcessComments = false
    }

    /** 写出用的块风格设置，保证生成的文件是人能读的 */
    private fun dumperOptions(): DumperOptions = DumperOptions().apply {
        defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        defaultScalarStyle = DumperOptions.ScalarStyle.PLAIN
        indent = 2
        isPrettyFlow = true
        width = 200
        splitLines = false
    }

    /** 每次调用都新建实例，SnakeYAML 的 Yaml 对象不是线程安全的 */
    private fun newYaml(): Yaml {
        val options = loaderOptions()
        val representer = Representer(dumperOptions())
        return Yaml(SafeConstructor(options), representer, dumperOptions(), options)
    }

    /**
     * 读取一个 YAML 映射。
     *
     * 空文件或顶层不是映射时返回空表，让调用方统一走"取不到就用默认值"的路径，
     * 不必到处判 null。
     */
    @Suppress("UNCHECKED_CAST")
    fun loadMap(input: InputStream): Map<String, Any?> {
        val loaded = input.reader(Charsets.UTF_8).use { newYaml().load<Any?>(it) }
        return loaded as? Map<String, Any?> ?: emptyMap()
    }

    /** 写出一个 YAML 映射，键顺序由传入的 Map 决定，请用 LinkedHashMap */
    fun dump(data: Map<String, Any?>, writer: Writer) {
        writer.use { newYaml().dump(data, it) }
    }

    /** 写出成字符串 */
    fun dumpToString(data: Map<String, Any?>): String = newYaml().dump(data)
}
