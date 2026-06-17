# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# -----------------------------
# Drivehub Kamera – obfuscation hardening
# -----------------------------

# JNI: CameraProbe native methods must be kept because JNI uses name-mangling;
# the class and its native method names need to survive R8 shrinking.
-keep class com.drivehub.kamera.CameraProbe { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# AndroidX core is sometimes loaded via reflection – prevent R8 from stripping it.
-keep class androidx.core.app.** { *; }
-dontwarn androidx.core.app.**

# Keep members annotated with @Keep.
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# Obfuscate source file names in stack traces.
-renamesourcefileattribute SourceFile

# Flatten the package structure to make the bytecode less readable.
-repackageclasses ''