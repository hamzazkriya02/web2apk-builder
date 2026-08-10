package com.web2apk.generated;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.DownloadListener;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private SwipeRefreshLayout refresh;
    private ProgressBar progress;

    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); setContentView(R.layout.activity_main);
        webView=findViewById(R.id.webView); refresh=findViewById(R.id.refresh); progress=findViewById(R.id.progress);
        WebSettings s=webView.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setLoadWithOverviewMode(true); s.setUseWideViewPort(true); s.setBuiltInZoomControls(false); s.setMediaPlaybackRequiresUserGesture(false); s.setUserAgentString(s.getUserAgentString()+" Web2APK/1.0");
        webView.setWebViewClient(new WebViewClient(){ @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r){ Uri u=r.getUrl(); if("http".equals(u.getScheme())||"https".equals(u.getScheme())) return false; try{startActivity(new Intent(Intent.ACTION_VIEW,u));}catch(Exception ignored){} return true;} @Override public void onPageFinished(WebView v,String url){refresh.setRefreshing(false);} });
        webView.setWebChromeClient(new WebChromeClient(){ @Override public void onProgressChanged(WebView v,int p){progress.setProgress(p);progress.setVisibility(p<100?View.VISIBLE:View.GONE);} });
        webView.setDownloadListener((url,userAgent,contentDisposition,mimeType,length)->{ try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(Exception ignored){} });
        refresh.setOnRefreshListener(webView::reload); refresh.setOnChildScrollUpCallback((parent,child)->webView.getScrollY()>0);
        getOnBackPressedDispatcher().addCallback(this,new OnBackPressedCallback(true){@Override public void handleOnBackPressed(){if(webView.canGoBack())webView.goBack();else finish();}});
        if(state==null) webView.loadUrl(getString(R.string.start_url)); else webView.restoreState(state);
    }
    @Override protected void onSaveInstanceState(Bundle out){webView.saveState(out);super.onSaveInstanceState(out);}
}
