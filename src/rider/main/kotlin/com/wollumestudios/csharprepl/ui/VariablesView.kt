package com.wollumestudios.csharprepl.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.treetable.ListTreeTableModelOnColumns
import com.intellij.ui.treeStructure.treetable.TreeColumnInfo
import com.intellij.ui.treeStructure.treetable.TreeTable
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.tree.TreeUtil
import com.wollumestudios.csharprepl.ReplSession
import com.wollumestudios.csharprepl.host.Var
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

/** Name / Value / Type, like the debugger's Variables: values with a handle get children from `expand` when opened. */
class VariablesView(private val session: ReplSession) : JPanel(BorderLayout()) {
    private class VarNode(val v: Var) : DefaultMutableTreeNode() {
        var loaded = v.handle == null
        init { if (!loaded) add(DefaultMutableTreeNode("…")) }
        override fun toString() = v.name
    }

    private val root = DefaultMutableTreeNode()
    private val model = ListTreeTableModelOnColumns(root, arrayOf(
        TreeColumnInfo("Name"),
        column("Value") { it.value },
        column("Type") { it.type },
    ))
    val table = TreeTable(model).apply {
        setRootVisible(false)
        tree.showsRootHandles = true
        tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(event: TreeExpansionEvent) { (event.path.lastPathComponent as? VarNode)?.let(::load) }
            override fun treeCollapsed(event: TreeExpansionEvent) {}
        })
    }

    init {
        add(JBScrollPane(table), BorderLayout.CENTER)
    }

    /** Shows [vars] as the top level (after a run; expansions start closed). */
    fun show(vars: List<Var>) {
        root.removeAllChildren()
        vars.forEach { root.add(VarNode(it)) }
        model.reload()
    }

    /** Selects the top-level variable [name] (the history's `[inspect ›]` uses `$exception`). */
    fun select(name: String) {
        val node = root.children().toList().firstOrNull { (it as VarNode).v.name == name } as? VarNode ?: return
        val path = TreePath(arrayOf(root, node))
        table.tree.selectionPath = path
        TreeUtil.scrollToVisible(table.tree, path, false)
        table.requestFocusInWindow()
    }

    private fun load(node: VarNode) {
        if (node.loaded) return
        node.loaded = true
        session.expand(node.v.handle ?: return).whenComplete { children, error ->
            ApplicationManager.getApplication().invokeLater({
                node.removeAllChildren()
                children?.forEach { node.add(VarNode(it)) }
                if (error != null) node.add(VarNode(Var("(error)", "", (error.cause ?: error).message ?: "", null)))
                model.nodeStructureChanged(node)
            }, ModalityState.any())
        }
    }

    private fun column(name: String, value: (Var) -> String) = object : ColumnInfo<Any, String>(name) {
        override fun valueOf(item: Any?) = (item as? VarNode)?.v?.let(value) ?: ""
    }
}
