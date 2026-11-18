package com.johnowl.gateway.security;

import org.springframework.test.context.TestPropertySource;

/** The login flow of {@link SessionLoginIT} with the session store {@code redis}. */
@TestPropertySource(properties = "gateway.session.store=redis")
class RedisSessionLoginIT extends SessionLoginIT {
}
