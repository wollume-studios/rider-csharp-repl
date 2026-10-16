package com.wollumestudios.csharprepl.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.messages.Topic

enum class VariablesPosition(val label: String) { RIGHT("Right of the history"), BELOW("Below the input") }

fun interface ReplSettingsListener {
    fun changed()
}

/** Application-level settings (Settings › Tools › C# REPL). Call [changed] after modifying [state] so open tool windows follow. */
@Service(Service.Level.APP)
@State(name = "CSharpReplSettings", storages = [Storage("csharprepl.xml")])
class ReplSettings : PersistentStateComponent<ReplSettings.Options> {
    class Options {
        var autoRun = true
        var variablesPosition = VariablesPosition.RIGHT
        var ignoreChangesForever = false
    }

    private var options = Options()

    override fun getState() = options

    override fun loadState(loaded: Options) {
        options = loaded
    }

    companion object {
        val TOPIC = Topic(ReplSettingsListener::class.java)

        val state: Options get() = service<ReplSettings>().getState()

        fun changed() = ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).changed()
    }
}
