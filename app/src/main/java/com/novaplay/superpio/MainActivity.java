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

// Google Play Billing
import com.android.billingclient.api.*;

// AdMob
import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;

import java.util.Arrays;
import java.util.List;

public class MainActivity extends Activity implements PurchasesUpdatedListener {

    private static final String TAG = "SuperPio";

    // ── AdMob IDs ──
    private static final String ADMOB_APP_ID    = "ca-app-pub-1152901043073265~9762922173";
    private static final String ADMOB_REWARD_ID = "ca-app-pub-1152901043073265/9821943568";
    private static final String ADMOB_SPLASH_ID = "ca-app-pub-1152901043073265/3720258008";

    private WebView webView;
    private WebViewAssetLoader assetLoader;

    // AdMob
    private RewardedAd rewardedAd;
    private InterstitialAd splashAd;
    private boolean adLoading = false;
    private String pendingRewardType = null;

    // Billing
    private BillingClient billingClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUI();

        // ── تهيئة AdMob ──
        MobileAds.initialize(this, initStatus -> {
            Log.d(TAG, "AdMob initialized");
            loadRewardedAd();
            loadSplashAd();
        });

        // ── تهيئة Billing ──
        billingClient = BillingClient.newBuilder(this)
            .setListener(this)
            .enablePendingPurchases()
            .build();
        billingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(BillingResult r) {
                if (r.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    checkPendingPurchases();
                }
            }
            @Override
            public void onBillingServiceDisconnected() {}
        });

        // ── WebView ──
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
                WebResourceResponse r = assetLoader.shouldInterceptRequest(req.getUrl());
                return r != null ? r : super.shouldInterceptRequest(v, req);
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
    }

    // ══════════════════════════════════════════
    // الجسر بين JavaScript والـ Native
    // ══════════════════════════════════════════
    private class GameBridge {

        // ── إعلان المكافأة ──
        @JavascriptInterface
        public void showRewardedAd(String rewardType) {
            pendingRewardType = rewardType;
            runOnUiThread(() -> {
                if (rewardedAd != null) {
                    rewardedAd.show(MainActivity.this, rewardItem -> {
                        // المستخدم شاهد الإعلان كاملاً
                        Log.d(TAG, "Reward earned: " + rewardType);
                        notifyJS("onAdRewarded", rewardType);
                        loadRewardedAd(); // تحميل إعلان جديد
                    });
                } else {
                    // لا يوجد إعلان جاهز - أعطِ المكافأة مباشرة
                    Log.d(TAG, "No ad ready, giving reward directly");
                    notifyJS("onAdRewarded", rewardType);
                    loadRewardedAd();
                }
            });
        }

        // ── إعلان Splash ──
        @JavascriptInterface
        public void showSplashAd() {
            runOnUiThread(() -> {
                if (splashAd != null) {
                    splashAd.show(MainActivity.this);
                    splashAd = null;
                    loadSplashAd();
                }
            });
        }

        // ── هل الإعلان جاهز ──
        @JavascriptInterface
        public boolean isAdReady() {
            return rewardedAd != null;
        }

        // ── شراء IAP ──
        @JavascriptInterface
        public void purchase(String productId) {
            if (!billingClient.isReady()) {
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
                runOnUiThread(() -> billingClient.launchBillingFlow(MainActivity.this, flow));
            });
        }

        // ── إغلاق التطبيق ──
        @JavascriptInterface
        public void exitApp() { finish(); }
    }

    // ══════════════════════════════════════════
    // تحميل الإعلانات
    // ══════════════════════════════════════════
    private void loadRewardedAd() {
        if (adLoading) return;
        adLoading = true;
        AdRequest req = new AdRequest.Builder().build();
        RewardedAd.load(this, ADMOB_REWARD_ID, req, new RewardedAdLoadCallback() {
            @Override
            public void onAdLoaded(@NonNull RewardedAd ad) {
                rewardedAd = ad;
                adLoading = false;
                Log.d(TAG, "Rewarded ad loaded");

                rewardedAd.setFullScreenContentCallback(new FullScreenContentCallback() {
                    @Override
                    public void onAdDismissedFullScreenContent() {
                        rewardedAd = null;
                        loadRewardedAd();
                    }
                    @Override
                    public void onAdFailedToShowFullScreenContent(@NonNull AdError e) {
                        rewardedAd = null;
                        loadRewardedAd();
                    }
                });
            }
            @Override
            public void onAdFailedToLoad(@NonNull LoadAdError e) {
                rewardedAd = null;
                adLoading = false;
                Log.e(TAG, "Rewarded ad failed: " + e.getMessage());
            }
        });
    }

    private void loadSplashAd() {
        AdRequest req = new AdRequest.Builder().build();
        InterstitialAd.load(this, ADMOB_SPLASH_ID, req, new InterstitialAdLoadCallback() {
            @Override
            public void onAdLoaded(@NonNull InterstitialAd ad) {
                splashAd = ad;
                Log.d(TAG, "Splash ad loaded");
            }
            @Override
            public void onAdFailedToLoad(@NonNull LoadAdError e) {
                splashAd = null;
                Log.e(TAG, "Splash ad failed: " + e.getMessage());
            }
        });
    }

    // ══════════════════════════════════════════
    // Billing - نتيجة الشراء
    // ══════════════════════════════════════════
    @Override
    public void onPurchasesUpdated(BillingResult result, List<Purchase> purchases) {
        if (result.getResponseCode() == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (Purchase p : purchases) handlePurchase(p);
        }
    }

    private void handlePurchase(Purchase purchase) {
        if (purchase.getPurchaseState() != Purchase.PurchaseState.PURCHASED) return;
        String productId = purchase.getProducts().get(0);

        ConsumeParams cp = ConsumeParams.newBuilder()
            .setPurchaseToken(purchase.getPurchaseToken())
            .build();
        billingClient.consumeAsync(cp, (r, token) -> {
            if (r.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                notifyJS("onPurchaseResult", "success|" + productId);
            }
        });
    }

    private void checkPendingPurchases() {
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP).build(),
            (r, list) -> { for (Purchase p : list) handlePurchase(p); }
        );
    }

    // ══════════════════════════════════════════
    // إرسال نتيجة لـ JavaScript
    // ══════════════════════════════════════════
    private void notifyJS(final String fn, final String data) {
        runOnUiThread(() ->
            webView.evaluateJavascript(
                "if(typeof " + fn + "==='function')" + fn + "('" + data + "');", null)
        );
    }

    // ══════════════════════════════════════════
    // UI
    // ══════════════════════════════════════════
    private void hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            webView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
        }
    }

    @Override public void onWindowFocusChanged(boolean h) { super.onWindowFocusChanged(h); if(h) hideSystemUI(); }
    @Override public boolean onKeyDown(int k, KeyEvent e) {
        if (k == KeyEvent.KEYCODE_BACK) {
            webView.evaluateJavascript("if(typeof showExitDialog==='function')showExitDialog();", null);
            return true;
        }
        return super.onKeyDown(k, e);
    }
    @Override protected void onResume()  { super.onResume();  webView.onResume();  hideSystemUI(); }
    @Override protected void onPause()   { super.onPause();   webView.onPause(); }
    @Override protected void onDestroy() { if(billingClient!=null) billingClient.endConnection(); super.onDestroy(); }
    }
            
