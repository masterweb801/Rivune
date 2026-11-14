
package com.logicrealm.rivune.discovery;

public class ServerInfo {

    private final String ipAddress;
    private final int port;
    private final int version;

    public ServerInfo(String ipAddress, int port, int version) {
        this.ipAddress = ipAddress;
        this.port = port;
        this.version = version;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public int getPort() {
        return port;
    }

    public int getVersion() {
        return version;
    }

    public String getBaseUrl() {
        return "http://" + ipAddress + ":" + port;
    }

    public String getDisplayName() {
        return ipAddress + ":" + port;
    }
}
