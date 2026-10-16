package com.wollumestudios.csharprepl.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel

/** Settings › Tools › C# REPL. */
class ReplConfigurable : BoundConfigurable("C# REPL") {
    override fun createPanel() = panel {
        val s = ReplSettings.state
        row {
            checkBox("Run on Enter (adds a missing ;)")
                .comment("Off: Enter collects lines until the command is complete; Shift+Enter runs now.")
                .bindSelected(s::autoRun)
        }
        row("Variables:") {
            comboBox(VariablesPosition.entries, textListCellRenderer { it?.label })
                .bindItem({ s.variablesPosition }, { s.variablesPosition = it ?: VariablesPosition.RIGHT })
        }
        row {
            checkBox("Ignore changes to referenced projects forever")
                .comment("No banner when a referenced project changes after it was added.")
                .bindSelected(s::ignoreChangesForever)
        }
    }

    override fun apply() {
        super.apply()
        ReplSettings.changed()
    }
}
