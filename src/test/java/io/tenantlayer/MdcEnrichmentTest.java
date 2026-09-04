package io.tenantlayer;

import static org.assertj.core.api.Assertions.assertThat;

import io.tenantlayer.core.TenantContext;
import io.tenantlayer.core.TenantScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * Feature 83 — every log line carries the tenant.
 *
 * Enrichment lives in TenantContext rather than in the servlet filter, so it holds for
 * scheduled jobs and message consumers too, not only HTTP requests.
 */
class MdcEnrichmentTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("the tenant is in the MDC while in scope and gone afterwards")
    void tenantIsPublishedToMdc() {
        assertThat(MDC.get(TenantContext.MDC_KEY)).isNull();

        TenantContext.runWithTenant(TenantScope.of("acme"), () ->
                assertThat(MDC.get(TenantContext.MDC_KEY)).isEqualTo("acme"));

        assertThat(MDC.get(TenantContext.MDC_KEY))
                .as("a thread that kept the tenant in its MDC would mislabel later log lines")
                .isNull();
    }

    @Test
    @DisplayName("nesting restores the outer tenant, it does not clear it")
    void nestingRestoresTheOuterTenant() {
        TenantContext.runWithTenant(TenantScope.of("acme"), () -> {
            TenantContext.runWithTenant(TenantScope.of("globex"), () ->
                    assertThat(MDC.get(TenantContext.MDC_KEY)).isEqualTo("globex"));

            assertThat(MDC.get(TenantContext.MDC_KEY)).isEqualTo("acme");
        });
    }
}
