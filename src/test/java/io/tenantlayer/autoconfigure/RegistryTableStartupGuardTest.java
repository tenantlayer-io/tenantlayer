package io.tenantlayer.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.tenantlayer.registry.TenantRegistration;
import io.tenantlayer.registry.TenantRegistry;
import io.tenantlayer.registry.TenantRegistryException;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The registry bean exists for anyone with a DataSource, so enforcing status would put a
 * query against a table many deployments never created onto every request. These pin the
 * one decision that prevents that, and the one it must not make.
 */
class RegistryTableStartupGuardTest {

    @Test
    @DisplayName("a missing registry table switches enforcement off rather than failing every request")
    void missingTableDisablesEnforcement() {
        TenantRegistry registry = failingWith(new SQLException("relation does not exist", "42P01"));

        assertThat(TenantLayerAutoConfiguration.enforceableRegistry(registry))
                .as("an application that never created the table must keep serving traffic")
                .isNull();
    }

    @Test
    @DisplayName("a database that is merely unreachable leaves enforcement on")
    void transientFailureKeepsEnforcement() {
        TenantRegistry registry = failingWith(new SQLException("connection refused", "08006"));

        assertThat(TenantLayerAutoConfiguration.enforceableRegistry(registry))
                .as("a blip at start-up must not disable a security control for the whole run")
                .isSameAs(registry);
    }

    @Test
    @DisplayName("a registry that answers is used")
    void workingRegistryIsEnforced() {
        TenantRegistry registry = new StubRegistry(null);

        assertThat(TenantLayerAutoConfiguration.enforceableRegistry(registry)).isSameAs(registry);
    }

    @Test
    void noRegistryIsNotAnError() {
        assertThat(TenantLayerAutoConfiguration.enforceableRegistry(null)).isNull();
    }

    private static TenantRegistry failingWith(SQLException cause) {
        return new StubRegistry(new TenantRegistryException("looking up tenant failed", cause));
    }

    /** Answers empty, or throws whatever it was given. */
    private record StubRegistry(RuntimeException failure) implements TenantRegistry {

        @Override
        public Optional<TenantRegistration> find(String tenantId) {
            if (failure != null) {
                throw failure;
            }
            return Optional.empty();
        }

        @Override
        public List<TenantRegistration> findAll() {
            return List.of();
        }

        @Override
        public List<String> activeTenantIds() {
            return List.of();
        }

        @Override
        public void save(TenantRegistration registration) {
        }

        @Override
        public boolean delete(String tenantId) {
            return false;
        }
    }
}
