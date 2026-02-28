# Super Pio ProGuard Rules

# حفظ MainActivity
-keep class com.novaplay.superpio.MainActivity { *; }

# حفظ JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# WebView
-keepclassmembers class * extends android.webkit.WebViewClient {
    public void *(android.webkit.WebView, java.lang.String, android.graphics.Bitmap);
    public boolean *(android.webkit.WebView, java.lang.String);
}

# AndroidX
-keep class androidx.** { *; }
-dontwarn androidx.**
