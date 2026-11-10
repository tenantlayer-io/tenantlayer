package io.tenantlayer.registry;

import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Feature 55 — a typed, named view onto one entry of a tenant's {@code metadata}.
 *
 * <p>The registry stores metadata as {@code Map<String,String>}, and that does not change.
 * A key says what one of those strings <em>means</em>, so the code reading it does not
 * parse it by hand, differently, in every place that needs it:
 *
 * <pre>{@code
 * static final MetadataKey<Integer> SEATS = MetadataKey.ofInt("seats")
 *         .require(n -> n > 0, "a positive number");
 *
 * int seats = tenant.requireMetadata(SEATS);
 * }</pre>
 *
 * <p>Parsing is strict on purpose. A value that is present but does not parse is an error,
 * never a silent default: {@code Boolean.parseBoolean("yes")} is {@code false}, which for
 * a flag such as "sso required" is the insecure answer arriving without anyone having typed
 * it. Here {@code "yes"} is rejected, and the caller finds out.
 *
 * <p>Values are not trimmed or case-folded. {@code " 5"} is not {@code 5}, and an enum is
 * matched by its exact constant name.
 */
public final class MetadataKey<T> {

    private final String name;
    private final String description;
    private final Function<String, T> parser;

    private MetadataKey(String name, String description, Function<String, T> parser) {
        this.name = name;
        this.description = description;
        this.parser = parser;
    }

    /**
     * A key with a parser of your own. The parser must throw (any {@link RuntimeException})
     * for a value it does not accept, and must not return {@code null}.
     *
     * @param description what a valid value looks like, used in error messages — for
     *                    example {@code "an integer"}
     */
    public static <T> MetadataKey<T> of(String name, String description, Function<String, T> parser) {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(parser, "parser must not be null");
        return new MetadataKey<>(name, description, parser);
    }

    /** The value as stored, including the empty string. */
    public static MetadataKey<String> ofString(String name) {
        return of(name, "a string", Function.identity());
    }

    public static MetadataKey<Integer> ofInt(String name) {
        return of(name, "an integer", Integer::parseInt);
    }

    public static MetadataKey<Long> ofLong(String name) {
        return of(name, "a long integer", Long::parseLong);
    }

    /** Exactly {@code true} or {@code false}, lower case. Anything else is rejected. */
    public static MetadataKey<Boolean> ofBoolean(String name) {
        return of(name, "'true' or 'false'", raw -> {
            if (raw.equals("true")) {
                return Boolean.TRUE;
            }
            if (raw.equals("false")) {
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("not a boolean");
        });
    }

    /** An ISO-8601 duration such as {@code PT30S} or {@code P2D}. */
    public static MetadataKey<Duration> ofDuration(String name) {
        return of(name, "an ISO-8601 duration such as PT30S", Duration::parse);
    }

    /** The exact name of one of the enum's constants. */
    public static <E extends Enum<E>> MetadataKey<E> ofEnum(String name, Class<E> type) {
        Objects.requireNonNull(type, "type must not be null");
        String allowed = Arrays.stream(type.getEnumConstants())
                .map(Enum::name)
                .collect(Collectors.joining(", "));
        return of(name, "one of " + allowed, raw -> {
            for (E constant : type.getEnumConstants()) {
                if (constant.name().equals(raw)) {
                    return constant;
                }
            }
            throw new IllegalArgumentException("not a constant of " + type.getSimpleName());
        });
    }

    /**
     * A copy of this key that also rejects any parsed value failing {@code check}.
     *
     * @param requirement what a valid value looks like, used in error messages — for
     *                    example {@code "a positive number"}
     */
    public MetadataKey<T> require(Predicate<? super T> check, String requirement) {
        Objects.requireNonNull(check, "check must not be null");
        Objects.requireNonNull(requirement, "requirement must not be null");
        Function<String, T> inner = parser;
        return new MetadataKey<>(name, description + ", " + requirement, raw -> {
            T value = inner.apply(raw);
            if (!check.test(value)) {
                throw new IllegalArgumentException("fails the requirement");
            }
            return value;
        });
    }

    public String name() {
        return name;
    }

    /** What a valid value looks like; used in error messages. */
    public String description() {
        return description;
    }

    /**
     * Parses a stored value. A parser that returns {@code null} is refused by the caller,
     * {@code TenantRegistration#metadata(MetadataKey)}, which wraps the result in
     * {@code Optional.of}.
     *
     * @throws RuntimeException if the value is not valid for this key
     */
    T parse(String raw) {
        return parser.apply(raw);
    }

    @Override
    public String toString() {
        return "MetadataKey[" + name + ": " + description + "]";
    }
}
