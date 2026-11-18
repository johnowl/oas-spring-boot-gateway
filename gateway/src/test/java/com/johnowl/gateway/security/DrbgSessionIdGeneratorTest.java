package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.DrbgParameters;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

class DrbgSessionIdGeneratorTest {

    private final DrbgSessionIdGenerator generator = new DrbgSessionIdGenerator();

    @Test
    void createsUniqueUrlSafeIdsWith256Bits() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            ids.add(generator.generate());
        }

        assertThat(ids).hasSize(1000).allSatisfy(id -> assertThat(id).hasSize(43).matches("[A-Za-z0-9_-]+"));
    }

    // SEC-51-1: the JDK default is 128 bits.
    @Test
    void asksForTheSecurityStrengthOf256Bits() {
        assertThat(generator.parameters()).isInstanceOfSatisfying(DrbgParameters.Instantiation.class, parameters -> {
            assertThat(parameters.getStrength()).isEqualTo(256);
            assertThat(parameters.getCapability()).isEqualTo(DrbgParameters.Capability.RESEED_ONLY);
        });
    }

    // NativePRNG reads /dev/urandom again after 100 ms. BlockHound fails the call on a
    // non-blocking thread, as with UUID.randomUUID().
    @Test
    void doesNotBlockOnTheEventLoop() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            Thread.sleep(150);
            String id = Mono.fromSupplier(generator::generate).subscribeOn(Schedulers.parallel())
                    .block(Duration.ofSeconds(5));
            assertThat(id).isNotBlank();
        }
    }
}
