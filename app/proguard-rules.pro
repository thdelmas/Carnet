# Keep CameraX classes (reflection-heavy in some configurations).
-keep class androidx.camera.** { *; }

# Keep ViewBinding classes.
-keep class com.carnet.app.databinding.** { *; }

# Keep crash diagnostics readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
