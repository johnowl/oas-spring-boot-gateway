package com.johnowl.gateway;

import reactor.blockhound.BlockHound;
import reactor.blockhound.integration.BlockHoundIntegration;

/**
 * The calls that BlockHound allows on Netty threads in the tests of the module (spec 002, "BlockHound").
 * Each one is a short lock around work in memory, never I/O. Spring Core allows the locks of its
 * {@code ConcurrentReferenceHashMap} in the same way.
 *
 * <p>Jackson builds a deserializer under the lock of its {@code DeserializerCache}. It does not cache
 * some deserializers, for example those of the JDK collections of Spring Security, so it builds them
 * again for each read. Redis reads the session values on its event loop, so two parallel requests of
 * one session can wait for this lock. A contended {@code synchronized} block is the same wait, but
 * BlockHound sees only the park of the {@code ReentrantLock} (CI run 43712577572: {@code 500} instead
 * of {@code 401}).
 */
public final class GatewayBlockHoundIntegration implements BlockHoundIntegration {

    @Override
    public void applyTo(BlockHound.Builder builder) {
        builder.allowBlockingCallsInside("tools.jackson.databind.deser.DeserializerCache", "_createAndCacheValueDeserializer");
    }
}
