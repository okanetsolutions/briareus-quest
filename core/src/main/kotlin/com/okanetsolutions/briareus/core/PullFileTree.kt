package com.okanetsolutions.briareus.core

/** A sorted file tree with single-directory chains compacted, matching the desktop file picker. */
data class PullFileTree(val name: String, val path: String, val file: PullFile?, val children: List<PullFileTree>) {
    data class Row(val node: PullFileTree, val depth: Int)

    companion object {
        fun build(files: List<PullFile>, prefix: String = ""): List<PullFileTree> {
            val (leaves, nested) = files.partition { '/' !in it.filename.removePrefix(prefix) }
            val nodes = leaves.map { PullFileTree(it.filename.removePrefix(prefix), it.filename, it, emptyList()) }
            val folders = nested.groupBy { it.filename.removePrefix(prefix).substringBefore('/') }.map { (name, group) ->
                val path = prefix + name
                val children = build(group, "$path/")
                val only = children.singleOrNull()?.takeIf { it.file == null }
                if (only != null) only.copy(name = "$name/${only.name}") else PullFileTree(name, path, null, children)
            }
            return (nodes + folders).sortedWith(compareBy<PullFileTree> { it.file != null }.thenBy { it.name.lowercase(java.util.Locale.ROOT) }.thenBy { it.name })
        }

        fun rows(nodes: List<PullFileTree>, collapsed: Set<String>, depth: Int = 0): List<Row> = nodes.flatMap { node ->
            listOf(Row(node, depth)) + if (node.path in collapsed) emptyList() else rows(node.children, collapsed, depth + 1)
        }
    }
}
