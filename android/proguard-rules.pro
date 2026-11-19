# Keep JNI entry points and their classes at their published names. The native
# libraries resolve these with static Java_... symbols, so renaming them would
# make every native method fail to link.
-keepclasseswithmembers class * {
    native <methods>;
}
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
-keep class org.mozilla.javascript.** { *; }
-dontwarn androidx.window.extensions.**
-dontwarn androidx.window.sidecar.**
-dontwarn java.beans.**
-dontwarn java.awt.**
-dontwarn javax.swing.**
-dontwarn org.mozilla.javascript.tools.**
