package com.pulsedesk.user;

import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class UsernameGenerator {

    private final UserRepository users;

    public UsernameGenerator(UserRepository users) {
        this.users = users;
    }

    /** Builds firstname@orgslug, appending a number on collision. */
    public String generate(String fullName, String slug) {
        String base = fullName.trim().split("\\s+")[0].toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (base.isEmpty()) {
            base = "user";
        }
        String candidate = base + "@" + slug;
        for (int i = 2; users.existsByUsername(candidate); i++) {
            candidate = base + i + "@" + slug;
        }
        return candidate;
    }
}
