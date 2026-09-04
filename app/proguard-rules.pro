# sidetrack ProGuard rules

# Keep JNI bridge methods (called from native code)
-keep class com.sidetrack.bridge.NativeBridge { *; }

# Keep audio callback (called from native code via JNI)
-keep class com.sidetrack.audio.AudioCallback { *; }

# Keep kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.sidetrack.bridge.**$$serializer { *; }
-keepclassmembers class com.sidetrack.bridge.** {
    *** Companion;
}
-keepclasseswithmembers class com.sidetrack.bridge.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Strip verbose/debug logs from release builds (SP-01 ROM filters Log.d
# at system level, so we use Log.i for app logs — kept in release for
# future log-capture support)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
