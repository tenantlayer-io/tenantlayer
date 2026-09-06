package io.tenantlayer.migration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What happened when migrations ran across tenants. */
public record MigrationOutcome(
        Map<String, Integer> migrated,
        Map<String, Throwable> failures) {

    public MigrationOutcome {
        // Not Map.copyOf: its iteration order is unspecified, and an operator reading
        // "these tenants failed" needs the same order every run.
        migrated = Collections.unmodifiableMap(new LinkedHashMap<>(migrated));
        failures = Collections.unmodifiableMap(new LinkedHashMap<>(failures));
    }

    public boolean isSuccessful() {
        return failures.isEmpty();
    }

    public List<String> failedTenants() {
        return List.copyOf(failures.keySet());
    }

    public int totalMigrationsApplied() {
        return migrated.values().stream().mapToInt(Integer::intValue).sum();
    }
}
