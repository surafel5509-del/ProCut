# Keep kotlinx.serialization generated serializers for the persisted project document.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class com.procutstudio.editor.domain.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.procutstudio.editor.domain.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.procutstudio.editor.**$$serializer { *; }

# Room
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# Media3 Transformer / effect classes are accessed reflectively by MediaCodec glue.
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
