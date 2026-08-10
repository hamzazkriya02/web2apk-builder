package com.web2apk.generated;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.DownloadListener;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

public class MainActivity extends AppCompatActivity {

    private static final int FILE_CHOOSER_REQUEST_CODE = 1001;

    private WebView webView;
    private SwipeRefreshLayout refreshLayout;
    private ProgressBar progressBar;

    private ValueCallback<Uri[]> fileUploadCallback;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        refreshLayout = findViewById(R.id.refresh);
        progressBar = findViewById(R.id.progress);

        configureWebView();

        refreshLayout.setOnRefreshListener(
            () -> webView.reload()
        );

        refreshLayout.setOnChildScrollUpCallback(
            (parent, child) -> webView.getScrollY() > 0
        );

        if (savedInstanceState == null) {
            webView.loadUrl(getString(R.string.start_url));
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {

        WebSettings settings = webView.getSettings();

        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);

        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);

        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        settings.setMediaPlaybackRequiresUserGesture(false);

        settings.setUserAgentString(
            settings.getUserAgentString() + " Web2APK/1.0"
        );

        webView.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(
                WebView view,
                WebResourceRequest request
            ) {
                Uri uri = request.getUrl();

                String scheme = uri.getScheme();

                if (
                    "http".equalsIgnoreCase(scheme) ||
                    "https".equalsIgnoreCase(scheme)
                ) {
                    return false;
                }

                try {
                    Intent intent = new Intent(
                        Intent.ACTION_VIEW,
                        uri
                    );

                    startActivity(intent);

                } catch (Exception ignored) {
                    Toast.makeText(
                        MainActivity.this,
                        "Unable to open this link",
                        Toast.LENGTH_SHORT
                    ).show();
                }

                return true;
            }

            @Override
            public void onPageFinished(
                WebView view,
                String url
            ) {
                refreshLayout.setRefreshing(false);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {

            @Override
            public void onProgressChanged(
                WebView view,
                int newProgress
            ) {
                progressBar.setProgress(newProgress);

                progressBar.setVisibility(
                    newProgress < 100
                        ? View.VISIBLE
                        : View.GONE
                );
            }

            @Override
            public boolean onShowFileChooser(
                WebView webView,
                ValueCallback<Uri[]> filePathCallback,
                FileChooserParams fileChooserParams
            ) {

                if (fileUploadCallback != null) {
                    fileUploadCallback.onReceiveValue(null);
                }

                fileUploadCallback = filePathCallback;

                Intent fileChooserIntent;

                try {
                    fileChooserIntent =
                        fileChooserParams.createIntent();

                    startActivityForResult(
                        fileChooserIntent,
                        FILE_CHOOSER_REQUEST_CODE
                    );

                    return true;

                } catch (Exception exception) {

                    fileUploadCallback = null;

                    Toast.makeText(
                        MainActivity.this,
                        "No file picker is available",
                        Toast.LENGTH_SHORT
                    ).show();

                    return false;
                }
            }
        });

        webView.setDownloadListener(
            new DownloadListener() {
                @Override
                public void onDownloadStart(
                    String url,
                    String userAgent,
                    String contentDisposition,
                    String mimeType,
                    long contentLength
                ) {
                    try {
                        Intent intent = new Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(url)
                        );

                        startActivity(intent);

                    } catch (Exception ignored) {
                        Toast.makeText(
                            MainActivity.this,
                            "Unable to download file",
                            Toast.LENGTH_SHORT
                        ).show();
                    }
                }
            }
        );
    }

    @Override
    protected void onActivityResult(
        int requestCode,
        int resultCode,
        @Nullable Intent data
    ) {
        super.onActivityResult(
            requestCode,
            resultCode,
            data
        );

        if (
            requestCode == FILE_CHOOSER_REQUEST_CODE &&
            fileUploadCallback != null
        ) {
            Uri[] selectedFiles =
                WebChromeClient.FileChooserParams.parseResult(
                    resultCode,
                    data
                );

            fileUploadCallback.onReceiveValue(selectedFiles);
            fileUploadCallback = null;
        }
    }

    @Override
    public void onBackPressed() {

        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onSaveInstanceState(
        Bundle outState
    ) {
        webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }
}
