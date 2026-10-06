import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.File

internal object Ids {
    /** `mekanism:x` and `mekanismgenerators:x` become `mekanized:x`; other namespaces stay. */
    fun rename(id: String): String {
        val tag = id.startsWith("#")
        val bare = id.removePrefix("#")
        val renamed = when {
            bare.startsWith("mekanismgenerators:") -> "mekanized:" + bare.removePrefix("mekanismgenerators:")
            bare.startsWith("mekanism:") -> "mekanized:" + bare.removePrefix("mekanism:")
            else -> bare
        }
        return if (tag) "#$renamed" else renamed
    }

    /** Renames every namespaced reference inside a JSON text (models, animations). */
    fun renameInText(text: String) = text
        .replace("\"mekanismgenerators:", "\"mekanized:")
        .replace("\"mekanism:", "\"mekanized:")
        .replace("\"#mekanismgenerators:", "\"#mekanized:")
        .replace("\"#mekanism:", "\"#mekanized:")
}

internal fun yaml(): Yaml = Yaml(DumperOptions().apply {
    defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
    indent = 2
    width = 160
    splitLines = false
})

internal fun writeYaml(file: File, header: String, content: Map<String, Any?>) {
    file.parentFile.mkdirs()
    file.writeText(header.lines().joinToString("\n") { "# $it".trimEnd() } + "\n" + yaml().dump(content))
}
