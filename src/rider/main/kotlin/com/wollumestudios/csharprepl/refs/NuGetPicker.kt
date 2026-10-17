package com.wollumestudios.csharprepl.refs

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.io.HttpRequests
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.wollumestudios.csharprepl.host.ReferenceInfo
import java.io.File
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.Timer
import javax.swing.event.DocumentEvent

/**
 * + Reference › NuGet package…: the solution's explicit packages (no typing), a search over nuget.org, and a version
 * combo. A package id typed into the search field works on its own: versions are looked up for it, and the combo is
 * editable, so a failing search never blocks adding a package.
 */
class NuGetPicker private constructor(private val project: Project) : DialogWrapper(project) {
    private class Entry(val id: String, val preferredVersion: String?, val primary: String, val secondary: String)

    private val solutionModel = DefaultListModel<Entry>()
    private val resultModel = DefaultListModel<Entry>()
    private val solutionList = JBList(solutionModel)
    private val resultList = JBList(resultModel)
    private val searchField = JBTextField()
    private val searchStatus = JBLabel(" ")
    private val targetLabel = JBLabel(" ")
    private val versionCombo = JComboBox<String>().apply { isEditable = true }
    private val prerelease = JBCheckBox("Show prerelease")
    private val versionStatus = JBLabel(" ")

    private var allVersions = emptyList<String>()
    private var preferred: String? = null
    private var updating = false // programmatic selection/model changes must not look like user input
    private var searchJob: ProgressIndicator? = null
    private var versionsJob: ProgressIndicator? = null
    private val debounce = Timer(300) { onQuery() }.apply { isRepeats = false }

    var result: ReferenceInfo? = null
        private set

    init {
        title = "Reference NuGet Package"
        solutionList.emptyText.text = "Scanning projects…"
        resultList.emptyText.text = "Type to search nuget.org"
        for (list in listOf(solutionList, resultList)) {
            list.cellRenderer = EntryRenderer
            list.addListSelectionListener { e ->
                val entry = list.selectedValue
                if (e.valueIsAdjusting || updating || entry == null) return@addListSelectionListener
                updating = true
                (if (list === solutionList) resultList else solutionList).clearSelection()
                updating = false
                select(entry.id, entry.preferredVersion)
            }
        }
        searchField.emptyText.text = "Search nuget.org, or type an exact package id"
        searchField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                updating = true
                solutionList.clearSelection()
                resultList.clearSelection()
                updating = false
                debounce.restart()
            }
        })
        prerelease.addActionListener { refreshVersions(keepTyped = true) }
        init()
        scanSolution()
    }

    override fun getPreferredFocusedComponent(): JComponent = searchField

    override fun createCenterPanel(): JComponent {
        val versionRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            versionCombo.preferredSize = JBUI.size(200, versionCombo.preferredSize.height)
            versionCombo.maximumSize = versionCombo.preferredSize
            add(versionCombo)
            add(javax.swing.Box.createHorizontalStrut(JBUI.scale(12)))
            add(prerelease)
            add(javax.swing.Box.createHorizontalGlue())
        }
        return FormBuilder.createFormBuilder()
            .addComponent(JBLabel("Packages in this solution"))
            .addComponent(JBScrollPane(solutionList).apply { preferredSize = JBUI.size(640, 130) })
            .addVerticalGap(8)
            .addComponent(searchField)
            .addComponent(searchStatus)
            .addComponent(JBScrollPane(resultList).apply { preferredSize = JBUI.size(640, 170) })
            .addVerticalGap(8)
            .addLabeledComponent("Package:", targetLabel)
            .addLabeledComponent("Version:", versionRow)
            .addComponent(versionStatus)
            .panel
    }

    override fun doValidate(): ValidationInfo? {
        val id = targetId()
        if (!ID.matches(id)) return ValidationInfo("Pick a package or type its exact id", searchField)
        if (versionText().isEmpty()) return ValidationInfo("Pick or type a version", versionCombo)
        return null
    }

    override fun doOKAction() {
        result = ReferenceInfo("nuget", targetId(), versionText())
        super.doOKAction()
    }

    override fun dispose() {
        debounce.stop()
        searchJob?.cancel()
        versionsJob?.cancel()
        super.dispose()
    }

    private fun targetId() = (solutionList.selectedValue ?: resultList.selectedValue)?.id ?: searchField.text.trim()

    private fun versionText() = versionCombo.editor.item?.toString()?.trim().orEmpty()

    private fun onUi(indicator: ProgressIndicator, block: () -> Unit) = ApplicationManager.getApplication().invokeLater({
        if (!indicator.isCanceled && !isDisposed) block()
    }, ModalityState.any())

    /** Off the EDT: GETs [url]; [done] (on the EDT) gets the body or a one-line error. A newer call cancels this one. */
    private fun get(url: String, indicator: ProgressIndicator, done: (Result<String>) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val outcome = try {
                Result.success(HttpRequests.request(url).accept("application/json").connectTimeout(8000).readTimeout(15000).readString(indicator))
            } catch (e: ProcessCanceledException) {
                return@executeOnPooledThread
            } catch (e: Exception) {
                Result.failure(e)
            }
            onUi(indicator) { done(outcome) }
        }
    }

    private fun describe(e: Throwable) = when {
        e is HttpRequests.HttpStatusException && e.statusCode == 404 -> "not found on nuget.org"
        else -> e.message?.lineSequence()?.firstOrNull()?.take(120) ?: e.javaClass.simpleName
    }

    private fun scanSolution() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val files = RiderProjectModel.solutionProjects(project).ifEmpty { project.basePath?.let { SolutionPackages.findProjectFiles(File(it)) }.orEmpty() }
            val packages = SolutionPackages.scan(files)
            ApplicationManager.getApplication().invokeLater({
                if (isDisposed) return@invokeLater
                packages.forEach { p ->
                    solutionModel.addElement(Entry(p.id, p.preferredVersion, p.id, p.uses.joinToString("; ") {
                        it.version + (if (it.isProperty) " (MSBuild property)" else "") + " in " + it.projects.joinToString(", ")
                    }))
                }
                solutionList.emptyText.text = "No explicit NuGet packages in the solution's projects"
            }, ModalityState.any())
        }
    }

    /** After the debounce: search for the text, and (when no list entry is selected) look up versions of it as an exact id. */
    private fun onQuery() {
        val q = searchField.text.trim()
        searchJob?.cancel()
        if (q.isEmpty()) {
            resultModel.clear()
            searchStatus.text = " "
            resultList.emptyText.text = "Type to search nuget.org"
            if (solutionList.selectedValue == null) select("", null)
            return
        }
        if (solutionList.selectedValue == null && resultList.selectedValue == null) select(q, null)
        searchStatus.text = "Searching nuget.org…"
        val indicator = EmptyProgressIndicator().also { searchJob = it }
        get(NuGetApi.searchUrl(q), indicator) { r ->
            r.mapCatching { NuGetApi.parseSearch(it) }.onSuccess { hits ->
                resultModel.clear()
                hits.forEach { h ->
                    resultModel.addElement(Entry(h.id, null, "${h.id}  ${h.version}",
                        listOf("${NuGetApi.formatDownloads(h.downloads)} downloads", h.description).filter { it.isNotEmpty() }.joinToString(" · ")))
                }
                resultList.emptyText.text = "No packages found"
                searchStatus.text = " "
            }.onFailure {
                searchStatus.text = "Search failed: ${describe(it)}. You can still type an exact package id."
            }
        }
    }

    /** Sets the target package and loads its versions; [preferredVersion] (the one the solution uses) is selected when listed. */
    private fun select(id: String, preferredVersion: String?) {
        versionsJob?.cancel()
        preferred = preferredVersion
        allVersions = emptyList()
        targetLabel.text = id.ifEmpty { " " }
        if (!ID.matches(id)) {
            versionStatus.text = " "
            refreshVersions(keepTyped = false)
            return
        }
        versionStatus.text = "Loading versions…"
        refreshVersions(keepTyped = false)
        val indicator = EmptyProgressIndicator().also { versionsJob = it }
        get(NuGetApi.versionsUrl(id), indicator) { r ->
            r.mapCatching { NuGetApi.parseVersions(it) }.onSuccess {
                allVersions = it
                versionStatus.text = if (it.isEmpty()) "No versions found" else " "
                refreshVersions(keepTyped = true)
            }.onFailure {
                versionStatus.text = "Could not load versions: ${describe(it)}. Type a version."
            }
        }
    }

    /** Fills the combo from [allVersions] (+ the solution's version); [keepTyped] keeps a version the user typed. */
    private fun refreshVersions(keepTyped: Boolean) {
        val typed = if (keepTyped) versionText() else ""
        val shown = NuGetApi.sortVersions(allVersions, prerelease.isSelected).toMutableList()
        val pref = preferred?.takeIf { it.isNotEmpty() }
        if (pref != null && pref !in shown) shown.add(0, pref)
        versionCombo.removeAllItems()
        shown.forEach(versionCombo::addItem)
        val choice = pref ?: typed.takeIf { it in shown } ?: shown.firstOrNull() ?: typed
        versionCombo.selectedItem = choice
        versionCombo.editor.item = choice
    }

    private object EntryRenderer : ColoredListCellRenderer<Entry>() {
        override fun customizeCellRenderer(list: JList<out Entry>, value: Entry?, index: Int, selected: Boolean, hasFocus: Boolean) {
            value ?: return
            append(value.primary, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            if (value.secondary.isNotEmpty()) append("   " + value.secondary, SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }

    companion object {
        private val ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

        /** Shows the picker; the chosen package as a `nuget` reference, or null when cancelled. */
        fun choose(project: Project): ReferenceInfo? = NuGetPicker(project).let { if (it.showAndGet()) it.result else null }
    }
}
