# ProGuard / R8 Rules for weight-sync

# Preserve stack traces and line numbers
-keepattributes SourceFile,LineNumberTable,EnclosingMethod,InnerClasses,Signature,*Annotation*

# Keep all application models, DAOs, services, and repositories
-keep class com.example.dianzicheng.domain.** { *; }
-keep class com.example.dianzicheng.data.local.** { *; }
-keep class com.example.dianzicheng.data.garmin.** { *; }
-keep class com.example.dianzicheng.data.health.** { *; }
-keep class com.example.dianzicheng.data.repository.** { *; }
-keep class com.example.dianzicheng.service.** { *; }
-keep class com.example.dianzicheng.ui.** { *; }

# Keep Room database and entities
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }

# Keep Health Connect
-keep class androidx.health.connect.** { *; }

# Keep OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Tree-shaking without renaming/obfuscation
# Strips ~35MB of unused vector icons from material-icons-extended and dead library classes,
# while guaranteeing 100% runtime reflection and model safety.
-dontobfuscate
-dontoptimize
