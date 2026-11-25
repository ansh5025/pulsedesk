package com.pulsedesk.messaging;

public record EmailMessage(String to, String subject, String body) {
}
