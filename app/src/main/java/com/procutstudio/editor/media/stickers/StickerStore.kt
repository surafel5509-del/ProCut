package com.procutstudio.editor.media.stickers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import com.procutstudio.editor.core.di.IoDispatcher
import com.procutstudio.editor.domain.model.MediaAsset
import com.procutstudio.editor.domain.model.MediaType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns catalogue stickers into project assets (high-resolution PNGs in app storage) and serves
 * small cached bitmaps for the picker grid.
 */
@Singleton
class StickerStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    private val tileCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Bitmap for a picker tile; rendered once, then served from memory. */
    suspend fun tile(def: StickerDef, widthPx: Int = TILE_PX): Bitmap = withContext(ioDispatcher) {
        val key = "${def.id}@$widthPx"
        tileCache.get(key) ?: StickerRenderer.render(def, widthPx).also { tileCache.put(key, it) }
    }

    /**
     * Writes the full-size PNG for [def] (once) and returns the asset describing it. The asset id
     * is derived from the sticker id so placing the same sticker twice reuses one file.
     */
    suspend fun materialize(def: StickerDef): MediaAsset = withContext(ioDispatcher) {
        val dir = File(context.filesDir, "stickers").apply { mkdirs() }
        val file = File(dir, "${def.id}.png")
        if (!file.exists() || file.length() == 0L) {
            val bitmap = StickerRenderer.render(def, EXPORT_PX)
            val temp = File(dir, "${def.id}.png.part")
            temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
            bitmap.recycle()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        MediaAsset(
            id = "stk-${def.id}",
            uri = Uri.fromFile(file).toString(),
            displayName = def.label,
            mediaType = MediaType.IMAGE,
            durationUs = 0L,
            width = bounds.outWidth,
            height = bounds.outHeight,
            sizeBytes = file.length(),
            importedAtMs = System.currentTimeMillis(),
        )
    }

    private companion object {
        const val TILE_PX = 256
        const val EXPORT_PX = 768
    }
}
