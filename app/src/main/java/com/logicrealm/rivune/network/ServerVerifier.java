
package com.logicrealm.rivune.network;

import com.logicrealm.rivune.discovery.ServerInfo;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import android.util.Log;

public class ServerVerifier {

    public boolean verify(ServerInfo server) {
        final String tag = "RIVUNE-Discovery";

        HttpURLConnection connection = null;

        try {
            URL url = new URL(
                    server.getBaseUrl() + "/api/health"
            );

            connection = (HttpURLConnection) url.openConnection();

            connection.setRequestMethod("GET");
            connection.setConnectTimeout(2500);
            connection.setReadTimeout(2500);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);

            int status = connection.getResponseCode();

            if (status != HttpURLConnection.HTTP_OK) {
                Log.w(tag, "Health check failed for " + server.getDisplayName() + " (HTTP " + status + ")");
                return false;
            }

            try (InputStream input = connection.getInputStream();
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(
                                 input,
                                 StandardCharsets.UTF_8
                         )
                 )) {

                StringBuilder response = new StringBuilder();
                String line;

                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }

                JSONObject json = new JSONObject(
                        response.toString()
                );

                if (json.has("service")) {
                    return "mobs-jukebox".equals(
                            json.optString("service")
                    );
                }

                return true;
            }

        } catch (Exception error) {
            Log.w(tag, "Could not verify " + server.getDisplayName(), error);
            return false;

        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
