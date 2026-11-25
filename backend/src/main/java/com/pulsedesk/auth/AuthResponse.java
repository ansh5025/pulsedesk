package com.pulsedesk.auth;

public record AuthResponse(String accessToken, String tokenType, long expiresInSeconds, String username, String role) {
}
