-dontobfuscate
-dontoptimize
-keepclasseswithmembers class * {
    native <methods>;
}
-dontwarn androidx.window.extensions.**
-dontwarn androidx.window.sidecar.**
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
-keepclassmembers class * { public *; }
