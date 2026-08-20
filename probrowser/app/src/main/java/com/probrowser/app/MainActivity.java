package com.probrowser.app;


import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
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
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity
        implements FirebaseRepository.Listener {

    private static final int FILE_CHOOSER_REQUEST_CODE = 4101;

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
    private boolean configLoaded = false;
    private boolean proxiesLoaded = false;
    private AuthenticatedProxyBridge proxyBridge;
    private ValueCallback<Uri[]> fileChooserCallback;

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
        // Do not load any placeholder URL before Firebase returns the real app config.
        // This permanently removes the old example.com flash/page.
        progressBar.setVisibility(View.VISIBLE);
        webView.setVisibility(View.INVISIBLE);
        clearProxySafely(() -> {
            loadedUrl = "";
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
            progressBar.setVisibility(View.VISIBLE);
            if (!configLoaded) {
                webView.setVisibility(View.INVISIBLE);
                configureFirebase();
            } else {
                webView.setVisibility(View.VISIBLE);
                applySavedProxyThenLoad();
            }
        });

        findViewById(R.id.retryButton).setOnClickListener(view -> {
            proxyRetryCount = 0;
            errorPanel.setVisibility(View.GONE);
            progressBar.setVisibility(View.VISIBLE);
            if (!configLoaded) {
                webView.setVisibility(View.INVISIBLE);
                configureFirebase();
            } else {
                applySavedProxyThenLoad();
            }
        });
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
                    public boolean onShowFileChooser(
                            WebView view,
                            ValueCallback<Uri[]> filePathCallback,
                            WebChromeClient.FileChooserParams fileChooserParams
                    ) {
                        if (fileChooserCallback != null) {
                            fileChooserCallback.onReceiveValue(null);
                        }
                        fileChooserCallback = filePathCallback;

                        Intent pickerIntent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        pickerIntent.addCategory(Intent.CATEGORY_OPENABLE);
                        pickerIntent.setType("*/*");
                        pickerIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                                fileChooserParams.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);

                        try {
                            startActivityForResult(
                                    Intent.createChooser(pickerIntent, "Select file"),
                                    FILE_CHOOSER_REQUEST_CODE
                            );
                        } catch (ActivityNotFoundException exception) {
                            fileChooserCallback.onReceiveValue(null);
                            fileChooserCallback = null;
                            Toast.makeText(MainActivity.this,
                                    "No file picker is available on this device.",
                                    Toast.LENGTH_LONG).show();
                        }
                        return true;
                    }

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
                        return openExternalLink(request.getUrl());
                    }

                    @Override
                    public void onReceivedError(
                            WebView view,
                            WebResourceRequest request,
                            WebResourceError error
                    ) {
                        if (!request.isForMainFrame()) return;

                        int code = error.getErrorCode();
                        boolean proxyEnabled = browserConfig.proxy_feature_enabled
                                && preferences.getBoolean("proxy_enabled", false);

                        if (proxyEnabled
                                && code != WebViewClient.ERROR_PROXY_AUTHENTICATION
                                && proxyRetryCount < 1) {
                            proxyRetryCount++;
                            webView.stopLoading();
                            webView.setVisibility(View.INVISIBLE);
                            progressBar.setVisibility(View.VISIBLE);
                            reloadHandler.postDelayed(() -> applySavedProxyThenLoad(), 1200);
                            return;
                        }

                        if (proxyEnabled && code == WebViewClient.ERROR_PROXY_AUTHENTICATION) {
                            showError("Proxy login failed. Check the proxy username/password in Admin.");
                        } else if (proxyEnabled && code == WebViewClient.ERROR_TIMEOUT) {
                            showError("Proxy connection timed out. Tap Retry or choose another proxy.");
                        } else if (proxyEnabled) {
                            showError("Proxy could not load the website. Tap Retry or turn the proxy off.");
                        } else {
                            showError("Website could not be loaded. Please check the website or internet connection.");
                        }
                    }

                    @Override
                    public void onReceivedHttpError(
                            WebView view,
                            WebResourceRequest request,
                            WebResourceResponse errorResponse
                    ) {
                        if (!request.isForMainFrame()) return;
                        int status = errorResponse.getStatusCode();
                        boolean proxyEnabled = browserConfig.proxy_feature_enabled
                                && preferences.getBoolean("proxy_enabled", false);
                        if (proxyEnabled && status == 407) {
                            showError("Proxy authentication failed. Check username/password in Admin.");
                        } else if (proxyEnabled && status >= 500) {
                            showError("Proxy server is temporarily unavailable. Tap Retry.");
                        }
                    }
                }
        );
    }

    /**
     * Opens WhatsApp invitations/channels in WhatsApp instead of keeping them
     * inside the WebView. Other non-web schemes (tel:, mailto:, intent:, etc.)
     * are also handed to Android, while normal web links continue in the app.
     */
    private boolean openExternalLink(Uri uri) {
        if (uri == null) return false;

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean whatsappLink = scheme.equals("whatsapp")
                || host.equals("wa.me")
                || host.endsWith(".wa.me")
                || host.equals("whatsapp.com")
                || host.endsWith(".whatsapp.com");

        if (whatsappLink) {
            Intent whatsappIntent = new Intent(Intent.ACTION_VIEW, uri);
            whatsappIntent.setPackage("com.whatsapp");
            try {
                startActivity(whatsappIntent);
                return true;
            } catch (ActivityNotFoundException ignored) {
                // WhatsApp is not installed, or this link type is handled by a browser.
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    return true;
                } catch (ActivityNotFoundException exception) {
                    Toast.makeText(this, "No app is available to open this WhatsApp link.", Toast.LENGTH_LONG).show();
                    return true;
                }
            }
        }

        if (!scheme.equals("http") && !scheme.equals("https")) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (ActivityNotFoundException exception) {
                Toast.makeText(this, "No app is available to open this link.", Toast.LENGTH_LONG).show();
            }
            return true;
        }

        return false;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST_CODE || fileChooserCallback == null) return;

        Uri[] selectedFiles = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
        fileChooserCallback.onReceiveValue(selectedFiles);
        fileChooserCallback = null;
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

        String configuredUrl = browserConfig.website_url == null ? "" : browserConfig.website_url.trim();
        if (configuredUrl.isEmpty()) {
            configLoaded = false;
            showError("Website URL is not configured for this app. Set it from Admin.");
            return;
        }
        configLoaded = true;

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
            clearProxySafely(() -> loadWebsite(true));
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

        // If a proxy is enabled, wait until its Firebase record is loaded before loading the site.
        boolean wantsProxy = preferences.getBoolean("proxy_enabled", false);
        if (!wantsProxy || proxiesLoaded) {
            applySavedProxyThenLoad();
        } else {
            progressBar.setVisibility(View.VISIBLE);
            webView.setVisibility(View.INVISIBLE);
        }
    }

    @Override
    public void onProxiesChanged(
            List<ProxyServer> updatedProxies
    ) {
        proxies = updatedProxies == null ? new ArrayList<>() : updatedProxies;
        proxiesLoaded = true;
        updateProxyButton();
        if (configLoaded) {
            applySavedProxyThenLoad();
        }
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

        if (!configLoaded) {
            showError("Could not load app settings. Check internet connection and tap Retry.");
        }
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
        if (!proxyEnabled) {
            updateProxyButton();
            clearProxySafely(() -> {
                loadedUrl = "";
                webView.stopLoading();
                loadWebsite(true);
            });
            return;
        }

        if (!proxiesLoaded) {
            progressBar.setVisibility(View.VISIBLE);
            webView.setVisibility(View.INVISIBLE);
            return;
        }

        if (!isValidProxy(selectedProxy)) {
            updateProxyButton();
            showError("The selected proxy is unavailable or invalid. Choose another proxy or turn proxy OFF.");
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
            stopProxyBridge();

            String proxyRule;
            String scheme = proxy.scheme == null ? "http" : proxy.scheme.trim().toLowerCase();

            if (proxy.hasCredentials() && (scheme.equals("http") || scheme.equals("https"))) {
                // Authenticated HTTP/HTTPS proxies need an explicit Proxy-Authorization header.
                // WebView ProxyController has no username/password API, so route WebView through
                // a loopback bridge that authenticates to the upstream proxy.
                proxyBridge = new AuthenticatedProxyBridge(proxy);
                int port = proxyBridge.start();
                proxyRule = "http://127.0.0.1:" + port;
            } else if (proxy.hasCredentials() && (scheme.equals("socks") || scheme.equals("socks5"))) {
                showError("Authenticated SOCKS is not used by this build. In Admin use this proxy's HTTPS/HTTP port instead.");
                return;
            } else {
                proxyRule = proxy.proxyRule();
            }

            ProxyConfig proxyConfig =
                    new ProxyConfig.Builder()
                            .addProxyRule(proxyRule)
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
        stopProxyBridge();
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

    private void stopProxyBridge() {
        if (proxyBridge != null) {
            try {
                proxyBridge.close();
            } catch (Exception ignored) {
            }
            proxyBridge = null;
        }
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
        AtomicBoolean completed = new AtomicBoolean(false);
        Handler handler = new Handler(Looper.getMainLooper());
        final Runnable[] timeoutHolder = new Runnable[1];

        Runnable finishOnce = () -> {
            if (completed.compareAndSet(false, true)) {
                if (timeoutHolder[0] != null) {
                    handler.removeCallbacks(timeoutHolder[0]);
                }
                // Wait for ProxyController's listener before any WebView load.
                after.run();
            }
        };

        timeoutHolder[0] = () -> {
            if (completed.compareAndSet(false, true)) {
                showError("Network proxy setup timed out. Tap Retry.");
            }
        };

        try {
            operation.run(finishOnce);
            handler.postDelayed(timeoutHolder[0], 10000);
        } catch (Exception exception) {
            if (completed.compareAndSet(false, true)) {
                showError("Network proxy setup failed. Tap Retry.");
            }
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

        if (!configLoaded) {
            progressBar.setVisibility(View.VISIBLE);
            webView.setVisibility(View.INVISIBLE);
            return;
        }

        String websiteUrl = browserConfig.website_url == null
                ? ""
                : browserConfig.website_url.trim();

        if (websiteUrl.isEmpty()) {
            showError("Website URL is not configured for this app.");
            return;
        }

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
        stopProxyBridge();

        if (webView != null) {
            webView.stopLoading();
        }
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
        stopProxyBridge();
        popupHandler.removeCallbacksAndMessages(null);

        if (fileChooserCallback != null) {
            fileChooserCallback.onReceiveValue(null);
            fileChooserCallback = null;
        }

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
