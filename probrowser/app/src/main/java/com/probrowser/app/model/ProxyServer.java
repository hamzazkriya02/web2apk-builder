package com.probrowser.app.model;

public class ProxyServer {

    public String id = "";
    public String name = "";
    public String country_code = "";
    public String scheme = "http";
    public String host = "";
    public int port = 8080;
    public String username = "";
    public String password = "";
    public boolean enabled = true;

    public ProxyServer() {
    }

    public String proxyRule() {
        return scheme + "://" + host + ":" + port;
    }

    public boolean hasCredentials() {
        return username != null
                && !username.trim().isEmpty()
                && password != null
                && !password.isEmpty();
    }

    @Override
    public String toString() {
        return name + " (" + country_code + ")";
    }
}
