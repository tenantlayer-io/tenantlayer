package io.tenantlayer.test;

import io.tenantlayer.core.TenantContext;
import io.tenantlayer.core.TenantScope;
import java.lang.reflect.AnnotatedElement;
import java.util.Optional;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;

public class WithTenantExtension implements BeforeEachCallback, AfterEachCallback {

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(WithTenantExtension.class);

    @Override
    public void beforeEach(ExtensionContext context) {
        find(context).ifPresent(tenant -> {
            TenantScope previous = TenantContext.current().orElse(null);
            context.getStore(NAMESPACE).put("previous", previous);
            TenantContext.enter(TenantScope.of(tenant));
        });
    }

    @Override
    public void afterEach(ExtensionContext context) {
        find(context).ifPresent(tenant -> {
            TenantScope previous = (TenantScope) context.getStore(NAMESPACE).get("previous");
            TenantContext.exit(previous);
        });
    }

    private Optional<String> find(ExtensionContext context) {
        Optional<String> onMethod = context.getTestMethod()
                .flatMap(m -> annotationOn(m));
        if (onMethod.isPresent()) {
            return onMethod;
        }
        return context.getTestClass().flatMap(c -> annotationOn(c));
    }

    private Optional<String> annotationOn(AnnotatedElement element) {
        return AnnotationSupport.findAnnotation(element, WithTenant.class).map(WithTenant::value);
    }
}
