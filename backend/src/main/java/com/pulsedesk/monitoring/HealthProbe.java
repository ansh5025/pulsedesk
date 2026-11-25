package com.pulsedesk.monitoring;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class HealthProbe {

    private final TargetValidator validator;
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public HealthProbe(TargetValidator validator) {
        this.validator = validator;
    }

    /** Never throws: any failure is reported as an unsuccessful outcome. */
    public ProbeOutcome execute(String url, int timeoutMs, int expectedStatus) {
        long start = System.nanoTime();
        try {
            URI uri = validator.validate(url);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("User-Agent", "PulseDesk-HealthCheck/1.0")
                    .GET()
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            boolean ok = response.statusCode() == expectedStatus;
            return new ProbeOutcome(ok, response.statusCode(), elapsedMs(start),
                    ok ? null : "Unexpected status " + response.statusCode() + ", expected " + expectedStatus);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure(start, "Check interrupted");
        } catch (Exception e) {
            return failure(start, e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }

    private static ProbeOutcome failure(long start, String message) {
        String trimmed = message.length() > 300 ? message.substring(0, 300) : message;
        return new ProbeOutcome(false, null, elapsedMs(start), trimmed);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
