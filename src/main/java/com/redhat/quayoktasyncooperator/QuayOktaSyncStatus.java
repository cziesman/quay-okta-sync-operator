package com.redhat.quayoktasyncooperator;

public class QuayOktaSyncStatus {

    private String phase;

    private String message;

    private String lastSyncTime;

    public String getPhase() {

        return phase;
    }

    public void setPhase(String phase) {

        this.phase = phase;
    }

    public String getMessage() {

        return message;
    }

    public void setMessage(String message) {

        this.message = message;
    }

    public String getLastSyncTime() {

        return lastSyncTime;
    }

    public void setLastSyncTime(String lastSyncTime) {

        this.lastSyncTime = lastSyncTime;
    }

}
