package com.pulsedesk.monitoring;

public record ProbeOutcome(boolean success, Integer statusCode, long responseTimeMs, String error) {
}
