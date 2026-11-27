package com.logicrealm.rivune;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

    private static final String START_URL = "https://rivune.page.gd/";

    private FrameLayout root;
    private WebView webView;
    private NativePlaybackBridge playbackBridge;
    private View splashView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        hideNavigationBar();

        root = new FrameLayout(this);
        root.setBackgroundColor(getColorCompat(R.color.rivune_midnight));
        setContentView(root);

        openRivune();
    }

    private int getColorCompat(int colorResource) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return getResources().getColor(colorResource, getTheme());
        }
        //noinspection deprecation
        return getResources().getColor(colorResource);
    }

    private int dp(float value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()
        );
    }

    private void hideNavigationBar() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        );
    }

    private boolean isTrustedUrl(String url) {
        if (url == null) return false;
        android.net.Uri uri = android.net.Uri.parse(url);
        return "https".equalsIgnoreCase(uri.getScheme())
                && "rivune.page.gd".equalsIgnoreCase(uri.getHost())
                && (uri.getPort() == -1 || uri.getPort() == 443);
    }

    private View createSplashScreen() {
        FrameLayout splash = new FrameLayout(this);
        splash.setBackgroundColor(getColorCompat(R.color.rivune_midnight));
        splash.setClickable(true);
        splash.setFocusable(true);

        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams centerParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
        );

        ImageView rivuneLogo = new ImageView(this);
        rivuneLogo.setImageResource(R.drawable.rivune_logo);
        rivuneLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        rivuneLogo.setContentDescription("RIVUNE logo");
        center.addView(rivuneLogo, new LinearLayout.LayoutParams(dp(126), dp(126)));

        TextView rivuneName = new TextView(this);
        rivuneName.setText("RIVUNE");
        rivuneName.setTextColor(Color.WHITE);
        rivuneName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 27);
        rivuneName.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        rivuneName.setLetterSpacing(0.16f);
        rivuneName.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        nameParams.topMargin = dp(17);
        center.addView(rivuneName, nameParams);
        splash.addView(center, centerParams);

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setGravity(Gravity.CENTER);

        TextView poweredBy = new TextView(this);
        poweredBy.setText("Powered by");
        poweredBy.setTextColor(getColorCompat(R.color.rivune_muted));
        poweredBy.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        poweredBy.setGravity(Gravity.CENTER);
        footer.addView(poweredBy);

        LinearLayout brandRow = new LinearLayout(this);
        brandRow.setOrientation(LinearLayout.HORIZONTAL);
        brandRow.setGravity(Gravity.CENTER_VERTICAL);

        ImageView logicRealmLogo = new ImageView(this);
        logicRealmLogo.setImageResource(R.drawable.logic_realm_logo);
        logicRealmLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logicRealmLogo.setContentDescription("Logic Realm logo");
        LinearLayout.LayoutParams brandLogoParams = new LinearLayout.LayoutParams(
                dp(31), dp(31)
        );
        brandLogoParams.rightMargin = dp(9);
        brandRow.addView(logicRealmLogo, brandLogoParams);

        TextView logicRealmName = new TextView(this);
        logicRealmName.setText("Logic Realm");
        logicRealmName.setTextColor(Color.WHITE);
        logicRealmName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        logicRealmName.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        brandRow.addView(logicRealmName);
        LinearLayout.LayoutParams brandRowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        brandRowParams.topMargin = dp(5);
        footer.addView(brandRow, brandRowParams);

        FrameLayout.LayoutParams footerParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL
        );
        footerParams.bottomMargin = dp(34);
        splash.addView(footer, footerParams);

        return splash;
    }

    private void hideSplashScreen() {
        if (splashView == null) return;
        View oldSplash = splashView;
        splashView = null;
        oldSplash.animate()
                .alpha(0f)
                .setDuration(180)
                .withEndAction(() -> {
                    if (root != null) root.removeView(oldSplash);
                })
                .start();
    }

    private void openRivune() {
        try {
            webView = new WebView(this);
            webView.setBackgroundColor(Color.BLACK);
            webView.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
            ));

            WebSettings settings = webView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setMediaPlaybackRequiresUserGesture(false);
            settings.setSupportZoom(false);
            settings.setBuiltInZoomControls(false);
            settings.setDisplayZoomControls(false);
            settings.setJavaScriptCanOpenWindowsAutomatically(false);
            settings.setSupportMultipleWindows(false);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            settings.setAllowFileAccess(false);
            settings.setAllowContentAccess(false);

            CookieManager cookieManager = CookieManager.getInstance();
            cookieManager.setAcceptCookie(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cookieManager.setAcceptThirdPartyCookies(webView, false);
            }

            playbackBridge = new NativePlaybackBridge(this, webView);
            webView.addJavascriptInterface(playbackBridge, "RivuneNative");

            webView.setWebChromeClient(new WebChromeClient());
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    return !isTrustedUrl(request.getUrl().toString());
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView view, String url) {
                    return !isTrustedUrl(url);
                }

                @Override
                public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                    if (playbackBridge != null) playbackBridge.onPageStarted(url);
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    if (playbackBridge != null) playbackBridge.onPageFinished(url);
                    if (isTrustedUrl(url)) hideSplashScreen();
                }

                @Override
                public boolean onRenderProcessGone(WebView view,
                        android.webkit.RenderProcessGoneDetail detail) {
                    if (view != null) {
                        root.removeView(view);
                        view.destroy();
                    }
                    if (playbackBridge != null) {
                        playbackBridge.release();
                        playbackBridge = null;
                    }
                    webView = null;
                    root.post(() -> openRivune());
                    return true;
                }
            });

            root.removeAllViews();
            root.addView(webView);
            splashView = createSplashScreen();
            root.addView(splashView);
            webView.loadUrl(START_URL);
        } catch (RuntimeException error) {
            showStartupError(error);
        }
    }

    private void showStartupError(RuntimeException error) {
        splashView = null;
        TextView message = new TextView(this);
        message.setTextColor(Color.WHITE);
        message.setTextSize(16);
        message.setGravity(Gravity.CENTER);
        message.setPadding(32, 32, 32, 32);
        message.setText(
                "RIVUNE could not start its browser component.\n\n"
                        + error.getClass().getSimpleName()
                        + ": "
                        + String.valueOf(error.getMessage())
                        + "\n\nPlease update Android System WebView or Chrome, then reopen RIVUNE."
        );
        root.removeAllViews();
        root.addView(message, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideNavigationBar();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            // An explicit Back-button exit must also terminate native playback.
            stopPlaybackService();
            super.onBackPressed();
        }
    }

    private void stopPlaybackService() {
        if (playbackBridge != null) {
            playbackBridge.release();
            playbackBridge = null;
        }
        stopService(new android.content.Intent(this, PlaybackService.class));
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onDestroy() {
        // Do not stop audio merely because the Activity is temporarily recreated.
        // isFinishing() identifies a real Activity exit; task-swipe cleanup is
        // independently handled by PlaybackService.onTaskRemoved().
        if (isFinishing()) {
            stopPlaybackService();
        } else if (playbackBridge != null) {
            playbackBridge.release();
            playbackBridge = null;
        }
        if (webView != null) {
            webView.stopLoading();
            webView.removeJavascriptInterface("RivuneNative");
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            if (root != null) root.removeView(webView);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
