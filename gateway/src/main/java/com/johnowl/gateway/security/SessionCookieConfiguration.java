package com.johnowl.gateway.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.session.CookieWebSessionIdResolver;
import org.springframework.web.server.session.WebSessionIdResolver;

/**
 * The session cookie {@code __Host-SESSION}: {@code HttpOnly}, {@code Secure}, {@code Path=/},
 * {@code SameSite=Lax}, and no {@code Domain}. {@code Secure} is explicit, because TLS can end at a
 * load balancer and the gateway then sees {@code http}. {@code Lax}, not {@code Strict}: the Entra ID
 * callback is a cross-site top-level navigation, and it needs the cookie.
 */
@Configuration(proxyBeanMethods = false)
public class SessionCookieConfiguration {

    static final String COOKIE_NAME = "__Host-SESSION";

    @Bean
    WebSessionIdResolver webSessionIdResolver() {
        CookieWebSessionIdResolver resolver = new CookieWebSessionIdResolver();
        resolver.setCookieName(COOKIE_NAME);
        resolver.addCookieInitializer(cookie -> cookie.httpOnly(true).secure(true).sameSite("Lax").path("/"));
        return resolver;
    }
}
