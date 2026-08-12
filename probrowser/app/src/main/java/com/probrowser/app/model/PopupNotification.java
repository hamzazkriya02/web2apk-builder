package com.probrowser.app.model;

public class PopupNotification {
    public boolean active = false;
    public String popup_id = "";
    public String title = "";
    public String description = "";

    // New two-button popup fields.
    public String button1_name = "";
    public String button1_link = "";
    public String button2_name = "";
    public String button2_link = "";

    // Legacy fields kept so old Firebase popup records still work.
    public String button_name = "";
    public String button_link = "";

    public int duration_seconds = 5;

    public PopupNotification() {
    }
}
