# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# ── Gson / TypeToken ──────────────────────────────────────────────────────────
# Prevent stripping of generic type information needed by TypeToken reflection
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
# Keep generic signatures on Gson TypeToken subclasses (F-125 — R8 was stripping these).
-keep class * extends com.google.gson.reflect.TypeToken { *; }
-keepattributes Signature
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep data classes serialized with Gson (ScheduleRule, Achievement, etc.)
-keep class com.grayzone.app.data.ScheduleRule { *; }
-keep class com.grayzone.app.data.Achievement  { *; }

# ── Room ──────────────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *
-keepclassmembers class * extends androidx.room.RoomDatabase {
    abstract ** *Dao();
}

# ── Accessibility Service ─────────────────────────────────────────────────────
-keep class com.grayzone.app.service.GrayzoneAccessibilityService { *; }
-keep class com.grayzone.app.service.OverlayService          { *; }

# ── MainActivity (used in PendingIntent) ──────────────────────────────────────
-keep class com.grayzone.app.MainActivity { *; }
