package com.probrowser.app;


import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.HttpAuthHandler;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import com.google.android.material.button.MaterialButton;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.database.FirebaseDatabase;
import com.probrowser.app.data.FirebaseRepository;
import com.probrowser.app.model.BrowserConfig;
import com.probrowser.app.model.PopupNotification;
import com.probrowser.app.model.ProxyServer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity
        implements FirebaseRepository.Listener {

    private TextView headerTitle;
    private MaterialButton proxyButton;
    private ProgressBar progressBar;
    private WebView webView;
    private LinearLayout errorPanel;
    private TextView errorText;
    private MaterialButton refreshButton;

    private BrowserConfig browserConfig = new BrowserConfig();
    private List<ProxyServer> proxies = new ArrayList<>();

    private SharedPreferences preferences;

    private String loadedUrl = "";
    private final Handler reloadHandler = new Handler(Looper.getMainLooper());
    private final Handler popupHandler = new Handler(Looper.getMainLooper());
    private AlertDialog popupDialog;
    private String scheduledPopupId = "";
    private String shownPopupId = "";
    private int proxyRetryCount = 0;

    private final Runnable reloadRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isFinishing() && !isDestroyed()) {
                loadWebsite(true);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();

        preferences = getSharedPreferences(
                "browser_settings",
                MODE_PRIVATE
        );

        configureWebView();
        configureBackButton();
        updateProxyButton();

        /*
         * Remove any old WebView proxy first.
         * This prevents an old invalid proxy from causing a blank screen.
         */
        clearProxySafely(() -> {
            loadedUrl = "";
            loadWebsite(true);
            configureFirebase();
        });

        proxyButton.setOnClickListener(
                view -> showProxyDialog()
        );


        refreshButton.setOnClickListener(v -> {
            proxyRetryCount = 0;
            loadedUrl = "";
            webView.stopLoading();
            errorPanel.setVisibility(View.GONE);
            webView.setVisibility(View.VISIBLE);
            progressBar.setVisibility(View.VISIBLE);
            applySavedProxyThenLoad();
        });

        findViewById(R.id.retryButton).setOnClickListener(
                view -> applySavedProxyThenLoad()
        );
    }

    private void bindViews() {
        headerTitle = findViewById(R.id.headerTitle);
        proxyButton = findViewById(R.id.proxyButton);
        refreshButton = findViewById(R.id.refreshButton);
        progressBar = findViewById(R.id.progressBar);
        webView = findViewById(R.id.webView);
        errorPanel = findViewById(R.id.errorPanel);
        errorText = findViewById(R.id.errorText);
    }

    private void configureFirebase() {
        try {
            FirebaseApp firebaseApp;

            try {
                firebaseApp = FirebaseApp.getInstance();
            } catch (IllegalStateException notInitialized) {
                FirebaseOptions options = new FirebaseOptions.Builder()
                        .setApiKey(AppConfig.FIREBASE_API_KEY)
                        .setApplicationId(AppConfig.FIREBASE_APP_ID)
                        .setProjectId(AppConfig.FIREBASE_PROJECT_ID)
                        .setDatabaseUrl(AppConfig.FIREBASE_DATABASE_URL)
                        .build();

                firebaseApp = FirebaseApp.initializeApp(this, options);
            }

            if (firebaseApp == null) {
                showError("Firebase initialization failed.");
                return;
            }

            FirebaseDatabase database =
                    FirebaseDatabase.getInstance(
                            firebaseApp,
                            AppConfig.FIREBASE_DATABASE_URL
                    );

            database.goOnline();

            FirebaseRepository repository =
                    new FirebaseRepository(database);

            repository.start(this);

        } catch (Exception exception) {
            String message = exception.getMessage();

            if (message == null || message.trim().isEmpty()) {
                message = "Unknown Firebase error";
            }

            Toast.makeText(
                    this,
                    "Firebase Error: " + message,
                    Toast.LENGTH_LONG
            ).show();

            loadWebsite(true);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();

        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);

        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);

        settings.setMixedContentMode(
                WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        );

        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        String defaultUserAgent = settings.getUserAgentString();

        if (defaultUserAgent != null
                && !defaultUserAgent.contains("ProBrowser")) {

            settings.setUserAgentString(
                    defaultUserAgent + " ProBrowser/1.0"
            );
        }

        CookieManager cookieManager =
                CookieManager.getInstance();

        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(
                webView,
                true
        );

        webView.setWebChromeClient(
                new WebChromeClient() {
                    @Override
                    public void onProgressChanged(
                            WebView view,
                            int newProgress
                    ) {
                        progressBar.setProgress(newProgress);

                        if (newProgress >= 100) {
                            progressBar.setVisibility(View.GONE);
                        } else {
                            progressBar.setVisibility(View.VISIBLE);
                        }
                    }
                }
        );

        webView.setWebViewClient(
                new WebViewClient() {

                    @Override
                    public void onReceivedHttpAuthRequest(
                            WebView view,
                            HttpAuthHandler handler,
                            String host,
                            String realm
                    ) {
                        String selectedProxyId =
                                preferences.getString("proxy_id", "");

                        ProxyServer selectedProxy =
                                findProxy(selectedProxyId);

                        if (selectedProxy != null
                                && selectedProxy.username != null
                                && !selectedProxy.username.trim().isEmpty()
                                && selectedProxy.password != null
                                && !selectedProxy.password.isEmpty()) {

                            handler.proceed(
                                    selectedProxy.username,
                                    selectedProxy.password
                            );
                        } else {
                            handler.cancel();

                            showError(
                                    "Proxy authentication credentials are missing."
                            );
                        }
                    }

                    @Override
                    public void onPageStarted(
                            WebView view,
                            String url,
                            Bitmap favicon
                    ) {
                        errorPanel.setVisibility(View.GONE);
                        webView.setVisibility(View.VISIBLE);
                        progressBar.setVisibility(View.VISIBLE);
                    }

                    @Override
                    public void onPageFinished(
                            WebView view,
                            String url
                    ) {
                        proxyRetryCount = 0;
                        progressBar.setVisibility(View.GONE);
                        CookieManager.getInstance().flush();
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(
                            WebView view,
                            WebResourceRequest request
                    ) {
                        return false;
                    }

                    @Override
                    public void onReceivedError(
                            WebView view,
                            WebResourceRequest request,
                            WebResourceError error
                    ) {
                        if (request.isForMainFrame()) {
                            String description = String.valueOf(error.getDescription());
                            boolean proxyEnabled = preferences.getBoolean("proxy_enabled", false);

                            if (browserConfig.proxy_feature_enabled && proxyEnabled && proxyRetryCount < 1) {
                                proxyRetryCount++;
                                reloadHandler.postDelayed(() -> applySavedProxyThenLoad(), 700);
                                return;
                            }

                            showError("Website could not be loaded.\n" + description);
                        }
                    }
                }
        );
    }

    private void configureBackButton() {
        getOnBackPressedDispatcher().addCallback(
                this,
                new OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        if (webView.canGoBack()) {
                            webView.goBack();
                        } else {
                            finish();
                        }
                    }
                }
        );
    }
    @Override
    public void onConfigChanged(BrowserConfig config) {
        browserConfig = config == null ? new BrowserConfig() : config;

        headerTitle.setText(nonEmpty(browserConfig.header_name, "Professional Browser"));

        if (isAppExpired()) {
            showError("This app access period has expired.");
            return;
        }

        if (!browserConfig.proxy_feature_enabled) {
            preferences.edit()
                    .putBoolean("proxy_enabled", false)
                    .putString("proxy_id", "")
                    .putBoolean("proxy_initialized", true)
                    .apply();
            updateProxyButton();
            clearProxySafely(() -> scheduleReload());
            return;
        }

        if (!preferences.contains("proxy_initialized")) {
            preferences.edit()
                    .putBoolean("proxy_initialized", true)
                    .putBoolean("proxy_enabled", browserConfig.default_proxy_enabled)
                    .putString("proxy_id", nonEmpty(browserConfig.default_proxy_id, ""))
                    .apply();
        }

        updateProxyButton();
        scheduleReload();
    }

    @Override
    public void onProxiesChanged(
            List<ProxyServer> updatedProxies
    ) {
        proxies = updatedProxies == null ? new ArrayList<>() : updatedProxies;
        updateProxyButton();
    }

    @Override
    public void onPopupChanged(PopupNotification popup) {
        popupHandler.removeCallbacksAndMessages(null);

        if (popupDialog != null && popupDialog.isShowing() && (popup == null || !popup.active)) {
            popupDialog.dismiss();
            popupDialog = null;
        }

        if (popup == null || !popup.active) {
            scheduledPopupId = "";
            return;
        }

        String popupId = nonEmpty(popup.popup_id, "popup_current");

        if (popupId.equals(shownPopupId)) {
            return;
        }

        scheduledPopupId = popupId;

        if (!isFinishing() && !isDestroyed()) {
            showAdminPopup(popup, popupId);
        }
    }

    private void showAdminPopup(PopupNotification popup, String popupId) {
        if (popupDialog != null && popupDialog.isShowing()) {
            popupDialog.dismiss();
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(nonEmpty(popup.title, "Notification"))
                .setMessage(nonEmpty(popup.description, ""))
                .setNegativeButton("Close", (dialog, which) -> dialog.dismiss());

        String button1Name = nonEmpty(popup.button1_name, popup.button_name == null ? "" : popup.button_name).trim();
        String button1Link = nonEmpty(popup.button1_link, popup.button_link == null ? "" : popup.button_link).trim();
        String button2Name = popup.button2_name == null ? "" : popup.button2_name.trim();
        String button2Link = popup.button2_link == null ? "" : popup.button2_link.trim();

        if (!button1Name.isEmpty() && !button1Link.isEmpty()) {
            builder.setPositiveButton(button1Name, (dialog, which) -> {
                dialog.dismiss();
                openPopupLink(button1Link);
            });
        }

        if (!button2Name.isEmpty() && !button2Link.isEmpty()) {
            builder.setNeutralButton(button2Name, (dialog, which) -> {
                dialog.dismiss();
                openPopupLink(button2Link);
            });
        }

        popupDialog = builder.create();
        popupDialog.setOnDismissListener(dialog -> {
            popupHandler.removeCallbacksAndMessages(null);
            popupDialog = null;
        });

        shownPopupId = popupId;
        popupDialog.show();

        int durationSeconds = Math.max(1, Math.min(popup.duration_seconds, 300));
        popupHandler.postDelayed(() -> {
            if (popupDialog != null && popupDialog.isShowing()) {
                popupDialog.dismiss();
            }
        }, durationSeconds * 1000L);
    }

    private void openPopupLink(String link) {
        String target = link == null ? "" : link.trim();
        if (target.isEmpty()) {
            return;
        }

        if (!target.startsWith("http://") && !target.startsWith("https://")) {
            target = "https://" + target;
        }

        loadedUrl = target;
        errorPanel.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.VISIBLE);
        webView.loadUrl(target);
    }

    @Override
    public void onError(String message) {
        Toast.makeText(
                this,
                "Firebase Error: " + message,
                Toast.LENGTH_LONG
        ).show();

        loadWebsite(true);
    }

    private void showProxyDialog() {
        if (!browserConfig.proxy_feature_enabled) {
            Toast.makeText(this, "Proxy controls are disabled by the administrator.", Toast.LENGTH_SHORT).show();
            return;
        }

        List<String> items = new ArrayList<>();

        items.add("Proxy OFF");

        for (ProxyServer proxy : proxies) {
            items.add(proxy.toString());
        }

        String selectedId =
                preferences.getString("proxy_id", "");

        boolean enabled =
                preferences.getBoolean(
                        "proxy_enabled",
                        false
                );

        int checkedItem = 0;

        if (enabled) {
            for (int index = 0;
                 index < proxies.size();
                 index++) {

                ProxyServer proxy = proxies.get(index);

                if (proxy.id != null
                        && proxy.id.equals(selectedId)) {

                    checkedItem = index + 1;
                    break;
                }
            }
        }

        new AlertDialog.Builder(this)
                .setTitle("Select Proxy Location")
                .setSingleChoiceItems(
                        items.toArray(new String[0]),
                        checkedItem,
                        (dialog, which) -> {

                            if (which == 0) {
                                preferences.edit()
                                        .putBoolean(
                                                "proxy_enabled",
                                                false
                                        )
                                        .putString(
                                                "proxy_id",
                                                ""
                                        )
                                        .apply();

                            } else {
                                ProxyServer selectedProxy =
                                        proxies.get(which - 1);

                                if (!isValidProxy(selectedProxy)) {
                                    Toast.makeText(
                                            this,
                                            "Invalid proxy configuration.",
                                            Toast.LENGTH_LONG
                                    ).show();

                                    preferences.edit()
                                            .putBoolean(
                                                    "proxy_enabled",
                                                    false
                                            )
                                            .putString(
                                                    "proxy_id",
                                                    ""
                                            )
                                            .apply();

                                } else {
                                    preferences.edit()
                                            .putBoolean(
                                                    "proxy_enabled",
                                                    true
                                            )
                                            .putString(
                                                    "proxy_id",
                                                    selectedProxy.id
                                            )
                                            .apply();
                                }
                            }

                            dialog.dismiss();
                            updateProxyButton();
                            applySavedProxyThenLoad();
                        }
                )
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void applySavedProxyThenLoad() {
        if (isAppExpired()) {
            showError("This app access period has expired.");
            return;
        }

        if (!browserConfig.proxy_feature_enabled) {
            preferences.edit().putBoolean("proxy_enabled", false).putString("proxy_id", "").apply();
            updateProxyButton();
            clearProxySafely(() -> { loadedUrl = ""; loadWebsite(true); });
            return;
        }

        boolean proxyEnabled =
                preferences.getBoolean(
                        "proxy_enabled",
                        false
                );

        String selectedProxyId =
                preferences.getString(
                        "proxy_id",
                        ""
                );

        ProxyServer selectedProxy =
                findProxy(selectedProxyId);

        /*
         * Proxy OFF or invalid:
         * remove the old WebView proxy completely,
         * then load the website normally.
         */
        if (!proxyEnabled || !isValidProxy(selectedProxy)) {
            preferences.edit()
                    .putBoolean("proxy_enabled", false)
                    .putString("proxy_id", "")
                    .apply();

            updateProxyButton();

            clearProxySafely(() -> {
                loadedUrl = "";
                webView.stopLoading();
                webView.clearCache(true);
                loadWebsite(true);
            });

            return;
        }

        /*
         * Valid proxy:
         * apply it and reload the website.
         */
        applyProxySafely(
                selectedProxy,
                () -> {
                    loadedUrl = "";
                    webView.stopLoading();
                    webView.clearCache(true);
                    loadWebsite(true);
                }
        );
    }

    private boolean isValidProxy(ProxyServer proxy) {
        if (proxy == null) {
            return false;
        }

        if (!proxy.enabled) {
            return false;
        }

        if (proxy.host == null
                || proxy.host.trim().isEmpty()) {
            return false;
        }

        if (proxy.port <= 0
                || proxy.port > 65535) {
            return false;
        }

        String scheme = proxy.scheme;

        if (scheme == null
                || scheme.trim().isEmpty()) {
            return false;
        }

        return scheme.equalsIgnoreCase("http")
                || scheme.equalsIgnoreCase("https")
                || scheme.equalsIgnoreCase("socks")
                || scheme.equalsIgnoreCase("socks5");
    }

    private ProxyServer findProxy(String proxyId) {
        if (proxyId == null || proxyId.trim().isEmpty()) {
            return null;
        }

        for (ProxyServer proxy : proxies) {
            if (proxy.id != null
                    && proxy.id.equals(proxyId)) {
                return proxy;
            }
        }

        return null;
    }

    private void applyProxySafely(
            ProxyServer proxy,
            Runnable after
    ) {
        if (!isValidProxy(proxy)) {
            Toast.makeText(
                    this,
                    "Invalid proxy configuration.",
                    Toast.LENGTH_LONG
            ).show();

            preferences.edit()
                    .putBoolean("proxy_enabled", false)
                    .putString("proxy_id", "")
                    .apply();

            updateProxyButton();
            clearProxySafely(after);
            return;
        }

        if (!WebViewFeature.isFeatureSupported(
                WebViewFeature.PROXY_OVERRIDE
        )) {
            Toast.makeText(
                    this,
                    "This device does not support WebView proxy override.",
                    Toast.LENGTH_LONG
            ).show();

            preferences.edit()
                    .putBoolean("proxy_enabled", false)
                    .putString("proxy_id", "")
                    .apply();

            updateProxyButton();
            after.run();
            return;
        }

        try {
            ProxyConfig proxyConfig =
                    new ProxyConfig.Builder()
                            .addProxyRule(proxy.proxyRule())
                            .addBypassRule("localhost")
                            .addBypassRule("127.0.0.1")
                            .build();

            runProxyOperationWithTimeout(
                    callback ->
                            ProxyController.getInstance()
                                    .setProxyOverride(
                                            proxyConfig,
                                            mainExecutor(),
                                            callback
                                    ),
                    after
            );

        } catch (Exception exception) {
            preferences.edit()
                    .putBoolean("proxy_enabled", false)
                    .putString("proxy_id", "")
                    .apply();

            updateProxyButton();

            Toast.makeText(
                    this,
                    "Proxy could not be applied.",
                    Toast.LENGTH_LONG
            ).show();

            clearProxySafely(after);
        }
    }

    private void clearProxySafely(Runnable after) {
        if (!WebViewFeature.isFeatureSupported(
                WebViewFeature.PROXY_OVERRIDE
        )) {
            after.run();
            return;
        }

        runProxyOperationWithTimeout(
                callback ->
                        ProxyController.getInstance()
                                .clearProxyOverride(
                                        mainExecutor(),
                                        callback
                                ),
                after
        );
    }

    private interface ProxyOperation {
        void run(Runnable callback);
    }

    private Executor mainExecutor() {
        return command -> runOnUiThread(command);
    }

    private void runProxyOperationWithTimeout(
            ProxyOperation operation,
            Runnable after
    ) {
        AtomicBoolean completed =
                new AtomicBoolean(false);

        Runnable finishOnce = () -> {
            if (completed.compareAndSet(false, true)) {
                after.run();
            }
        };

        try {
            operation.run(finishOnce);

            new Handler(
                    Looper.getMainLooper()
            ).postDelayed(
                    finishOnce,
                    2000
            );

        } catch (Exception exception) {
            finishOnce.run();
        }
    }

    private void scheduleReload() {
        reloadHandler.removeCallbacks(reloadRunnable);
        reloadHandler.postDelayed(reloadRunnable, 500);
    }

    private void loadWebsite() {
        loadWebsite(false);
    }

    private void loadWebsite(boolean force) {
        if (isAppExpired()) {
            showError("This app access period has expired.");
            return;
        }

        String websiteUrl = nonEmpty(
                browserConfig.website_url,
                "https://example.com"
        ).trim();

        if (!websiteUrl.startsWith("http://")
                && !websiteUrl.startsWith("https://")) {

            websiteUrl = "https://" + websiteUrl;
        }

        errorPanel.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.VISIBLE);

        if (force || !websiteUrl.equals(loadedUrl)) {
            loadedUrl = websiteUrl;
            webView.loadUrl(websiteUrl);

        } else if (webView.getUrl() == null
                || webView.getUrl().trim().isEmpty()) {

            webView.loadUrl(websiteUrl);
        }
    }

    private void updateProxyButton() {
        boolean featureEnabled = browserConfig.proxy_feature_enabled;
        boolean proxyEnabled = featureEnabled && preferences.getBoolean("proxy_enabled", false);

        proxyButton.setEnabled(featureEnabled);
        proxyButton.setAlpha(featureEnabled ? 1.0f : 0.45f);
        proxyButton.setText(proxyEnabled ? "P✓" : "P");
        proxyButton.setContentDescription(featureEnabled
                ? (proxyEnabled ? "Proxy enabled" : "Proxy disabled")
                : "Proxy disabled by administrator");
    }


    private boolean isAppExpired() {
        return browserConfig != null
                && browserConfig.app_expiry_enabled
                && browserConfig.app_expires_at > 0
                && (System.currentTimeMillis() / 1000L) >= browserConfig.app_expires_at;
    }

    private void showError(String message) {
        errorText.setText(message);
        errorPanel.setVisibility(View.VISIBLE);
        webView.setVisibility(View.GONE);
        progressBar.setVisibility(View.GONE);
    }

    private String nonEmpty(
            String value,
            String fallback
    ) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }

        return value;
    }

    @Override
    protected void onDestroy() {
        reloadHandler.removeCallbacksAndMessages(null);
        popupHandler.removeCallbacksAndMessages(null);

        if (popupDialog != null && popupDialog.isShowing()) {
            popupDialog.dismiss();
            popupDialog = null;
        }

        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.clearHistory();
            webView.removeAllViews();
            webView.destroy();
        }

        super.onDestroy();
    }
}