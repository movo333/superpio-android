package com.novaplay.superpio;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.webkit.WebChromeClient;
import android.view.KeyEvent;
import android.app.AlertDialog;
import android.content.DialogInterface;

public class MainActivity extends Activity {

    private WebView webView;
    private static final String GAME_URL = "https://superpio-inc.netlify.app";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setAllowFileAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());

        // جسر Android للتحكم بالخروج
        webView.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void exitApp() {
                finish();
            }
        }, "AndroidBridge");

        webView.loadUrl(GAME_URL);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // زر الرجوع — يظهر dialog الخروج
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            webView.evaluateJavascript("showExitDialog()", null);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
    }
}
