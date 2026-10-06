/** What the import did, adapted and skipped, written next to the output for review. */
internal class Report {
    val counts = sortedMapOf<String, Int>()
    val skipped = sortedMapOf<String, MutableList<String>>()
    val adapted = sortedMapOf<String, MutableList<String>>()

    fun count(what: String, by: Int = 1) {
        if (by > 0) counts[what] = (counts[what] ?: 0) + by
    }

    fun skip(reason: String, what: String) {
        skipped.getOrPut(reason) { mutableListOf() }.add(what)
    }

    /** Content imported with a change (an ingredient replaced...), listed apart from what is left out. */
    fun adapt(reason: String, what: String) {
        adapted.getOrPut(reason) { mutableListOf() }.add(what)
    }

    fun summary() = "Mekanism import: " + counts.entries.joinToString { "${it.value} ${it.key}" } +
            "; skipped " + skipped.values.sumOf { it.size } + " (see import-report.txt)"

    fun format() = buildString {
        appendLine("# Mekanism import report")
        counts.forEach { (what, count) -> appendLine("$count $what") }
        section("Skipped", skipped)
        section("Adapted", adapted)
    }

    private fun StringBuilder.section(title: String, entries: Map<String, List<String>>) {
        entries.forEach { (reason, items) ->
            appendLine()
            appendLine("## $title: $reason (${items.size})")
            items.sorted().forEach { appendLine("- $it") }
        }
    }
}
