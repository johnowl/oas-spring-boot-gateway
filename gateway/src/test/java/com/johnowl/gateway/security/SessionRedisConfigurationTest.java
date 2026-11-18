package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.session.data.redis.RedisSessionMapper;

/** The session values of Redis that make a session missing (spec 022, "Session serialization"). */
class SessionRedisConfigurationTest {

    private static final String CREATION_TIME = "creationTime";

    private final RedisSessionMapper mapper = new RedisSessionMapper();

    @Test
    void readsAValidSession() {
        assertThat(SessionRedisConfiguration.readSession(mapper, "id-1", metadata()).blockOptional())
                .hasValueSatisfying(session -> assertThat(session.getId()).isEqualTo("id-1"));
    }

    @Test
    void sessionWithAnUnreadableValueIsMissing() {
        Map<String, Object> values = metadata();
        values.put("sessionAttr:injected", SessionJsonSerializer.UNREADABLE);

        assertThat(SessionRedisConfiguration.readSession(mapper, "id-1", values).blockOptional()).isEmpty();
    }

    @Test
    void sessionWithoutMetadataIsMissing() {
        Map<String, Object> values = metadata();
        values.remove(CREATION_TIME);

        assertThat(SessionRedisConfiguration.readSession(mapper, "id-1", values).blockOptional()).isEmpty();
    }

    // QA-51-4: RedisSessionMapper casts the metadata, so another type gives a ClassCastException.
    @Test
    void sessionWithMetadataOfAnotherTypeIsMissing() {
        Map<String, Object> values = metadata();
        values.put(CREATION_TIME, "2026-11-13T08:00:00Z");

        assertThat(SessionRedisConfiguration.readSession(mapper, "id-1", values).blockOptional()).isEmpty();
    }

    private static Map<String, Object> metadata() {
        Map<String, Object> values = new HashMap<>();
        values.put(CREATION_TIME, 1_700_000_000_000L);
        values.put("lastAccessedTime", 1_700_000_000_000L);
        values.put("maxInactiveInterval", 300);
        return values;
    }
}
