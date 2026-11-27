package com.logicrealm.rivune;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {

    private static final String START_URL = "https://rivune.page.gd/";
    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/masterweb801/Rivune/releases/latest";
    private static final String LATEST_RELEASE_PAGE =
            "https://github.com/masterweb801/Rivune/releases/latest";
    private static final String UPDATE_PREFS = "rivune_update_check";
    private static final String PREF_LAST_CHECK = "last_successful_check";
    private static final String PREF_LATEST_VERSION = "latest_version";
    private static final String PREF_APK_URL = "latest_apk_url";
    private static final long UPDATE_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L;

    private FrameLayout root;
    private WebView webView;
    private NativePlaybackBridge playbackBridge;
    private View splashView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean updateCheckRunning;

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

        checkAppVersion();
    }

    /** Verify the native APK version before creating the WebView or exposing the website. */
    private void checkAppVersion() {
        if (updateCheckRunning) return;
        SharedPreferences prefs = getSharedPreferences(UPDATE_PREFS, MODE_PRIVATE);
        long lastCheck = prefs.getLong(PREF_LAST_CHECK, 0L);
        String cachedVersion = prefs.getString(PREF_LATEST_VERSION, null);
        String cachedApkUrl = prefs.getString(PREF_APK_URL, null);
        if (lastCheck > 0L && System.currentTimeMillis() - lastCheck < UPDATE_CHECK_INTERVAL_MS
                && cachedVersion != null && !cachedVersion.isEmpty()
                && cachedApkUrl != null && !cachedApkUrl.isEmpty()) {
            applyReleaseResult(cachedVersion, cachedApkUrl);
            return;
        }
        updateCheckRunning = true;
        showVersionGate("Checking for RIVUNE updates…", false, null);

        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                if (!hasInternetConnection()) {
                    mainHandler.post(() -> {
                        updateCheckRunning = false;
                        if (isFinishing() || isDestroyed()) return;
                        showVersionGate(
                                "OFFLINE:No internet connection. Please connect to Wi-Fi or mobile data and try again.",
                                false,
                                null
                        );
                    });
                    return;
                }

                connection = (HttpURLConnection) new URL(LATEST_RELEASE_API).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(7000);
                connection.setReadTimeout(7000);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("User-Agent", "RIVUNE-Android-App");

                int status = connection.getResponseCode();
                if (status != HttpURLConnection.HTTP_OK) {
                    throw new IllegalStateException("GitHub returned HTTP " + status);
                }

                StringBuilder response = new StringBuilder();
                try (InputStream input = connection.getInputStream();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(input))) {
                    String line;
                    while ((line = reader.readLine()) != null) response.append(line);
                }

                JSONObject release = new JSONObject(response.toString());
                String latestVersion = release.optString("tag_name", "").trim();
                String releasePage = release.optString("html_url", "").trim();
                String apkUrl = null;
                JSONArray assets = release.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.optJSONObject(i);
                        if (asset == null) continue;
                        String name = asset.optString("name", "");
                        if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) {
                            apkUrl = asset.optString("browser_download_url", null);
                            break;
                        }
                    }
                }
                if (latestVersion.isEmpty() || apkUrl == null || apkUrl.isEmpty()) {
                    throw new IllegalStateException("The latest GitHub release has no valid APK asset");
                }

                SharedPreferences releasePrefs = getSharedPreferences(UPDATE_PREFS, MODE_PRIVATE);
                releasePrefs.edit()
                        .putLong(PREF_LAST_CHECK, System.currentTimeMillis())
                        .putString(PREF_LATEST_VERSION, latestVersion)
                        .putString(PREF_APK_URL, apkUrl)
                        .apply();
                mainHandler.post(() -> {
                    updateCheckRunning = false;
                    if (isFinishing() || isDestroyed()) return;
                    applyReleaseResult(latestVersion, apkUrl);
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    updateCheckRunning = false;
                    if (isFinishing() || isDestroyed()) return;
                    showVersionGate("RIVUNE couldn't verify the latest app version. "
                            + "For your security, access is locked until the update check succeeds.\n\n"
                            + error.getMessage(), false, null);
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "RivuneVersionCheck").start();
    }

    private void applyReleaseResult(String latestVersion, String apkUrl) {
        try {
            String installedVersion = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
            boolean updateRequired = compareVersions(installedVersion, latestVersion) < 0;
            if (updateRequired) {
                showVersionGate("A required RIVUNE update is available. Update to version "
                        + latestVersion + " to continue.", true, apkUrl);
            } else {
                openRivune();
            }
        } catch (Exception error) {
            showVersionGate("Unable to check for updates right now. Please try again.", false, null);
        }
    }

    private void retryInternetConnection() {
        if (updateCheckRunning) return;
        updateCheckRunning = true;
        showVersionGate("Checking your internet connection…", false, null);
        new Thread(() -> {
            boolean connected = hasInternetConnection();
            mainHandler.post(() -> {
                updateCheckRunning = false;
                if (isFinishing() || isDestroyed()) return;
                if (connected) checkAppVersion();
                else showVersionGate(
                        "OFFLINE:No internet connection. Please connect to Wi-Fi or mobile data and try again.",
                        false, null);
            });
        }, "RivuneConnectivityCheck").start();
    }

    /** Returns true only when Android reports a validated internet connection. */
    private boolean hasInternetConnection() {
        ConnectivityManager manager =
                (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (manager == null) return false;

        Network activeNetwork = manager.getActiveNetwork();
        if (activeNetwork == null) return false;

        NetworkCapabilities capabilities = manager.getNetworkCapabilities(activeNetwork);
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private int compareVersions(String installed, String latest) {
        String left = installed == null ? "" : installed.trim().replaceFirst("^[vV]", "");
        String right = latest == null ? "" : latest.trim().replaceFirst("^[vV]", "");
        String[] a = left.split("[.+-]", -1);
        String[] b = right.split("[.+-]", -1);
        if (a.length == 0 || b.length == 0) throw new IllegalArgumentException("Invalid app version");
        int count = Math.max(a.length, b.length);
        for (int i = 0; i < count; i++) {
            String av = i < a.length ? a[i] : "0";
            String bv = i < b.length ? b[i] : "0";
            if (!av.matches("\\d+") || !bv.matches("\\d+")) {
                throw new IllegalArgumentException("Versions must use numeric components (for example 1.0.1)");
            }
            int result = Long.compare(Long.parseLong(av), Long.parseLong(bv));
            if (result != 0) return result;
        }
        return 0;
    }

    private void showVersionGate(String message, boolean updateAvailable, String apkUrl) {
        boolean offline = message != null && message.startsWith("OFFLINE:");
        if (offline) message = message.substring("OFFLINE:".length());
        root.removeAllViews();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(30), dp(30), dp(30), dp(30));

        TextView title = new TextView(this);
        title.setText(offline ? "NO INTERNET CONNECTION" : (updateAvailable ? "RIVUNE UPDATE REQUIRED" : "RIVUNE SECURITY CHECK"));
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        panel.addView(title);

        TextView details = new TextView(this);
        details.setText(message);
        details.setTextColor(getColorCompat(R.color.rivune_muted));
        details.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        details.setGravity(Gravity.CENTER);
        details.setPadding(0, dp(18), 0, dp(22));
        panel.addView(details);

        if (updateAvailable && apkUrl != null) {
            Button update = new Button(this);
            update.setText("Download latest APK");
            update.setOnClickListener(view -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(apkUrl)));
                } catch (Exception ignored) {
                    details.setText("Unable to open the download link. Please try again.");
                }
            });
            panel.addView(update);
        }

        if (!updateCheckRunning) {
            Button latestRelease = new Button(this);
            latestRelease.setText("View latest release");
            latestRelease.setOnClickListener(view -> {
                try {
                    startActivity(new Intent(
                            Intent.ACTION_VIEW,
                            android.net.Uri.parse(LATEST_RELEASE_PAGE)
                    ));
                } catch (Exception ignored) {
                    details.setText("Unable to open the GitHub release page. Please try again.");
                }
            });
            panel.addView(latestRelease);

            if (!updateAvailable) {
                Button retry = new Button(this);
                retry.setText(offline ? "Check internet connection again" : "Retry");
                retry.setOnClickListener(view -> {
                    if (offline) retryInternetConnection();
                    else checkAppVersion();
                });
                panel.addView(retry);
            }
        }

        root.addView(panel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
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
