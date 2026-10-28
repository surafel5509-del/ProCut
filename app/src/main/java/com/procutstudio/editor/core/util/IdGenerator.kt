package com.procutstudio.editor.core.util

import java.util.UUID

/**
 * Stable identifiers. Every persisted entity gets an id the first time it is created and keeps it
 * for the lifetime of the document, which lets undo/redo, selection and async work refer to items
 * without ambiguity.
 */
object IdGenerator {
    fun newId(prefix: String = ""): String {
        val raw = UUID.randomUUID().toString().replace("-", "").substring(0, 20)
        return if (prefix.isEmpty()) raw else "$prefix-$raw"
    }

    fun projectId(): String = newId("prj")
    fun trackId(): String = newId("trk")
    fun clipId(): String = newId("clp")
    fun textId(): String = newId("txt")
    fun audioId(): String = newId("aud")
    fun overlayId(): String = newId("ovl")
    fun assetId(): String = newId("ast")
    fun effectId(): String = newId("fxi")
}
