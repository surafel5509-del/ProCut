package com.procutstudio.editor.ui.navigation

/** All navigation destinations. Routes with arguments expose typed builders. */
object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val NEW_PROJECT = "new_project"

    const val EDITOR = "editor/{projectId}"
    fun editor(projectId: String) = "editor/$projectId"

    const val TEXT_EDITOR = "text_editor/{projectId}?textId={textId}"
    fun textEditor(projectId: String, textId: String? = null) =
        if (textId == null) "text_editor/$projectId" else "text_editor/$projectId?textId=$textId"

    const val AUDIO_MIXER = "audio/{projectId}"
    fun audioMixer(projectId: String) = "audio/$projectId"

    /**
     * The export flow is a single navigation destination hosting three screens (settings →
     * progress → success). Keeping it as one destination means the export ViewModel — and the
     * running render — survive every step without nested-graph scoping tricks.
     */
    const val EXPORT = "export/{projectId}"
    fun export(projectId: String) = "export/$projectId"
}
