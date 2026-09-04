package io.tenantlayer.schema;

import jakarta.persistence.Column;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.persister.entity.EntityPersister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Feature 21 — works out which tables are tenant-scoped.
 *
 * <p>This is input for two other features rather than an end in itself: the RLS policy
 * generator (feature 30) needs to know what to write policies for, and the isolation
 * checker (feature 31, v0.4) needs to know what to check.
 *
 * <h2>How a table is judged tenant-scoped</h2>
 *
 * Either the entity carries Hibernate's {@code @TenantId} on a field, or it has a column
 * with the configured tenant name ({@code tenant_id} by default). Explicit includes and
 * excludes override both, because the convention will be wrong somewhere — a shared
 * reference table that happens to have a {@code tenant_id} audit column, say.
 *
 * <p>Table names come from Hibernate's runtime metamodel rather than from reading
 * {@code @Table} annotations, because the answer depends on the physical naming strategy
 * and Hibernate is the only component that actually knows what it resolved to.
 */
public class TenantScopedEntityScanner {

    private static final Logger log = LoggerFactory.getLogger(TenantScopedEntityScanner.class);

    public static final String DEFAULT_TENANT_COLUMN = "tenant_id";
    private static final String HIBERNATE_TENANT_ID = "org.hibernate.annotations.TenantId";

    private final EntityManagerFactory entityManagerFactory;
    private final String tenantColumn;
    private final Set<String> includes;
    private final Set<String> excludes;

    public TenantScopedEntityScanner(EntityManagerFactory entityManagerFactory) {
        this(entityManagerFactory, DEFAULT_TENANT_COLUMN, Set.of(), Set.of());
    }

    public TenantScopedEntityScanner(EntityManagerFactory entityManagerFactory,
                                     String tenantColumn, Set<String> includes,
                                     Set<String> excludes) {
        this.entityManagerFactory = entityManagerFactory;
        this.tenantColumn = tenantColumn == null || tenantColumn.isBlank()
                ? DEFAULT_TENANT_COLUMN : tenantColumn;
        this.includes = includes == null ? Set.of() : Set.copyOf(includes);
        this.excludes = excludes == null ? Set.of() : Set.copyOf(excludes);
    }

    public List<TenantScopedTable> scan() {
        SessionFactoryImplementor sessionFactory =
                entityManagerFactory.unwrap(SessionFactoryImplementor.class);

        List<TenantScopedTable> found = new ArrayList<>();
        for (EntityType<?> entityType : entityManagerFactory.getMetamodel().getEntities()) {
            Class<?> javaType = entityType.getJavaType();
            if (javaType == null) {
                continue;
            }
            String table = tableNameOf(sessionFactory, javaType);
            if (table == null) {
                continue;
            }
            if (excludes.contains(table) || excludes.contains(entityType.getName())) {
                continue;
            }
            String column = tenantColumnOf(javaType);
            boolean included = includes.contains(table) || includes.contains(entityType.getName());
            if (column != null || included) {
                found.add(new TenantScopedTable(entityType.getName(), table,
                        column == null ? tenantColumn : column));
            }
        }
        found.sort(null);
        log.debug("tenant-scoped tables: {}", found);
        return List.copyOf(found);
    }

    /**
     * Looked up by Java type rather than by name. The JPA metamodel's {@code getName()}
     * returns the short entity name ("Document"), while Hibernate's mapping metamodel is
     * keyed by the fully-qualified one — passing the short name throws
     * {@code UnknownEntityTypeException} for every entity, and the scan silently comes
     * back empty rather than failing.
     */
    private String tableNameOf(SessionFactoryImplementor sessionFactory, Class<?> javaType) {
        try {
            EntityPersister persister = sessionFactory.getMappingMetamodel()
                    .getEntityDescriptor(javaType);
            return persister.getMappedTableDetails().getTableName();
        } catch (RuntimeException e) {
            log.debug("could not resolve a table for entity {}", javaType.getName(), e);
            return null;
        }
    }

    /**
     * Reflection rather than the Hibernate column metamodel, deliberately: the field-level
     * answer is identical across Hibernate 6 and 7, whereas the metamodel's column APIs
     * have moved between them. Feature 110 commits to both versions, so the narrower the
     * Hibernate surface this touches, the fewer things break on an upgrade.
     */
    private String tenantColumnOf(Class<?> javaType) {
        for (Class<?> type = javaType; type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                String column = columnNameOf(field);
                if (hasTenantIdAnnotation(field) || tenantColumn.equalsIgnoreCase(column)) {
                    return column;
                }
            }
        }
        return null;
    }

    private boolean hasTenantIdAnnotation(Field field) {
        for (var annotation : field.getAnnotations()) {
            if (annotation.annotationType().getName().equals(HIBERNATE_TENANT_ID)) {
                return true;
            }
        }
        return false;
    }

    private String columnNameOf(Field field) {
        Column column = field.getAnnotation(Column.class);
        if (column != null && !column.name().isBlank()) {
            return column.name();
        }
        return camelToSnake(field.getName());
    }

    private static String camelToSnake(String name) {
        StringBuilder out = new StringBuilder(name.length() + 4);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                out.append('_');
            }
            out.append(Character.toLowerCase(c));
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }
}
