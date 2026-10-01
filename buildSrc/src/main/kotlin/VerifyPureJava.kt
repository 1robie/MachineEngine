import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

abstract class VerifyPureJava : DefaultTask() {
    @get:Input
    abstract val projectPath: Property<String>

    /** `group:module` coordinates found on the checked classpaths. */
    @get:Input
    abstract val modules: ListProperty<String>

    @get:Input
    abstract val forbiddenGroups: ListProperty<String>

    @TaskAction
    fun verify() {
        val forbidden = forbiddenGroups.get()
        val violations = modules.get().distinct().sorted().filter { coordinates ->
            val group = coordinates.substringBefore(':')
            forbidden.any { group == it || group.startsWith("$it.") }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Module '${projectPath.get()}' must stay server-free but depends on: ${violations.joinToString()}"
            )
        }
    }
}

/** Collects every external module reachable from [root]. */
fun collectModules(root: ResolvedComponentResult): List<String> {
    val seen = LinkedHashSet<ResolvedComponentResult>()
    val pending = ArrayDeque(listOf(root))
    while (pending.isNotEmpty()) {
        val component = pending.removeFirst()
        if (!seen.add(component)) continue
        component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { pending.add(it.selected) }
    }
    return seen.mapNotNull { (it.id as? ModuleComponentIdentifier)?.let { id -> "${id.group}:${id.module}" } }
}
