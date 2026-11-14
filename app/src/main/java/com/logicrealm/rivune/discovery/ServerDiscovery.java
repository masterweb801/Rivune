package com.logicrealm.rivune.discovery;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.net.DhcpInfo;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import android.util.Log;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ServerDiscovery {

    private static final int DISCOVERY_PORT = 39876;
    private static final String REQUEST =
            "MOBS_JUKEBOX_DISCOVER_V1";

    private static final long DISCOVERY_DURATION = 7000;
    private static final long BROADCAST_INTERVAL = 750;
    private static final String TAG = "RIVUNE-Discovery";

    private final Context context;

    public ServerDiscovery(Context context) {
        this.context = context.getApplicationContext();
    }

    public List<ServerInfo> discover() throws Exception {

        Map<String, ServerInfo> servers = new LinkedHashMap<>();
        byte[] requestData = REQUEST.getBytes(
                java.nio.charset.StandardCharsets.UTF_8
        );

        InetAddress directedBroadcast = getWifiBroadcastAddress();

        try (DatagramSocket socket = new DatagramSocket(null)) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(0));
            socket.setBroadcast(true);
            socket.setSoTimeout(250);

            long startedAt = System.currentTimeMillis();
            long deadline = startedAt + DISCOVERY_DURATION;
            long nextBroadcastAt = 0;
            byte[] buffer = new byte[2048];

            while (System.currentTimeMillis() < deadline) {
                long now = System.currentTimeMillis();

                // Re-announce throughout the scan. A server may have missed
                // an earlier packet or become reachable after Wi-Fi settles.
                if (now >= nextBroadcastAt) {
                    try {
                        sendBroadcast(socket, requestData,
                                InetAddress.getByName("255.255.255.255"));
                        if (directedBroadcast != null &&
                                !directedBroadcast.getHostAddress()
                                        .equals("255.255.255.255")) {
                            sendBroadcast(socket, requestData, directedBroadcast);
                        }
                    } catch (Exception error) {
                        Log.w(TAG, "Could not send discovery broadcast", error);
                    }
                    nextBroadcastAt = now + BROADCAST_INTERVAL;
                }

                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                    String response = new String(
                            packet.getData(), packet.getOffset(), packet.getLength(),
                            java.nio.charset.StandardCharsets.UTF_8
                    );
                    JSONObject json = new JSONObject(response);

                    if (!"mobs-jukebox".equals(json.optString("service"))) {
                        Log.d(TAG, "Ignored UDP response from " +
                                packet.getAddress().getHostAddress() + ": invalid service");
                        continue;
                    }

                    int port = json.optInt("port", -1);
                    int version = json.optInt("version", 0);
                    if (port < 1 || port > 65535) {
                        Log.w(TAG, "Ignored discovery response with invalid HTTP port");
                        continue;
                    }

                    String ip = packet.getAddress().getHostAddress();
                    ServerInfo server = new ServerInfo(ip, port, version);
                    servers.put(server.getDisplayName(), server);
                    Log.i(TAG, "Discovered server at " + server.getDisplayName());

                } catch (SocketTimeoutException ignored) {
                    // Continue sending and listening until the scan deadline.
                } catch (org.json.JSONException error) {
                    Log.w(TAG, "Received malformed UDP discovery response", error);
                }
            }
        }

        Log.i(TAG, "Discovery finished; received " + servers.size() + " server(s)");
        return new ArrayList<>(servers.values());
    }

    private void sendBroadcast(
            DatagramSocket socket,
            byte[] data,
            InetAddress address
    ) throws Exception {

        DatagramPacket packet = new DatagramPacket(
                data,
                data.length,
                address,
                DISCOVERY_PORT
        );

        socket.send(packet);
    }

    private InetAddress getWifiBroadcastAddress() {

        try {
            WifiManager wifiManager =
                    (WifiManager) context.getSystemService(
                            Context.WIFI_SERVICE
                    );

            if (wifiManager == null) return null;

            DhcpInfo info =
                    wifiManager.getDhcpInfo();

            if (info == null || info.ipAddress == 0 ||
                    info.netmask == 0) {
                return null;
            }

            int broadcast =
                    (info.ipAddress & info.netmask)
                    | ~info.netmask;

            byte[] bytes = new byte[] {
                    (byte) (broadcast & 0xff),
                    (byte) ((broadcast >> 8) & 0xff),
                    (byte) ((broadcast >> 16) & 0xff),
                    (byte) ((broadcast >> 24) & 0xff)
            };

            return InetAddress.getByAddress(bytes);

        } catch (Exception ignored) {
            return null;
        }
    }
}
