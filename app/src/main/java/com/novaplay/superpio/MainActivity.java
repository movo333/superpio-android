package com.novaplay.superpio;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.JavascriptInterface;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;
import android.view.View;
import android.os.Build;
import android.util.Log;
import androidx.webkit.WebViewAssetLoader;
import androidx.annotation.NonNull;

import com.android.billingclient.api.*;
import com.google.android.gms.ads.*;
import com.google.android.gms.ads.rewarded.*;
import com.google.android.gms.ads.interstitial.*;

import java.util.Arrays;
import java.util.List;

public class MainActivity extends Activity implements PurchasesUpdatedListener {

    private static final String TAG = "SuperPio";
    private static final String ADMOB_REWARD_ID  = "ca-app-pub-1152901043073265/9821943568";
    private static final String ADMOB_SPLASH_ID  = "ca-app-pub-1152901043073265/3720258008";

    private WebView webView;
    private WebViewAssetLoader assetLoader;
    private RewardedAd rewardedAd;
    private InterstitialAd splashAd;
    private boolean adLoading = false;
    private BillingClient billingClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ── شاشة كاملة ──
        try {
            requestWindowFeature(Window.FEATURE_NO_TITLE);
            getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            );
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } catch (Exception e) {
            Log.e(TAG, "Window setup error: " + e.getMessage());
        }

        // ── WebView أولاً قبل أي شيء آخر ──
        try {
            assetLoader = new WebViewAssetLoader.Builder()
                .setDomain("appassets.androidplatform.net")
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

            webView = new WebView(this);
            setContentView(webView);

            WebSettings s = webView.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setDatabaseEnabled(true);
            s.setMediaPlaybackRequiresUserGesture(false);
            s.setCacheMode(WebSettings.LOAD_DEFAULT);
            s.setAllowFileAccess(true);
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

            webView.setWebViewClient(new WebViewClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                    try {
                        WebResourceResponse r = assetLoader.shouldInterceptRequest(req.getUrl());
                        return r != null ? r : super.shouldInterceptRequest(v, req);
                    } catch (Exception e) {
                        return super.shouldInterceptRequest(v, req);
                    }
                }
                @Override
                public void onPageFinished(WebView v, String url) {
                    super.onPageFinished(v, url);
                    hideSystemUI();
                }
            });

            webView.setWebChromeClient(new WebChromeClient());
            webView.addJavascriptInterface(new GameBridge(), "AndroidBridge");
            webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");

        } catch (Exception e) {
            Log.e(TAG, "WebView setup error: " + e.getMessage());
        }

        hideSystemUI();

        // ── AdMob في الخلفية ──
        try {
            MobileAds.initialize(this, initStatus -> {
                try { loadRewardedAd(); } catch (Exception e) { Log.e(TAG, "loadRewardedAd: " + e.getMessage()); }
                try { loadSplashAd();  } catch (Exception e) { Log.e(TAG, "loadSplashAd: "  + e.getMessage()); }
            });
        } catch (Exception e) {
            Log.e(TAG, "AdMob init error: " + e.getMessage());
        }

        // ── Billing في الخلفية ──
        try {
            billingClient = BillingClient.newBuilder(this)
                .setListener(this)
                .enablePendingPurchases()
                .build();
            billingClient.startConnection(new BillingClientStateListener() {
                @Override
                public void onBillingSetupFinished(BillingResult r) {
                    try {
                        if (r.getResponseCode() == BillingClient.BillingResponseCode.OK)
                            checkPendingPurchases();
                    } catch (Exception e) { Log.e(TAG, "Billing setup: " + e.getMessage()); }
                }
                @Override
                public void onBillingServiceDisconnected() {}
            });
        } catch (Exception e) {
            Log.e(TAG, "Billing init error: " + e.getMessage());
        }
    }

    // ══════════════════════════════════════════
    // JavaScript Bridge
    // ══════════════════════════════════════════
    private class GameBridge {

        @JavascriptInterface
        public void showRewardedAd(String rewardType) {
            try {
                runOnUiThread(() -> {
                    try {
                        if (rewardedAd != null) {
                            rewardedAd.show(MainActivity.this, item -> {
                                notifyJS("onAdRewarded", rewardType);
                                try { loadRewardedAd(); } catch (Exception e) {}
                            });
                        } else {
                            // لا إعلان جاهز - أعط المكافأة مباشرة
                            notifyJS("onAdRewarded", rewardType);
                            try { loadRewardedAd(); } catch (Exception e) {}
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "showRewardedAd: " + e.getMessage());
                        notifyJS("onAdRewarded", rewardType);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "showRewardedAd outer: " + e.getMessage());
            }
        }

        @JavascriptInterface
        public void showSplashAd() {
            try {
                runOnUiThread(() -> {
                    try {
                        if (splashAd != null) {
                            splashAd.show(MainActivity.this);
                            splashAd = null;
                            loadSplashAd();
                        }
                    } catch (Exception e) { Log.e(TAG, "showSplashAd: " + e.getMessage()); }
                });
            } catch (Exception e) {}
        }

        @JavascriptInterface
        public boolean isAdReady() {
            return rewardedAd != null;
        }

        @JavascriptInterface
        public void purchase(String productId) {
            try {
                if (billingClient == null || !billingClient.isReady()) {
                    notifyJS("onPurchaseResult", "error|" + productId);
                    return;
                }
                QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                    .setProductList(Arrays.asList(
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(productId)
                            .setProductType(BillingClient.ProductType.INAPP)
                            .build()
                    )).build();

                billingClient.queryProductDetailsAsync(params, (result, list) -> {
                    try {
                        if (result.getResponseCode() != BillingClient.BillingResponseCode.OK || list.isEmpty()) {
                            notifyJS("onPurchaseResult", "error|" + productId);
                            return;
                        }
                        BillingFlowParams flow = BillingFlowParams.newBuilder()
                            .setProductDetailsParamsList(Arrays.asList(
                                BillingFlowParams.ProductDetailsParams.newBuilder()
                                    .setProductDetails(list.get(0))
                                    .build()
                            )).build();
                        runOnUiThread(() -> {
                            try { billingClient.launchBillingFlow(MainActivity.this, flow); }
                            catch (Exception e) { Log.e(TAG, "launchBilling: " + e.getMessage()); }
                        });
                    } catch (Exception e) { Log.e(TAG, "queryProduct: " + e.getMessage()); }
                });
            } catch (Exception e) {
                Log.e(TAG, "purchase: " + e.getMessage());
                notifyJS("onPurchaseResult", "error|" + productId);
            }
        }

        @JavascriptInterface
        public void exitApp() {
            try { finish(); } catch (Exception e) {}
        }
    }

    // ══════════════════════════════════════════
    // تحميل الإعلانات
    // ══════════════════════════════════════════
    private void loadRewardedAd() {
        if (adLoading) return;
        adLoading = true;
        try {
            RewardedAd.load(this, ADMOB_REWARD_ID, new AdRequest.Builder().build(),
                new RewardedAdLoadCallback() {
                    @Override
                    public void onAdLoaded(@NonNull RewardedAd ad) {
                        rewardedAd = ad;
                        adLoading = false;
                        rewardedAd.setFullScreenContentCallback(new FullScreenContentCallback() {
                            @Override public void onAdDismissedFullScreenContent() {
                                rewardedAd = null;
                                try { loadRewardedAd(); } catch (Exception e) {}
                            }
                            @Override public void onAdFailedToShowFullScreenContent(@NonNull AdError e) {
                                rewardedAd = null;
                                try { loadRewardedAd(); } catch (Exception ex) {}
                            }
                        });
                    }
                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError e) {
                        rewardedAd = null;
                        adLoading = false;
                    }
                });
        } catch (Exception e) {
            adLoading = false;
            Log.e(TAG, "loadRewardedAd: " + e.getMessage());
        }
    }

    private void loadSplashAd() {
        try {
            InterstitialAd.load(this, ADMOB_SPLASH_ID, new AdRequest.Builder().build(),
                new InterstitialAdLoadCallback() {
                    @Override public void onAdLoaded(@NonNull InterstitialAd ad) { splashAd = ad; }
                    @Override public void onAdFailedToLoad(@NonNull LoadAdError e) { splashAd = null; }
                });
        } catch (Exception e) {
            Log.e(TAG, "loadSplashAd: " + e.getMessage());
        }
    }

    // ══════════════════════════════════════════
    // Billing
    // ══════════════════════════════════════════
    @Override
    public void onPurchasesUpdated(BillingResult result, List<Purchase> purchases) {
        try {
            if (result.getResponseCode() == BillingClient.BillingResponseCode.OK && purchases != null)
                for (Purchase p : purchases) handlePurchase(p);
        } catch (Exception e) { Log.e(TAG, "onPurchasesUpdated: " + e.getMessage()); }
    }

    private void handlePurchase(Purchase purchase) {
        try {
            if (purchase.getPurchaseState() != Purchase.PurchaseState.PURCHASED) return;
            String productId = purchase.getProducts().get(0);
            ConsumeParams cp = ConsumeParams.newBuilder()
                .setPurchaseToken(purchase.getPurchaseToken()).build();
            billingClient.consumeAsync(cp, (r, token) -> {
                if (r.getResponseCode() == BillingClient.BillingResponseCode.OK)
                    notifyJS("onPurchaseResult", "success|" + productId);
            });
        } catch (Exception e) { Log.e(TAG, "handlePurchase: " + e.getMessage()); }
    }

    private void checkPendingPurchases() {
        try {
            billingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder()
                    .setProductType(BillingClient.ProductType.INAPP).build(),
                (r, list) -> { try { for (Purchase p : list) handlePurchase(p); } catch (Exception e) {} }
            );
        } catch (Exception e) { Log.e(TAG, "checkPending: " + e.getMessage()); }
    }

    // ══════════════════════════════════════════
    // Utils
    // ══════════════════════════════════════════
    private void notifyJS(final String fn, final String data) {
        try {
            runOnUiThread(() -> {
                try {
                    if (webView != null)
                        webView.evaluateJavascript(
                            "if(typeof " + fn + "==='function')" + fn + "('" + data + "');", null);
                } catch (Exception e) {}
            });
        } catch (Exception e) {}
    }

    private void hideSystemUI() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && webView != null) {
                webView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                );
            }
        } catch (Exception e) {}
    }

    @Override public void onWindowFocusChanged(boolean h) {
        super.onWindowFocusChanged(h);
        if (h) hideSystemUI();
    }

    @Override public boolean onKeyDown(int k, KeyEvent e) {
        if (k == KeyEvent.KEYCODE_BACK) {
            try {
                if (webView != null)
                    webView.evaluateJavascript(
                        "if(typeof showExitDialog==='function')showExitDialog();", null);
            } catch (Exception ex) {}
            return true;
        }
        return super.onKeyDown(k, e);
    }

    @Override protected void onResume() {
        super.onResume();
        try { if (webView != null) webView.onResume(); } catch (Exception e) {}
        hideSystemUI();
    }

    @Override protected void onPause() {
        super.onPause();
        try { if (webView != null) webView.onPause(); } catch (Exception e) {}
    }

    @Override protected void onDestroy() {
        try { if (billingClient != null) billingClient.endConnection(); } catch (Exception e) {}
        try { if (webView != null) { webView.destroy(); webView = null; } } catch (Exception e) {}
        super.onDestroy();
    }
    }
            
