/**
 * A CraftEngine item: its translated name (`<lang:key>`, key like `item.mekanized.x`), its model, an
 * optional behavior, and the tags it has (in `settings`).
 */
internal fun itemYaml(nameKey: String, model: String, behavior: Map<String, Any?>? = null, tags: Set<String>? = null):
        MutableMap<String, Any?> {
    val item = linkedMapOf<String, Any?>("data" to mapOf("item_name" to "<!i><lang:$nameKey>"), "model" to model)
    if (behavior != null) item["behavior"] = behavior
    return item.withTags(tags)
}

/** The item with the tags it belongs to, sorted (nothing is added without tags). */
internal fun MutableMap<String, Any?>.withTags(tags: Set<String>?): MutableMap<String, Any?> {
    if (!tags.isNullOrEmpty()) this["settings"] = mapOf("tags" to tags.sorted())
    return this
}

/** A block item: places [block]. */
internal fun blockItemBehavior(block: String): Map<String, Any?> = mapOf("type" to "block_item", "block" to block)
