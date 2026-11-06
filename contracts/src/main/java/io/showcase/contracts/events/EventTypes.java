package io.showcase.contracts.events;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Registry: event type name <-> Java class, derived from the sealed hierarchy ITSELF.
 *
 * <p>{@link Class#getPermittedSubclasses()} (Java 17) lists the permitted subtypes of a sealed
 * type at runtime. Walking it recursively means a new event record is registered automatically:
 * no hand-maintained map that someone forgets to update. Only these whitelisted classes can ever
 * be deserialized, which is also a security control.
 */
public final class EventTypes {

    private static final Map<String, Class<? extends IntegrationEvent>> BY_NAME =
            leafTypes(IntegrationEvent.class)
                    .collect(Collectors.toUnmodifiableMap(Class::getSimpleName, Function.identity()));

    private EventTypes() {
    }

    public static String nameOf(IntegrationEvent event) {
        return event.getClass().getSimpleName();
    }

    public static Optional<Class<? extends IntegrationEvent>> classOf(String eventType) {
        return Optional.ofNullable(BY_NAME.get(eventType));
    }

    public static java.util.Set<String> knownTypes() {
        return BY_NAME.keySet();
    }

    @SuppressWarnings("unchecked")
    private static Stream<Class<? extends IntegrationEvent>> leafTypes(Class<?> type) {
        if (!type.isSealed()) {
            return Stream.of((Class<? extends IntegrationEvent>) type);   // a record = a leaf
        }
        return Arrays.stream(type.getPermittedSubclasses()).flatMap(EventTypes::leafTypes);
    }
}
