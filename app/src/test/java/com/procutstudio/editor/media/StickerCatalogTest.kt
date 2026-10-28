package com.procutstudio.editor.media

import com.procutstudio.editor.media.stickers.StickerCatalog
import com.procutstudio.editor.media.stickers.StickerCategory
import com.procutstudio.editor.media.stickers.StickerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerCatalogTest {

    @Test
    fun `sticker ids are unique and every category is populated`() {
        val ids = StickerCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        StickerCategory.entries.forEach { category ->
            assertTrue("${category.label} is empty", StickerCatalog.inCategory(category).isNotEmpty())
        }
    }

    @Test
    fun `the library has the text-art stickers from the design`() {
        listOf("Catch the moment", "Never give up", "Good night", "OMG!", "So fun").forEach { label ->
            val sticker = StickerCatalog.all.firstOrNull { it.label == label }
            assertNotNull("missing $label", sticker)
            assertTrue(sticker!!.kind is StickerKind.TextArt)
        }
    }

    @Test
    fun `search matches labels case-insensitively`() {
        assertTrue(StickerCatalog.search("GOOD").any { it.label == "Good night" })
        assertTrue(StickerCatalog.search("zzzz-no-match").isEmpty())
        assertEquals(StickerCatalog.all.size, StickerCatalog.search("  ").size)
    }
}
