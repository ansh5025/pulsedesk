package com.pulsedesk.incident;

public enum IncidentPriority {
    LOW(480, 4320), MEDIUM(240, 1440), HIGH(60, 480), CRITICAL(15, 240);

    private final int responseMinutes;
    private final int resolutionMinutes;

    IncidentPriority(int responseMinutes, int resolutionMinutes) {
        this.responseMinutes = responseMinutes;
        this.resolutionMinutes = resolutionMinutes;
    }

    public int responseMinutes() {
        return responseMinutes;
    }

    public int resolutionMinutes() {
        return resolutionMinutes;
    }
}
