package com.probrowser.app.model;

public class BrowserConfig {
    public String header_name = "Professional Browser";
    public String website_url = "https://example.com";
    public boolean proxy_feature_enabled = true;
    public boolean default_proxy_enabled = false;
    public String default_proxy_id = "";
    public boolean app_expiry_enabled = false;
    public long app_expires_at = 0L; // Unix seconds

    public BrowserConfig() {
    }
}
