# WebRTC
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
-keep class livekit.org.webrtc.** { *; }
-dontwarn livekit.org.webrtc.**

# LiveKit
-keep class io.livekit.** { *; }
-dontwarn io.livekit.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class ai.decart.sdk.**$$serializer { *; }
-keepclassmembers class ai.decart.sdk.** {
    *** Companion;
}
-keepclasseswithmembers class ai.decart.sdk.** {
    kotlinx.serialization.KSerializer serializer(...);
}
