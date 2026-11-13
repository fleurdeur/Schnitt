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
-dontwarn java.beans.**
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.javascript.optimizer.** { *; }
-dontwarn java.awt.**
-dontwarn javax.swing.**
-dontwarn org.mozilla.javascript.tools.**
