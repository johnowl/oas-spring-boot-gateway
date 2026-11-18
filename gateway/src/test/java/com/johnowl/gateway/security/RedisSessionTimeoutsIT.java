package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** The tests of {@link SessionTimeoutsIT} with the session store {@code redis}. */
@TestPropertySource(properties = "gateway.session.store=redis")
class RedisSessionTimeoutsIT extends SessionTimeoutsIT {

    private final MockIdentityProvider idp = MockIdentityProviderSession.provider();

    @Test
    void preLoginSessionKeyHasTheShortTimeToLive() {
        TestBrowser browser = browser();
        browser.startLogin(idp);

        String key = "spring:session:sessions:" + browser.cookie(TestBrowser.SESSION);
        assertThat(RedisTestContainer.admin().ttl(key)).isBetween(1L, 2L);
    }
}
