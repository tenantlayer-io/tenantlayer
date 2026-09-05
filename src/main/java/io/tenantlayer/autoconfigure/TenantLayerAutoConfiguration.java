package io.tenantlayer.autoconfigure;

import io.tenantlayer.client.TenantPropagatingExchangeFilter;
import io.tenantlayer.client.TenantPropagatingFeignInterceptor;
import io.tenantlayer.client.TenantPropagatingRequestInterceptor;
import io.tenantlayer.core.TenantAwareDataSource;
import io.tenantlayer.core.TenantResolver;
import io.tenantlayer.core.TenantResolverChain;
import io.tenantlayer.core.TenantTaskDecorator;
import io.tenantlayer.hibernate.TenantContextIdentifierResolver;
import io.tenantlayer.strategy.RowLevelSecurityStrategy;
import io.tenantlayer.strategy.SchemaPerTenantStrategy;
import io.tenantlayer.strategy.TenantConnectionStrategy;
import io.tenantlayer.security.ClaimTenantMembershipVerifier;
import io.tenantlayer.security.TenantMembershipVerifier;
import io.tenantlayer.web.HeaderTenantResolver;
import io.tenantlayer.web.JwtClaimTenantResolver;
import io.tenantlayer.web.PathSegmentTenantResolver;
import io.tenantlayer.web.SubdomainTenantResolver;
import io.tenantlayer.web.TenantFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.task.SimpleAsyncTaskExecutorCustomizer;
import org.springframework.boot.task.ThreadPoolTaskExecutorCustomizer;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.util.ClassUtils;

/**
 * One dependency, three things wired: the tenant is read off the request, published onto
 * every connection, and carried across @Async boundaries.
 *
 * Ordered before {@link DataSourceAutoConfiguration} so the post-processor is in place
 * when the application's DataSource is created.
 */
@AutoConfiguration(before = DataSourceAutoConfiguration.class)
@ConditionalOnClass(DataSource.class)
@EnableConfigurationProperties(TenantLayerProperties.class)
public class TenantLayerAutoConfiguration {

    /**
     * Spring Security registers its filter chain at -100. Anything that needs an
     * authenticated principal has to sit after that. Hard-coded rather than imported from
     * {@code SecurityProperties} so this class still loads when Spring Security is absent.
     */
    private static final int AFTER_SPRING_SECURITY = -90;

    private static final int BEFORE_EVERYTHING = Ordered.HIGHEST_PRECEDENCE + 20;

    private static final String JWT_CLASS = "org.springframework.security.oauth2.jwt.Jwt";

    /**
     * Wraps whatever DataSource the application defines, so users keep their own
     * datasource configuration and change nothing.
     *
     * Static, and returning a raw BeanPostProcessor, because a non-static @Bean method
     * here would force the enclosing configuration to initialise too early.
     */
    @Bean
    static BeanPostProcessor tenantLayerDataSourcePostProcessor(
            ObjectProvider<TenantLayerProperties> properties) {

        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName)
                    throws BeansException {
                if (bean instanceof DataSource dataSource
                        && !(bean instanceof TenantAwareDataSource)) {
                    TenantLayerProperties props = properties.getIfAvailable();
                    return new TenantAwareDataSource(dataSource, strategyFor(props, dataSource));
                }
                return bean;
            }
        };
    }

    /**
     * Row-level security unless configured otherwise, so upgrading changes nothing. The
     * strategy is chosen once at start-up — selecting it per request would be a way to
     * read another tenant's data by changing one property.
     */
    private static TenantConnectionStrategy strategyFor(
            TenantLayerProperties properties, DataSource dataSource) {

        if (properties == null) {
            return new RowLevelSecurityStrategy(dataSource);
        }
        return switch (properties.getStrategy()) {
            case ROW_LEVEL_SECURITY -> new RowLevelSecurityStrategy(dataSource);
            case SCHEMA_PER_TENANT ->
                    new SchemaPerTenantStrategy(dataSource, properties.getSchema().getPrefix());
        };
    }

    /** Carries the tenant into @Async and other Spring-managed executors. */
    @Bean
    ThreadPoolTaskExecutorCustomizer tenantLayerTaskExecutorCustomizer() {
        return executor -> executor.setTaskDecorator(new TenantTaskDecorator());
    }

    /**
     * Feature 12 — the same decorator, for the executor Boot builds instead when
     * {@code spring.threads.virtual.enabled=true}.
     *
     * <p>This is not defensive duplication. Setting that flag makes Boot construct a
     * {@link org.springframework.core.task.SimpleAsyncTaskExecutor} rather than a
     * {@code ThreadPoolTaskExecutor}, and a {@code ThreadPoolTaskExecutorCustomizer} is
     * simply never consulted for it. Without this bean, turning on virtual threads — one
     * property, widely recommended, with no mention of tenancy anywhere near it — removes
     * the task decorator, and every {@code @Async} method starts running with no tenant
     * bound. Isolation still holds, because a connection with no tenant sees nothing; what
     * breaks is the application, silently, as empty results.
     */
    @Bean
    SimpleAsyncTaskExecutorCustomizer tenantLayerSimpleAsyncTaskExecutorCustomizer() {
        return executor -> executor.setTaskDecorator(new TenantTaskDecorator());
    }

    /**
     * Builds the chain from {@code tenantlayer.resolvers}. A single configured source
     * yields that resolver directly rather than a chain of one.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    TenantResolver<HttpServletRequest> tenantResolver(TenantLayerProperties properties) {
        List<TenantResolver<HttpServletRequest>> resolvers = new ArrayList<>();
        for (TenantLayerProperties.Source source : properties.getResolvers()) {
            resolvers.add(switch (source) {
                case HEADER -> new HeaderTenantResolver(properties.getHeader());
                case SUBDOMAIN -> new SubdomainTenantResolver(properties.getBaseDomain());
                case PATH -> new PathSegmentTenantResolver(properties.getPathPrefix());
                case JWT -> newJwtResolver(properties.getJwtClaim());
            });
        }
        if (resolvers.isEmpty()) {
            resolvers.add(new HeaderTenantResolver(properties.getHeader()));
        }
        return resolvers.size() == 1 ? resolvers.get(0) : new TenantResolverChain<>(resolvers);
    }

    /**
     * Fails with an explanation rather than a NoClassDefFoundError three frames deep. The
     * OAuth2 resource-server dependency is optional, so asking for JWT resolution without
     * it is a configuration mistake worth naming.
     */
    private static TenantResolver<HttpServletRequest> newJwtResolver(String claim) {
        if (!ClassUtils.isPresent(JWT_CLASS, TenantLayerAutoConfiguration.class.getClassLoader())) {
            throw new IllegalStateException(
                    "tenantlayer.resolvers includes JWT, but Spring Security's OAuth2 JWT support "
                    + "is not on the classpath. Add spring-boot-starter-oauth2-resource-server.");
        }
        return new JwtClaimTenantResolver(claim);
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    FilterRegistrationBean<TenantFilter> tenantLayerFilter(
            TenantResolver<HttpServletRequest> resolver,
            TenantLayerProperties properties,
            ObjectProvider<TenantMembershipVerifier> membershipVerifier) {

        TenantMembershipVerifier verifier = membershipVerifier.getIfAvailable();

        FilterRegistrationBean<TenantFilter> registration = new FilterRegistrationBean<>(
                new TenantFilter(resolver, properties.isStrict(), properties.getUnscopedPaths(),
                        verifier));
        registration.setOrder(filterOrder(properties, verifier != null));
        return registration;
    }

    /**
     * Early by default: the tenant must exist before anything downstream touches the
     * database. But a filter that reads an authenticated principal cannot also run before
     * authentication happens — placed early, the JWT resolver would find an empty
     * SecurityContext on every request and fall through to whatever comes next in the
     * chain, which is exactly the spoofable header it was added to outrank.
     */
    private static int filterOrder(TenantLayerProperties properties, boolean membershipActive) {
        if (properties.getFilterOrder() != null) {
            return properties.getFilterOrder();
        }
        return properties.resolvesFromAuthentication() || membershipActive
                ? AFTER_SPRING_SECURITY
                : BEFORE_EVERYTHING;
    }


    /**
     * Feature 16 — outbound propagation.
     *
     * <p>These are customizers, so they reach clients built from Boot's
     * {@code RestTemplateBuilder}, {@code RestClient.Builder} and {@code WebClient.Builder}.
     * A client constructed with {@code new RestTemplate()} bypasses them, which is worth
     * knowing before concluding the header "sometimes" goes missing.
     *
     * <h2>Why these are four classes and not four @Bean methods</h2>
     *
     * WebFlux and Feign are optional dependencies, so most applications have neither.
     * Spring introspects every method of a configuration class — including each @Bean
     * method's return type — <em>before</em> it evaluates any {@code @ConditionalOnClass}
     * on those methods. A single class holding a bean that returns
     * {@code feign.RequestInterceptor} therefore fails to load with a
     * {@code NoClassDefFoundError} in every application that does not use Feign, and the
     * condition never gets a chance to exclude it.
     *
     * <p>Class-level {@code @ConditionalOnClass} is read from annotation metadata without
     * loading the class at all, so splitting them is what actually makes the dependency
     * optional. The library's own tests cannot catch this — its optional dependencies are
     * on its own test classpath — which is why it was found by the consuming service.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(org.springframework.web.client.RestTemplate.class)
    static class RestTemplatePropagation {

        @Bean
        RestTemplateCustomizer tenantLayerRestTemplateCustomizer(TenantLayerProperties properties) {
            return restTemplate -> restTemplate.getInterceptors()
                    .add(new TenantPropagatingRequestInterceptor(properties.getHeader()));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(org.springframework.web.client.RestClient.class)
    static class RestClientPropagation {

        @Bean
        RestClientCustomizer tenantLayerRestClientCustomizer(TenantLayerProperties properties) {
            return builder -> builder.requestInterceptor(
                    new TenantPropagatingRequestInterceptor(properties.getHeader()));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.web.reactive.function.client.WebClient")
    static class WebClientPropagation {

        @Bean
        WebClientCustomizer tenantLayerWebClientCustomizer(TenantLayerProperties properties) {
            return builder -> builder.filter(
                    new TenantPropagatingExchangeFilter(properties.getHeader()));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "feign.RequestInterceptor")
    static class FeignPropagation {

        @Bean
        @ConditionalOnMissingBean(feign.RequestInterceptor.class)
        feign.RequestInterceptor tenantLayerFeignInterceptor(TenantLayerProperties properties) {
            return new TenantPropagatingFeignInterceptor(properties.getHeader());
        }
    }

    /**
     * Feature 20 — makes Hibernate's {@code @TenantId} work by telling Hibernate where the
     * current tenant lives. Registered as a properties customizer rather than a plain bean
     * because the resolver has to be in place before the EntityManagerFactory is built.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(org.hibernate.SessionFactory.class)
    @ConditionalOnProperty(prefix = "tenantlayer.discriminator", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    static class DiscriminatorStrategy {

        @Bean
        HibernatePropertiesCustomizer tenantLayerHibernatePropertiesCustomizer() {
            return properties -> properties.put(
                    org.hibernate.cfg.AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER,
                    new TenantContextIdentifierResolver());
        }
    }

    /** Feature 52 — only when Spring Security is present and verification is asked for. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = JWT_CLASS)
    @ConditionalOnProperty(prefix = "tenantlayer.membership", name = "enabled",
            havingValue = "true")
    static class MembershipVerification {

        @Bean
        @ConditionalOnMissingBean
        TenantMembershipVerifier tenantMembershipVerifier(TenantLayerProperties properties) {
            return new ClaimTenantMembershipVerifier(properties.getMembership().getClaim());
        }
    }
}
