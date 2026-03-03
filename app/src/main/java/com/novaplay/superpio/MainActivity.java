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
import com.android.billingclient.api.*;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends Activity implements PurchasesUpdatedListener {

    private static final String TAG = "SuperPio";
    private WebView webView;
    private WebViewAssetLoader assetLoader;
    private BillingClient billingClient;

    // IDs المنتجات - يجب أن تطابق ما أنشأته في Play Console
    private static final List<String> PRODUCT_IDS = Arrays.asList(
        "next_level",
        "all_levels",
        "coins_1000",
        "hearts_20"
    );

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

        // إعداد Billing Client
        billingClient = BillingClient.newBuilder(this)
            .setListener(this)
            .enablePendingPurchases()
            .build();

        // الاتصال بـ Google Play
        billingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(BillingResult result) {
                if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    Log.d(TAG, "Billing connected");
                    // تحقق من المشتريات السابقة غير المكتملة
                    checkPendingPurchases();
                }
            }
            @Override
            public void onBillingServiceDisconnected() {
                Log.d(TAG, "Billing disconnected");
            }
        });

        // WebViewAssetLoader
        assetLoader = new WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
            .build();

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                WebResourceResponse response = assetLoader.shouldInterceptRequest(request.getUrl());
                if (response != null) return response;
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                hideSystemUI();
            }
        });

        webView.setWebChromeClient(new WebChromeClient());

        // JavascriptInterface - الجسر بين JS و Java
        webView.addJavascriptInterface(new BillingBridge(), "AndroidBilling");

        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }

    // ═══════════════════════════════════════
    // الجسر بين JavaScript و Google Play
    // ═══════════════════════════════════════
    private class BillingBridge {

        // استدعاء من JS لبدء الشراء
        @JavascriptInterface
        public void purchase(String productId) {
            Log.d(TAG, "Purchase requested: " + productId);

            if (!billingClient.isReady()) {
                notifyJS("error", productId, "Billing not ready");
                return;
            }

            // جلب تفاصيل المنتج من Google Play
            QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                .setProductList(Arrays.asList(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                ))
                .build();

            billingClient.queryProductDetailsAsync(params, (billingResult, productDetailsList) -> {
                if (billingResult.getResponseCode() != BillingClient.BillingResponseCode.OK
                        || productDetailsList.isEmpty()) {
                    notifyJS("error", productId, "Product not found");
                    return;
                }

                // فتح شاشة الدفع
                BillingFlowParams flowParams = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(Arrays.asList(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(productDetailsList.get(0))
                            .build()
                    ))
                    .build();

                runOnUiThread(() ->
                    billingClient.launchBillingFlow(MainActivity.this, flowParams)
                );
            });
        }

        // إغلاق التطبيق
        @JavascriptInterface
        public void exitApp() {
            finish();
        }
    }

    // ═══════════════════════════════════════
    // نتيجة الشراء
    // ═══════════════════════════════════════
    @Override
    public void onPurchasesUpdated(BillingResult result, List<Purchase> purchases) {
        if (result.getResponseCode() == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (Purchase purchase : purchases) {
                handlePurchase(purchase);
            }
        } else if (result.getResponseCode() == BillingClient.BillingResponseCode.USER_CANCELED) {
            Log.d(TAG, "Purchase cancelled");
        } else {
            Log.e(TAG, "Purchase error: " + result.getDebugMessage());
        }
    }

    private void handlePurchase(Purchase purchase) {
        if (purchase.getPurchaseState() != Purchase.PurchaseState.PURCHASED) return;

        String productId = purchase.getProducts().get(0);
        Log.d(TAG, "Purchase success: " + productId);

        // تأكيد الشراء لـ Google Play (مطلوب وإلا يُسترد المال)
        ConsumeParams consumeParams = ConsumeParams.newBuilder()
            .setPurchaseToken(purchase.getPurchaseToken())
            .build();

        billingClient.consumeAsync(consumeParams, (billingResult, purchaseToken) -> {
            if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                // أبلغ JS بنجاح الشراء
                notifyJS("success", productId, "");
            }
        });
    }

    // تحقق من مشتريات لم تكتمل
    private void checkPendingPurchases() {
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build(),
            (billingResult, purchases) -> {
                if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    for (Purchase purchase : purchases) {
                        handlePurchase(purchase);
                    }
                }
            }
        );
    }

    // إرسال نتيجة الشراء لـ JavaScript
    private void notifyJS(final String status, final String productId, final String error) {
        runOnUiThread(() -> {
            String js = "if(typeof onAndroidPurchase==='function')" +
                "onAndroidPurchase('" + status + "','" + productId + "','" + error + "');";
            webView.evaluateJavascript(js, null);
        });
    }

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

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUI();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            webView.evaluateJavascript(
                "if(typeof showExitDialog==='function')showExitDialog();", null);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        hideSystemUI();
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
    }

    @Override
    protected void onDestroy() {
        if (billingClient != null) billingClient.endConnection();
        super.onDestroy();
    }
                              }
                    
