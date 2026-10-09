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

# ---- Room: database, entities and DAOs (Room's consumer rules cover the generated code) ----
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class com.debasish.livefit.history.HistoryDatabase_Impl { *; }
-keep @androidx.room.Entity class com.debasish.livefit.history.** { *; }
-keep @androidx.room.Dao interface com.debasish.livefit.history.** { *; }

# ---- Rokid CXR-L / CXR bridge (JNI + AIDL + Gson models) ----
-keep class com.rokid.cxr.** { *; }
-keep class com.rokid.cxrservice.** { *; }
-dontwarn com.rokid.**
# CxrGlassesLink.preferGlobalHiRokid() writes the private field "a" by reflection: name and field must survive.
-keep class com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
-keep class * extends com.google.gson.reflect.TypeToken

# ---- Release logging: strip v/d/i, keep w/e (spec §3) ----
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
