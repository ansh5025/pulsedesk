package com.pulsedesk.incident;

import java.util.EnumSet;
import java.util.Set;

public enum IncidentStatus {
    OPEN, ACKNOWLEDGED, IN_PROGRESS, RESOLVED, CLOSED;

    public Set<IncidentStatus> allowedNext() {
        return switch (this) {
            case OPEN -> EnumSet.of(ACKNOWLEDGED, IN_PROGRESS, RESOLVED);
            case ACKNOWLEDGED -> EnumSet.of(IN_PROGRESS, RESOLVED);
            case IN_PROGRESS -> EnumSet.of(RESOLVED);
            case RESOLVED -> EnumSet.of(IN_PROGRESS, CLOSED);
            case CLOSED -> EnumSet.noneOf(IncidentStatus.class);
        };
    }

    public boolean isActive() {
        return this != RESOLVED && this != CLOSED;
    }
}
