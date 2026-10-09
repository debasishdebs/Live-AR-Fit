# ---- kotlinx.serialization: wire and settings types in :core:model and :services:* (spec §3) ----
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses,Signature,EnclosingMethod
-keep,includedescriptorclasses class com.debasish.livefit.**$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class com.debasish.livefit.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class com.debasish.livefit.**$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontnote kotlinx.serialization.**

# ---- Rokid CXR-S bridge (JNI) ----
-keep class com.rokid.cxr.** { *; }
-keep class com.rokid.cxrservice.** { *; }
-dontwarn com.rokid.**
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

# ---- Release logging: strip v/d/i, keep w/e (spec §3) ----
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
