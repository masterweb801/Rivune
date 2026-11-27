package com.logicrealm.rivune;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
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

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

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
    private static final String PREF_UPDATE_REQUIRED = "update_required";
    private static final String PREF_REQUIRED_VERSION = "required_version";
    private static final String PREF_REQUIRED_APK_URL = "required_apk_url";
    private static final String PREF_INITIAL_WORK_SCHEDULED = "initial_work_scheduled";
    private static final String ACTION_UPDATE_AVAILABLE = "com.logicrealm.rivune.UPDATE_AVAILABLE";
    private static final long UPDATE_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L;

    private FrameLayout root;
    private WebView webView;
    private NativePlaybackBridge playbackBridge;
    private View splashView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean updateCheckRunning;
    private boolean updateReceiverRegistered;
    private boolean startupSoundPlayed;
    private boolean startupSoundFinished = true;
    private boolean splashPageLoaded;
    private MediaPlayer startupPlayer;
    private View offlineView;
    private final BroadcastReceiver updateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (ACTION_UPDATE_AVAILABLE.equals(intent.getAction())) showSavedMandatoryUpdate();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        root = new FrameLayout(this);
        root.setBackgroundColor(getColorCompat(R.color.rivune_midnight));
        root.setClipToPadding(false);
        setContentView(root);
        applySafeAreaInsets();

        scheduleVersionChecks();
        if (!showSavedMandatoryUpdate()) openRivune();
    }

    /** Start an immediate first check and then a network-constrained daily check. */
    private void scheduleVersionChecks() {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build();
        PeriodicWorkRequest daily = new PeriodicWorkRequest.Builder(
                VersionCheckWorker.class, 24, TimeUnit.HOURS)
                .setConstraints(constraints).build();
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "rivune-daily-version-check", ExistingPeriodicWorkPolicy.KEEP, daily);
        SharedPreferences prefs = getSharedPreferences(UPDATE_PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean(PREF_INITIAL_WORK_SCHEDULED, false)) {
            OneTimeWorkRequest first = new OneTimeWorkRequest.Builder(VersionCheckWorker.class)
                    .setConstraints(constraints).build();
            WorkManager.getInstance(this).enqueueUniqueWork(
                    "rivune-first-version-check", ExistingWorkPolicy.KEEP, first);
            prefs.edit().putBoolean(PREF_INITIAL_WORK_SCHEDULED, true).apply();
        }
    }

    /** A detected mandatory update remains enforced across app restarts. */
    private boolean showSavedMandatoryUpdate() {
        SharedPreferences prefs = getSharedPreferences(UPDATE_PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean(PREF_UPDATE_REQUIRED, false)) return false;
        String requiredVersion = prefs.getString(PREF_REQUIRED_VERSION, "");
        String apkUrl = prefs.getString(PREF_REQUIRED_APK_URL, "");
        try {
            String installed = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            if (requiredVersion == null || requiredVersion.isEmpty()
                    || compareVersions(installed, requiredVersion) >= 0) {
                prefs.edit().putBoolean(PREF_UPDATE_REQUIRED, false)
                        .remove(PREF_REQUIRED_VERSION).remove(PREF_REQUIRED_APK_URL).apply();
                return false;
            }
            showVersionGate("A required RIVUNE update is available. Update to version "
                    + requiredVersion + " to continue.", true, apkUrl);
            return true;
        } catch (Exception error) {
            showVersionGate("A required RIVUNE update is available. Please update to continue.",
                    true, apkUrl);
            return true;
        }
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(ACTION_UPDATE_AVAILABLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(updateReceiver, filter);
        updateReceiverRegistered = true;
    }

    @Override protected void onStop() {
        if (updateReceiverRegistered) {
            unregisterReceiver(updateReceiver);
            updateReceiverRegistered = false;
        }
        super.onStop();
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
            if (!offline) {
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
            }

            if (!updateAvailable) {
                Button retry = new Button(this);
                retry.setText("Retry");
                retry.setOnClickListener(view -> {
                    scheduleVersionChecks();
                    details.setText("RIVUNE will check for updates in the background when internet is available.");
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

    /** Keep all WebView content inside the status-bar, cutout and navigation safe areas. */
    private void applySafeAreaInsets() {
        root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            int left;
            int top;
            int right;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets safe = windowInsets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = safe.left;
                top = safe.top;
                right = safe.right;
                bottom = safe.bottom;
            } else {
                left = windowInsets.getSystemWindowInsetLeft();
                top = windowInsets.getSystemWindowInsetTop();
                right = windowInsets.getSystemWindowInsetRight();
                bottom = windowInsets.getSystemWindowInsetBottom();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    android.view.DisplayCutout cutout = windowInsets.getDisplayCutout();
                    if (cutout != null) {
                        left = Math.max(left, cutout.getSafeInsetLeft());
                        top = Math.max(top, cutout.getSafeInsetTop());
                        right = Math.max(right, cutout.getSafeInsetRight());
                        bottom = Math.max(bottom, cutout.getSafeInsetBottom());
                    }
                }
            }
            view.setPadding(left, top, right, bottom);
            return windowInsets;
        });
        root.requestApplyInsets();
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
        rivuneLogo.setAlpha(0f);
        rivuneLogo.setScaleX(0.72f); rivuneLogo.setScaleY(0.72f);
        rivuneLogo.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(950)
                .setInterpolator(new android.view.animation.OvershootInterpolator(0.7f)).start();

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
        rivuneName.setAlpha(0f); rivuneName.setTranslationY(dp(16));
        rivuneName.animate().alpha(1f).translationY(0f).setStartDelay(320).setDuration(650)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
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
        footer.setAlpha(0f); footer.setTranslationY(dp(12));
        footer.animate().alpha(1f).translationY(0f).setStartDelay(620).setDuration(700)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();

        FrameLayout.LayoutParams footerParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL
        );
        footerParams.bottomMargin = dp(34);
        splash.addView(footer, footerParams);
        splashPageLoaded = false;
        startupSoundFinished = false;
        playStartupSound();
        return splash;
    }

    /** Play the splash signature and keep the splash visible until the sound finishes. */
    private void playStartupSound() {
        if (startupSoundPlayed || isFinishing()) {
            startupSoundFinished = true;
            return;
        }
        startupSoundPlayed = true;
        try {
            startupPlayer = MediaPlayer.create(this, R.raw.rivune_startup);
            if (startupPlayer == null) {
                startupSoundFinished = true;
                hideSplashScreen();
                return;
            }
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            startupPlayer.setAudioAttributes(attributes);
            startupPlayer.setVolume(0.82f, 0.82f);
            startupPlayer.setOnCompletionListener(mp -> {
                mp.release();
                if (startupPlayer == mp) startupPlayer = null;
                startupSoundFinished = true;
                hideSplashScreen();
            });
            startupPlayer.setOnErrorListener((mp, what, extra) -> {
                mp.release();
                if (startupPlayer == mp) startupPlayer = null;
                startupSoundFinished = true;
                hideSplashScreen();
                return true;
            });
            startupPlayer.start();
        } catch (RuntimeException ignored) {
            startupSoundFinished = true;
            hideSplashScreen();
        }
    }

    private void hideSplashScreen() {
        if (!splashPageLoaded || !startupSoundFinished || splashView == null) return;
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
                    if (isTrustedUrl(url)) {
                        splashPageLoaded = true;
                        hideSplashScreen();
                    }
                }

                @Override
                public void onReceivedError(WebView view, WebResourceRequest request,
                        android.webkit.WebResourceError error) {
                    super.onReceivedError(view, request, error);
                    if (request != null && request.isForMainFrame()) {
                        showOfflineScreen();
                    }
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
            if (!isNetworkAvailable()) {
                showOfflineScreen();
            } else {
                webView.loadUrl(START_URL);
            }
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

    private boolean isNetworkAvailable() {
        ConnectivityManager manager =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return false;
        Network active = manager.getActiveNetwork();
        if (active == null) return false;
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(active);
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private void showOfflineScreen() {
        if (root == null || isFinishing()) return;
        if (splashView != null) {
            root.removeView(splashView);
            splashView = null;
        }
        if (webView != null) webView.setVisibility(View.GONE);
        if (offlineView != null) root.removeView(offlineView);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(30), dp(30), dp(30), dp(30));
        panel.setBackgroundColor(getColorCompat(R.color.rivune_midnight));

        TextView title = new TextView(this);
        title.setText("No Internet Connection");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        panel.addView(title);

        Button retry = new Button(this);
        retry.setText("Retry");
        retry.setAllCaps(false);
        retry.setOnClickListener(v -> {
            if (!isNetworkAvailable()) {
                return;
            }
            root.removeView(panel);
            offlineView = null;
            if (webView != null) {
                webView.setVisibility(View.VISIBLE);
                webView.loadUrl(START_URL);
            } else {
                openRivune();
            }
        });
        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        retryParams.topMargin = dp(22);
        panel.addView(retry, retryParams);
        offlineView = panel;
        root.addView(panel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
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
        showSavedMandatoryUpdate();
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
        if (startupPlayer != null) {
            startupPlayer.setOnCompletionListener(null);
            startupPlayer.setOnErrorListener(null);
            startupPlayer.release();
            startupPlayer = null;
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
