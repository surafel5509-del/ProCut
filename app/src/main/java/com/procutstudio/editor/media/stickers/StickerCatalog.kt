package com.procutstudio.editor.media.stickers

/** Look of a text-art sticker. Each style is a different real Canvas rendering recipe. */
enum class TextArtStyle { OUTLINE, NEON, BOXED, GRADIENT, SHADOW3D, BUBBLE, SCRIPT }

enum class ShapeKind { STAR, HEART, ARROW, SPEECH, CHECK, SPARKLE, BURST, CROWN }

enum class StickerCategory(val label: String, val icon: String) {
    TRENDING("Trending", "🔥"),
    TEXT("Text", "💬"),
    FACES("Emoji", "😀"),
    LOVE("Love", "💖"),
    ANIMALS("Animals", "🐶"),
    FOOD("Food", "🍕"),
    PARTY("Party", "🎉"),
    NATURE("Nature", "🌴"),
    SHAPES("Shapes", "⭐"),
    LABELS("Labels", "🏷"),
}

/** What a sticker is made of. All kinds are rasterised on the device by [StickerRenderer]. */
sealed interface StickerKind {
    data class TextArt(
        val text: String,
        val style: TextArtStyle,
        val primary: Long,
        val secondary: Long,
    ) : StickerKind

    data class Emoji(val glyph: String) : StickerKind

    data class Shape(val shape: ShapeKind, val primary: Long, val secondary: Long) : StickerKind

    data class Badge(val text: String, val background: Long, val foreground: Long) : StickerKind
}

data class StickerDef(
    val id: String,
    val category: StickerCategory,
    val label: String,
    val kind: StickerKind,
    val trending: Boolean = false,
    /** Suggested width of the placed sticker as a fraction of the frame width. */
    val defaultScale: Float = 0.3f,
    val isPro: Boolean = false,
)

/** The built-in sticker library (text art, emoji packs, vector shapes and badges). */
object StickerCatalog {

    private fun text(
        id: String, label: String, style: TextArtStyle, primary: Long, secondary: Long,
        trending: Boolean = false, pro: Boolean = false,
    ) = StickerDef(
        id = "t_$id", category = StickerCategory.TEXT, label = label,
        kind = StickerKind.TextArt(label, style, primary, secondary),
        trending = trending, defaultScale = 0.62f, isPro = pro,
    )

    private fun emoji(prefix: String, category: StickerCategory, glyphs: List<String>, trendingFirst: Int = 0) =
        glyphs.mapIndexed { index, glyph ->
            StickerDef(
                id = "e_${prefix}_$index", category = category, label = glyph,
                kind = StickerKind.Emoji(glyph), trending = index < trendingFirst, defaultScale = 0.24f,
            )
        }

    private fun shape(id: String, label: String, shape: ShapeKind, primary: Long, secondary: Long, trending: Boolean = false, pro: Boolean = false) =
        StickerDef(
            id = "s_$id", category = StickerCategory.SHAPES, label = label,
            kind = StickerKind.Shape(shape, primary, secondary), trending = trending, defaultScale = 0.28f, isPro = pro,
        )

    private fun badge(id: String, label: String, background: Long, foreground: Long, trending: Boolean = false, pro: Boolean = false) =
        StickerDef(
            id = "b_$id", category = StickerCategory.LABELS, label = label,
            kind = StickerKind.Badge(label, background, foreground), trending = trending, defaultScale = 0.4f, isPro = pro,
        )

    private const val WHITE = 0xFFFFFFFFL
    private const val INK = 0xFF16161DL

    val all: List<StickerDef> = buildList {
        // ---- Text art -------------------------------------------------------------------
        add(text("catch", "Catch the moment", TextArtStyle.SCRIPT, WHITE, 0xFFFF5C8AL, trending = true))
        add(text("never", "Never give up", TextArtStyle.OUTLINE, WHITE, 0xFF22C55EL, trending = true))
        add(text("night", "Good night", TextArtStyle.NEON, 0xFFB794F6L, 0xFF7C3AEDL, trending = true))
        add(text("omg", "OMG!", TextArtStyle.SHADOW3D, 0xFFFFE14DL, 0xFFE11D48L, trending = true))
        add(text("fun", "So fun", TextArtStyle.BOXED, 0xFFFF7A1AL, WHITE, trending = true))
        add(text("vibes", "Good vibes", TextArtStyle.GRADIENT, 0xFFFF5E62L, 0xFFFFC371L, trending = true))
        add(text("hello", "Hello!", TextArtStyle.BUBBLE, 0xFF2563EBL, WHITE))
        add(text("wow", "WOW", TextArtStyle.SHADOW3D, 0xFF4ADE80L, 0xFF14532DL))
        add(text("love", "Love it", TextArtStyle.SCRIPT, 0xFFFF8FB1L, 0xFF9D174DL))
        add(text("weekend", "Weekend mode", TextArtStyle.BOXED, 0xFF111827L, 0xFFFDE047L))
        add(text("squad", "Squad goals", TextArtStyle.OUTLINE, 0xFFFDE047L, 0xFF1E1B4BL))
        add(text("happy", "Happy day", TextArtStyle.GRADIENT, 0xFF38BDF8L, 0xFF6366F1L))
        add(text("newday", "New day", TextArtStyle.NEON, 0xFF5EEAD4L, 0xFF0D9488L, pro = true))
        add(text("kind", "Be kind", TextArtStyle.SCRIPT, WHITE, 0xFF0EA5E9L))
        add(text("dream", "Dream big", TextArtStyle.GRADIENT, 0xFFF472B6L, 0xFF8B5CF6L, pro = true))
        add(text("best", "Best day ever", TextArtStyle.BUBBLE, 0xFFEA580CL, 0xFFFFF7EDL))
        add(text("lol", "LOL", TextArtStyle.SHADOW3D, 0xFFFFFFFFL, 0xFF0F766EL))
        add(text("cool", "Too cool", TextArtStyle.NEON, 0xFF67E8F9L, 0xFF0891B2L))
        add(text("summer", "Summer time", TextArtStyle.GRADIENT, 0xFFFACC15L, 0xFFF97316L))
        add(text("chill", "Just chill", TextArtStyle.OUTLINE, 0xFFA7F3D0L, 0xFF064E3BL))
        add(text("mood", "Mood", TextArtStyle.BOXED, 0xFFE11D48L, WHITE))
        add(text("fresh", "Fresh start", TextArtStyle.SCRIPT, 0xFFBEF264L, 0xFF3F6212L))
        add(text("slay", "Slay", TextArtStyle.NEON, 0xFFF0ABFCL, 0xFFC026D3L, pro = true))
        add(text("sunday", "Sunday funday", TextArtStyle.BUBBLE, 0xFF7C3AEDL, 0xFFF5F3FFL))

        // ---- Emoji packs ----------------------------------------------------------------
        addAll(emoji("face", StickerCategory.FACES, listOf("😀", "😂", "🤣", "😍", "😎", "🥹", "😭", "😡", "🤯", "🥳", "😴", "🤔", "🙄", "😱", "🤩", "😇", "🥶", "🥵", "🤗", "😏", "🫠", "🤭", "😬", "🙃"), trendingFirst = 3))
        addAll(emoji("love", StickerCategory.LOVE, listOf("❤️", "💖", "💕", "😘", "🥰", "💘", "💝", "💗", "💓", "💞", "💌", "🌹", "💍", "😻", "🫶", "💑"), trendingFirst = 2))
        addAll(emoji("animal", StickerCategory.ANIMALS, listOf("🐶", "🐱", "🦊", "🐼", "🦁", "🐯", "🐸", "🐵", "🐧", "🦄", "🐰", "🐨", "🐢", "🦋", "🐝", "🐙"), trendingFirst = 1))
        addAll(emoji("food", StickerCategory.FOOD, listOf("🍕", "🍔", "🍟", "🌮", "🍣", "🍩", "🍦", "🍓", "🍉", "☕", "🧋", "🍿", "🥑", "🍫", "🍪", "🍷"), trendingFirst = 1))
        addAll(emoji("party", StickerCategory.PARTY, listOf("🎉", "🎊", "🎈", "🎁", "🥂", "🍾", "🎂", "🪩", "🎤", "🎧", "🎸", "🏆", "🔥", "💯", "✨", "🎆"), trendingFirst = 3))
        addAll(emoji("nature", StickerCategory.NATURE, listOf("🌴", "🌸", "🌻", "🌈", "☀️", "🌙", "⭐", "⚡", "❄️", "🌊", "🍀", "🌵", "🍂", "🌋", "☁️", "🌍"), trendingFirst = 2))

        // ---- Vector shapes --------------------------------------------------------------
        add(shape("star", "Star", ShapeKind.STAR, 0xFFFFD43BL, 0xFFF59F00L, trending = true))
        add(shape("heart", "Heart", ShapeKind.HEART, 0xFFFF4D6DL, 0xFFC9184AL, trending = true))
        add(shape("sparkle", "Sparkle", ShapeKind.SPARKLE, 0xFFFFFFFFL, 0xFFFFE066L, trending = true))
        add(shape("arrow", "Arrow", ShapeKind.ARROW, 0xFFFF6B6BL, 0xFFC92A2AL))
        add(shape("speech", "Speech", ShapeKind.SPEECH, WHITE, 0xFF495057L))
        add(shape("check", "Check", ShapeKind.CHECK, 0xFF40C057L, 0xFF2B8A3EL))
        add(shape("burst", "Burst", ShapeKind.BURST, 0xFFFFA94DL, 0xFFE8590CL))
        add(shape("crown", "Crown", ShapeKind.CROWN, 0xFFFFD43BL, 0xFFE67700L, pro = true))
        add(shape("star2", "Pink star", ShapeKind.STAR, 0xFFF783ACL, 0xFFD6336CL))
        add(shape("heart2", "Blue heart", ShapeKind.HEART, 0xFF4DABF7L, 0xFF1971C2L))
        add(shape("sparkle2", "Cyan spark", ShapeKind.SPARKLE, 0xFF99E9F2L, 0xFF15AABFL))
        add(shape("arrow2", "Green arrow", ShapeKind.ARROW, 0xFF69DB7CL, 0xFF2B8A3EL))

        // ---- Badges ---------------------------------------------------------------------
        add(badge("new", "NEW", 0xFFE11D48L, WHITE, trending = true))
        add(badge("sale", "SALE", 0xFFFFD43BL, INK, trending = true))
        add(badge("hot", "HOT", 0xFFFF6B00L, WHITE))
        add(badge("live", "LIVE", 0xFFDC2626L, WHITE, trending = true))
        add(badge("vip", "VIP", INK, 0xFFFFD43BL, pro = true))
        add(badge("best", "BEST", 0xFF7C3AEDL, WHITE))
        add(badge("top", "TOP 1", 0xFF2563EBL, WHITE))
        add(badge("free", "FREE", 0xFF16A34AL, WHITE))
        add(badge("like", "LIKE", 0xFF1D9BF0L, WHITE))
        add(badge("sub", "SUBSCRIBE", 0xFFFF0000L, WHITE))
        add(badge("follow", "FOLLOW", 0xFFEC4899L, WHITE))
        add(badge("part2", "PART 2", INK, WHITE, pro = true))
    }

    fun inCategory(category: StickerCategory): List<StickerDef> =
        if (category == StickerCategory.TRENDING) all.filter { it.trending } else all.filter { it.category == category }

    fun search(query: String): List<StickerDef> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return all
        return all.filter { it.label.lowercase().contains(q) || it.category.label.lowercase().contains(q) }
    }

    fun byId(id: String): StickerDef? = all.firstOrNull { it.id == id }
}
