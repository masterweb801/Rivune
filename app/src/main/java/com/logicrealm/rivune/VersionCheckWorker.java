package com.logicrealm.rivune;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

public class VersionCheckWorker extends Worker {
    private static final String API = "https://api.github.com/repos/masterweb801/Rivune/releases/latest";
    private static final String PREFS = "rivune_update_check";
    private static final String ACTION = "com.logicrealm.rivune.UPDATE_AVAILABLE";

    public VersionCheckWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection)new URL(API).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "RIVUNE-Android-App");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return Result.retry();

            StringBuilder body = new StringBuilder();
            try (InputStream in = connection.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }
            JSONObject release = new JSONObject(body.toString());
            String latest = release.optString("tag_name", "").trim();
            String apk = null;
            JSONArray assets = release.optJSONArray("assets");
            if (assets != null) for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.optJSONObject(i);
                if (asset != null && asset.optString("name", "").toLowerCase(Locale.ROOT).endsWith(".apk")) {
                    apk = asset.optString("browser_download_url", null);
                    break;
                }
            }
            if (latest.isEmpty() || apk == null || apk.isEmpty()) return Result.retry();

            Context context = getApplicationContext();
            String installed = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
            boolean required = compareVersions(installed, latest) < 0;

            SharedPreferences.Editor edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putLong("last_successful_check", System.currentTimeMillis())
                    .putString("latest_version", latest).putString("latest_apk_url", apk)
                    .putBoolean("update_required", required);
            if (required) edit.putString("required_version", latest).putString("required_apk_url", apk);
            else edit.remove("required_version").remove("required_apk_url");
            edit.apply();

            if (required) {
                Intent intent = new Intent(ACTION);
                intent.setPackage(context.getPackageName());
                context.sendBroadcast(intent);
            }
            return Result.success();
        } catch (Exception error) {
            return Result.retry();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private int compareVersions(String installed, String latest) {
        String[] a = (installed == null ? "" : installed.trim().replaceFirst("^[vV]", "")).split("[.+-]", -1);
        String[] b = (latest == null ? "" : latest.trim().replaceFirst("^[vV]", "")).split("[.+-]", -1);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            String av = i < a.length ? a[i] : "0";
            String bv = i < b.length ? b[i] : "0";
            if (!av.matches("\\d+") || !bv.matches("\\d+"))
                throw new IllegalArgumentException("Release versions must be numeric");
            int result = Long.compare(Long.parseLong(av), Long.parseLong(bv));
            if (result != 0) return result;
        }
        return 0;
    }
}
