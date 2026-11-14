package com.logicrealm.rivune;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.content.Intent;

import com.logicrealm.rivune.web.WebViewActivity;
import com.logicrealm.rivune.discovery.ServerDiscovery;
import com.logicrealm.rivune.discovery.ServerInfo;
import com.logicrealm.rivune.network.ServerVerifier;
import com.logicrealm.rivune.ui.EdgeToEdge;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

public class MainActivity extends Activity {

    private static final int VIOLET = Color.rgb(152, 117, 255);
    private static final int CARD_BACKGROUND = Color.rgb(27, 25, 41);
    private static final int TEXT = Color.rgb(247, 244, 255);
    private static final int MUTED = Color.rgb(167, 160, 184);

    private TextView statusText;
    private TextView descriptionText;
    private ProgressBar loadingIndicator;
    private LinearLayout serverList;
    private Button retryButton;

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    private boolean isScanning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        EdgeToEdge.apply(this, findViewById(R.id.rootContainer));

        statusText = findViewById(R.id.statusText);
        descriptionText = findViewById(R.id.descriptionText);
        loadingIndicator = findViewById(R.id.loadingIndicator);
        serverList = findViewById(R.id.serverList);
        retryButton = findViewById(R.id.retryButton);

        retryButton.setOnClickListener(v -> startDiscovery());

        startDiscovery();
    }

    private void startDiscovery() {

        if (isScanning || isFinishing()) return;

        isScanning = true;

        serverList.removeAllViews();

        loadingIndicator.setVisibility(View.VISIBLE);
        retryButton.setVisibility(View.GONE);

        statusText.setText("Searching your network");
        descriptionText.setText(
                "Finding RIVUNE servers on your local network."
        );

        executor.execute(() -> {

            List<ServerInfo> verifiedServers =
                    new ArrayList<>();

            try {
                ServerDiscovery discovery =
                        new ServerDiscovery(this);

                ServerVerifier verifier =
                        new ServerVerifier();

                List<ServerInfo> discovered =
                        discovery.discover();

                ExecutorService verificationPool = Executors.newFixedThreadPool(
                        Math.max(1, Math.min(4, discovered.size()))
                );
                try {
                    List<Callable<ServerInfo>> checks = new ArrayList<>();
                    for (ServerInfo server : discovered) {
                        checks.add(() -> verifier.verify(server) ? server : null);
                    }
                    List<Future<ServerInfo>> futures = verificationPool.invokeAll(checks);
                    for (Future<ServerInfo> future : futures) {
                        ServerInfo verified = future.get();
                        if (verified != null) verifiedServers.add(verified);
                    }
                } finally {
                    verificationPool.shutdownNow();
                }

            } catch (Exception error) {
                android.util.Log.e("RIVUNE-Discovery", "Discovery session failed", error);
            }

            List<ServerInfo> results = verifiedServers;

            runOnUiThread(() -> {
                isScanning = false;
                displayResults(results);
            });
        });
    }

    private void displayResults(List<ServerInfo> servers) {

        loadingIndicator.setVisibility(View.GONE);
        retryButton.setVisibility(View.VISIBLE);

        serverList.removeAllViews();

        if (servers.isEmpty()) {

            statusText.setText("No servers found");
            statusText.setTextColor(TEXT);

            descriptionText.setText(
                    "Connect this device and your server to the same Wi-Fi network, " +
                    "then make sure the RIVUNE server is running."
            );

            return;
        }

        statusText.setText(servers.size() +
                (servers.size() == 1
                        ? " server found"
                        : " servers found"));

        statusText.setTextColor(VIOLET);

        descriptionText.setText(
                "Choose a server to open your music library."
        );

        for (ServerInfo server : servers) {
            serverList.addView(createServerCard(server));
        }
    }

    private View createServerCard(ServerInfo server) {

        LinearLayout card = new LinearLayout(this);

        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));

        GradientDrawable background =
                new GradientDrawable();

        background.setColor(CARD_BACKGROUND);
        background.setCornerRadius(dp(18));
        background.setStroke(dp(1), Color.rgb(56, 48, 79));

        card.setBackground(background);
        card.setClickable(true);
        card.setFocusable(true);

        LinearLayout.LayoutParams cardParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );

        cardParams.bottomMargin = dp(12);
        card.setLayoutParams(cardParams);

        TextView name = new TextView(this);
        name.setText("RIVUNE Music Server");
        name.setTextColor(TEXT);
        name.setTextSize(16);
        name.setTypeface(null, Typeface.BOLD);

        TextView address = new TextView(this);
        address.setText(server.getDisplayName());
        address.setTextColor(VIOLET);
        address.setTextSize(14);
        address.setTypeface(Typeface.MONOSPACE);
        address.setPadding(0, dp(8), 0, 0);

        TextView version = new TextView(this);
        version.setText("LOCAL NETWORK  ·  PROTOCOL " + server.getVersion());
        version.setTextColor(MUTED);
        version.setTextSize(12);
        version.setPadding(0, dp(8), 0, 0);

        card.addView(name);
        card.addView(address);
        card.addView(version);

        card.setOnClickListener(v -> {
            Intent intent = new Intent(
                MainActivity.this,
                WebViewActivity.class
            );

            intent.putExtra(
                WebViewActivity.EXTRA_SERVER_URL,
                server.getBaseUrl()
            );

            startActivity(intent);
        });

        return card;
    }

    private int dp(float value) {
        return (int) (value *
                getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
