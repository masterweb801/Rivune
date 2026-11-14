package com.logicrealm.rivune.web;

import android.app.Activity;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import android.graphics.Color;
import com.logicrealm.rivune.ui.EdgeToEdge;

public class WebViewActivity extends Activity {

    public static final String EXTRA_SERVER_URL = "server_url";

    private WebView webView;
    private ProgressBar pageProgress;
    private View errorLayout;
    private TextView errorMessage;

    private String serverUrl;
    private Uri serverOrigin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(com.logicrealm.rivune.R.layout.activity_webview);
        EdgeToEdge.apply(this, findViewById(com.logicrealm.rivune.R.id.rootContainer));

        webView = findViewById(
                com.logicrealm.rivune.R.id.webView
        );

        pageProgress = findViewById(
                com.logicrealm.rivune.R.id.pageProgress
        );

        errorLayout = findViewById(
                com.logicrealm.rivune.R.id.errorLayout
        );

        errorMessage = findViewById(
                com.logicrealm.rivune.R.id.errorMessage
        );

        TextView address = findViewById(
                com.logicrealm.rivune.R.id.serverAddress
        );

        Button reloadButton = findViewById(
                com.logicrealm.rivune.R.id.reloadButton
        );

        TextView closeButton = findViewById(
                com.logicrealm.rivune.R.id.closeButton
        );

        serverUrl = getIntent().getStringExtra(EXTRA_SERVER_URL);

        if (!isValidServerUrl(serverUrl)) {
            Toast.makeText(
                    this,
                    "Invalid server address",
                    Toast.LENGTH_LONG
            ).show();

            finish();
            return;
        }

        serverOrigin = Uri.parse(serverUrl);
        address.setText(serverOrigin.getHost() + ":" +
                serverOrigin.getPort());

        closeButton.setOnClickListener(v -> finish());
        reloadButton.setOnClickListener(v -> loadServer());

        configureWebView();

        if (savedInstanceState != null &&
                webView.restoreState(savedInstanceState) != null) {
            // Previous WebView state restored.
        } else {
            loadServer();
        }
    }

    private boolean isValidServerUrl(String url) {

        if (url == null || url.trim().isEmpty()) {
            return false;
        }

        try {
            Uri uri = Uri.parse(url);

            return "http".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && !uri.getHost().isEmpty()
                    && uri.getPort() >= 1
                    && uri.getPort() <= 65535
                    && uri.getUserInfo() == null;

        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isAllowedOrigin(Uri uri) {

        if (uri == null || serverOrigin == null) {
            return false;
        }

        return "http".equalsIgnoreCase(uri.getScheme())
                && serverOrigin.getHost().equalsIgnoreCase(
                        uri.getHost()
                )
                && serverOrigin.getPort() == uri.getPort()
                && uri.getUserInfo() == null;
    }

    private void configureWebView() {

        WebSettings settings = webView.getSettings();

        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);

        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);

        settings.setMixedContentMode(
                WebSettings.MIXED_CONTENT_NEVER_ALLOW
        );

        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, false);

        webView.setBackgroundColor(Color.rgb(17, 19, 24));

        webView.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view,
                    WebResourceRequest request
            ) {

                if (!request.isForMainFrame()) {
                    return false;
                }

                if (isAllowedOrigin(request.getUrl())) {
                    return false;
                }

                Toast.makeText(
                        WebViewActivity.this,
                        "Navigation outside this server is blocked",
                        Toast.LENGTH_SHORT
                ).show();

                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view,
                    String url
            ) {

                if (isAllowedOrigin(Uri.parse(url))) {
                    return false;
                }

                return true;
            }

            @Override
            public void onPageStarted(
                    WebView view,
                    String url,
                    android.graphics.Bitmap favicon
            ) {
                super.onPageStarted(view, url, favicon);

                errorLayout.setVisibility(View.GONE);
                pageProgress.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(
                    WebView view,
                    String url
            ) {
                super.onPageFinished(view, url);

                pageProgress.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedError(
                    WebView view,
                    WebResourceRequest request,
                    WebResourceError error
            ) {
                super.onReceivedError(view, request, error);

                if (request.isForMainFrame()) {
                    showError("Your RIVUNE server could not be reached.");
                }
            }

            @Override
            public void onReceivedSslError(
                    WebView view,
                    SslErrorHandler handler,
                    android.net.http.SslError error
            ) {
                handler.cancel();
                showError("The server security certificate is invalid.");
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {

            @Override
            public void onProgressChanged(
                    WebView view,
                    int progress
            ) {
                pageProgress.setProgress(progress);

                if (progress >= 100) {
                    pageProgress.setVisibility(View.GONE);
                } else {
                    pageProgress.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    private void loadServer() {

        errorLayout.setVisibility(View.GONE);
        pageProgress.setVisibility(View.VISIBLE);

        webView.loadUrl(serverUrl);
    }

    private void showError(String message) {

        pageProgress.setVisibility(View.GONE);
        errorLayout.setVisibility(View.VISIBLE);
        errorMessage.setText(message);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    protected void onPause() {

        if (webView != null) {
            webView.onPause();
        }

        super.onPause();
    }

    @Override
    public void onBackPressed() {

        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {

        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);

            if (webView.getParent() instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) webView.getParent())
                        .removeView(webView);
            }

            webView.destroy();
            webView = null;
        }

        super.onDestroy();
    }
}
