package io.tenantlayer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tenantlayer.registry.MetadataKey;
import io.tenantlayer.registry.TenantMetadataException;
import io.tenantlayer.registry.TenantRegistration;
import io.tenantlayer.registry.TenantRegistryException;
import io.tenantlayer.registry.TenantStatus;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Feature 55 — typed accessors over the registry's untyped metadata map.
 *
 * <p>Every rejection here is paired with an acceptance of a neighbouring valid value, so a
 * test cannot pass merely because the implementation rejects (or returns empty for)
 * everything.
 */
class TenantMetadataTest {

    private static final MetadataKey<Integer> SEATS = MetadataKey.ofInt("seats");
    private static final MetadataKey<Boolean> SSO = MetadataKey.ofBoolean("sso_required");

    private enum Plan { STARTER, PRO }

    private static TenantRegistration tenant(Map<String, String> metadata) {
        return new TenantRegistration("acme", TenantStatus.ACTIVE, null, null, null, metadata);
    }

    // ---- absent versus present ----

    @Test
    @DisplayName("an absent key is empty, and a present one is not")
    void absentIsEmptyPresentIsNot() {
        TenantRegistration t = tenant(Map.of("seats", "25"));

        assertThat(t.metadata(MetadataKey.ofInt("missing"))).isEmpty();
        assertThat(t.metadata(SEATS)).contains(25);
    }

    // ---- integers and longs ----

    @Test
    @DisplayName("an integer parses; one that does not is an error, not a default")
    void integerStrict() {
        assertThat(tenant(Map.of("seats", "-3")).metadata(SEATS)).contains(-3);

        assertThatThrownBy(() -> tenant(Map.of("seats", "twelve")).metadata(SEATS))
                .isInstanceOf(TenantMetadataException.class);
        assertThatThrownBy(() -> tenant(Map.of("seats", "")).metadata(SEATS))
                .isInstanceOf(TenantMetadataException.class);
        // not trimmed: " 5" is not 5
        assertThatThrownBy(() -> tenant(Map.of("seats", " 5")).metadata(SEATS))
                .isInstanceOf(TenantMetadataException.class);
    }

    @Test
    @DisplayName("a long accepts a value an int cannot hold")
    void longRange() {
        String big = "3000000000";
        assertThat(tenant(Map.of("n", big)).metadata(MetadataKey.ofLong("n"))).contains(3_000_000_000L);
        assertThatThrownBy(() -> tenant(Map.of("n", big)).metadata(MetadataKey.ofInt("n")))
                .isInstanceOf(TenantMetadataException.class);
    }

    // ---- booleans ----

    @Test
    @DisplayName("a boolean is exactly true or false; 'yes' is rejected, not read as false")
    void booleanStrict() {
        assertThat(tenant(Map.of("sso_required", "true")).metadata(SSO)).contains(true);
        assertThat(tenant(Map.of("sso_required", "false")).metadata(SSO)).contains(false);

        // Boolean.parseBoolean would answer false for each of these, silently.
        for (String bad : new String[] {"yes", "TRUE", "1", ""}) {
            assertThatThrownBy(() -> tenant(Map.of("sso_required", bad)).metadata(SSO))
                    .as("value '%s'", bad)
                    .isInstanceOf(TenantMetadataException.class);
        }
    }

    // ---- durations ----

    @Test
    @DisplayName("a duration is ISO-8601")
    void durationIso() {
        MetadataKey<Duration> ttl = MetadataKey.ofDuration("ttl");

        assertThat(tenant(Map.of("ttl", "PT30S")).metadata(ttl)).contains(Duration.ofSeconds(30));
        assertThatThrownBy(() -> tenant(Map.of("ttl", "30s")).metadata(ttl))
                .isInstanceOf(TenantMetadataException.class);
    }

    // ---- enums ----

    @Test
    @DisplayName("an enum matches its exact constant name")
    void enumExact() {
        MetadataKey<Plan> plan = MetadataKey.ofEnum("plan", Plan.class);

        assertThat(tenant(Map.of("plan", "PRO")).metadata(plan)).contains(Plan.PRO);
        assertThatThrownBy(() -> tenant(Map.of("plan", "pro")).metadata(plan))
                .isInstanceOf(TenantMetadataException.class);
        assertThatThrownBy(() -> tenant(Map.of("plan", "ENTERPRISE")).metadata(plan))
                .isInstanceOf(TenantMetadataException.class);
    }

    // ---- strings ----

    @Test
    @DisplayName("a string key returns the stored value as it is, empty included")
    void stringAsStored() {
        MetadataKey<String> note = MetadataKey.ofString("note");

        assertThat(tenant(Map.of("note", "hello")).metadata(note)).contains("hello");
        assertThat(tenant(Map.of("note", "")).metadata(note)).contains("");
    }

    // ---- validators ----

    @Test
    @DisplayName("a requirement rejects a value that parses but is not acceptable")
    void requirementApplied() {
        MetadataKey<Integer> positive = MetadataKey.ofInt("seats").require(n -> n > 0, "a positive number");

        assertThat(tenant(Map.of("seats", "1")).metadata(positive)).contains(1);
        assertThatThrownBy(() -> tenant(Map.of("seats", "0")).metadata(positive))
                .isInstanceOf(TenantMetadataException.class);
        assertThatThrownBy(() -> tenant(Map.of("seats", "-1")).metadata(positive))
                .isInstanceOf(TenantMetadataException.class);
    }

    @Test
    @DisplayName("a custom parser returning null is an error, not an empty value")
    void nullFromParserRejected() {
        MetadataKey<String> bad = MetadataKey.of("x", "anything", raw -> null);

        assertThatThrownBy(() -> tenant(Map.of("x", "v")).metadata(bad))
                .isInstanceOf(TenantMetadataException.class);
        assertThat(tenant(Map.of("x", "v")).metadata(MetadataKey.of("x", "anything", raw -> raw)))
                .contains("v");
    }

    // ---- required ----

    @Test
    @DisplayName("requireMetadata returns a present value and throws for an absent one")
    void requireMetadata() {
        assertThat(tenant(Map.of("seats", "8")).requireMetadata(SEATS)).isEqualTo(8);

        assertThatThrownBy(() -> tenant(Map.of()).requireMetadata(SEATS))
                .isInstanceOf(TenantMetadataException.class)
                .hasMessageContaining("acme")
                .hasMessageContaining("seats");
        assertThatThrownBy(() -> tenant(Map.of("seats", "x")).requireMetadata(SEATS))
                .isInstanceOf(TenantMetadataException.class);
    }

    // ---- validateMetadata ----

    @Test
    @DisplayName("validateMetadata returns the registration, skips absent keys, and rejects an invalid one")
    void validateMetadata() {
        TenantRegistration ok = tenant(Map.of("seats", "5"));

        assertThat(ok.validateMetadata(SEATS, SSO)).isSameAs(ok); // SSO is absent: fine
        assertThatThrownBy(() -> tenant(Map.of("seats", "5", "sso_required", "yes"))
                .validateMetadata(SEATS, SSO))
                .isInstanceOf(TenantMetadataException.class)
                .hasMessageContaining("sso_required");
    }

    // ---- the value never reaches the exception ----

    @Test
    @DisplayName("a rejected value is not quoted in the message or the cause chain")
    void valueNeverLeaks() {
        String secret = "hunter2-not-a-number";

        assertThatThrownBy(() -> tenant(Map.of("seats", secret)).metadata(SEATS))
                .isInstanceOf(TenantMetadataException.class)
                .hasMessageNotContaining(secret)
                .hasNoCause();
        // and the same key is fine when the value is fine, so the test is not vacuous
        assertThatCode(() -> tenant(Map.of("seats", "7")).metadata(SEATS)).doesNotThrowAnyException();
    }

    // ---- compatibility ----

    @Test
    @DisplayName("the untyped map is unchanged, and typed reads do not alter it")
    void untypedMapStillWorks() {
        Map<String, String> raw = Map.of("seats", "5", "free", "text");
        TenantRegistration t = tenant(raw);

        t.metadata(SEATS);

        assertThat(t.metadata()).isEqualTo(raw);
        assertThat(t.metadata().get("free")).isEqualTo("text");
    }

    @Test
    @DisplayName("TenantMetadataException is a TenantRegistryException, so existing handlers still catch it")
    void isARegistryException() {
        assertThat(new TenantMetadataException("m", null)).isInstanceOf(TenantRegistryException.class);
    }

    @Test
    @DisplayName("a null key is refused outright")
    void nullKeyRefused() {
        assertThatThrownBy(() -> tenant(Map.of()).metadata((MetadataKey<Integer>) null))
                .isInstanceOf(NullPointerException.class);
    }
}
