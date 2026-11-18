package com.johnowl.gateway.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Top-level settings of the gateway.
 *
 * @param publicBaseUrl the public URL of the gateway, for example {@code https://gateway.example.com}.
 *     Required. See {@link PublicBaseUrl} for the rules.
 * @param session the session store (spec 022, "Session store")
 */
@ConfigurationProperties("gateway")
public record GatewayProperties(String publicBaseUrl, @DefaultValue Session session) {

    /** The property that selects the session store. */
    public static final String SESSION_STORE = "gateway.session.store";

    /** The session stores. */
    public enum SessionStore {
        /** Sessions in the memory of each gateway instance. The default. */
        MEMORY,
        /** Sessions in Redis, shared by all gateway instances. */
        REDIS
    }

    /**
     * @param store the session store
     * @param memory the settings of the store {@code memory}
     */
    public record Session(@DefaultValue("memory") SessionStore store, @DefaultValue Memory memory) {
    }

    /** @param maxSessions the maximum number of sessions in the memory of one gateway instance */
    public record Memory(@DefaultValue("100000") int maxSessions) {
    }
}
