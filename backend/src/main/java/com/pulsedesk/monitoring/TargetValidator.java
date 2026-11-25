package com.pulsedesk.monitoring;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import org.springframework.stereotype.Component;

/** Rejects health-check targets that would let a tenant probe the platform's own network (SSRF). */
@Component
public class TargetValidator {

    private final MonitoringProperties props;

    public TargetValidator(MonitoringProperties props) {
        this.props = props;
    }

    /** @throws IllegalArgumentException when the target is malformed or not allowed */
    public URI validate(String url) {
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("URL is not valid");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("Only http and https URLs are allowed");
        }
        if (uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("URL must have a host and no embedded credentials");
        }
        if (!props.allowPrivateTargets()) {
            try {
                for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                    if (isInternal(address)) {
                        throw new IllegalArgumentException("URL points to a private or internal address");
                    }
                }
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("Host cannot be resolved");
            }
        }
        return uri;
    }

    private static boolean isInternal(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            return first == 0 || (first == 100 && second >= 64 && second <= 127);
        }
        return (b[0] & 0xfe) == 0xfc;
    }
}
