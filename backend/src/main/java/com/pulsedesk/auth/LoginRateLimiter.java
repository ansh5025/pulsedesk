package com.pulsedesk.auth;

import com.pulsedesk.common.exception.ApiException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Counts failed logins per account and per client IP in a fixed window. Fails open if Redis is down so an
 * outage cannot lock everyone out. Locking per account lets an attacker temporarily block a known username;
 * that trade-off is accepted in exchange for stopping brute force against it.
 */
@Component
public class LoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);

    private final StringRedisTemplate redis;
    private final RateLimitProperties props;

    public LoginRateLimiter(StringRedisTemplate redis, RateLimitProperties props) {
        this.redis = redis;
        this.props = props;
    }

    public void checkAllowed(String account, String ip) {
        try {
            if (count(accountKey(account)) >= props.loginMaxAttempts()
                    || count(ipKey(ip)) >= props.loginMaxAttemptsPerIp()) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                        "Too many failed login attempts. Try again later.");
            }
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Login rate limit check skipped: {}", e.getMessage());
        }
    }

    public void recordFailure(String account, String ip) {
        try {
            increment(accountKey(account));
            increment(ipKey(ip));
        } catch (RuntimeException e) {
            log.warn("Login failure not recorded: {}", e.getMessage());
        }
    }

    public void reset(String account) {
        try {
            redis.delete(accountKey(account));
        } catch (RuntimeException e) {
            log.warn("Login counter not reset: {}", e.getMessage());
        }
    }

    private long count(String key) {
        String value = redis.opsForValue().get(key);
        return value == null ? 0 : Long.parseLong(value);
    }

    private void increment(String key) {
        Long n = redis.opsForValue().increment(key);
        if (n != null && n == 1L) {
            redis.expire(key, Duration.ofSeconds(props.loginWindowSeconds()));
        }
    }

    private static String accountKey(String account) {
        return "pulsedesk:login-fail:acct:" + account;
    }

    private static String ipKey(String ip) {
        return "pulsedesk:login-fail:ip:" + ip;
    }
}
