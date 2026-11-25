package com.pulsedesk.common.security;

import com.pulsedesk.membership.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private final JwtProperties props;
    private final SecretKey key;

    public JwtService(JwtProperties props) {
        byte[] secret = props.secret() == null ? new byte[0] : props.secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("app.jwt.secret (PULSEDESK_JWT_SECRET) must be at least 32 bytes");
        }
        this.props = props;
        this.key = Keys.hmacShaKeyFor(secret);
    }

    public String generate(AuthenticatedUser user) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(user.userId().toString())
                .issuer(props.issuer())
                .claim("orgId", user.organizationId().toString())
                .claim("membershipId", user.membershipId().toString())
                .claim("role", user.role().name())
                .claim("username", user.username())
                .issuedAt(new Date(now))
                .expiration(new Date(now + props.expirationMs()))
                .signWith(key)
                .compact();
    }

    public long expirationSeconds() {
        return props.expirationMs() / 1000;
    }

    public Optional<AuthenticatedUser> parse(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).requireIssuer(props.issuer()).build()
                    .parseSignedClaims(token).getPayload();
            return Optional.of(new AuthenticatedUser(
                    UUID.fromString(c.getSubject()),
                    UUID.fromString(c.get("orgId", String.class)),
                    UUID.fromString(c.get("membershipId", String.class)),
                    Role.valueOf(c.get("role", String.class)),
                    c.get("username", String.class)));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
