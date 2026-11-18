package com.johnowl.gateway.security;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * Starts one {@link MockIdentityProvider} for each test JVM, before the first test. Every Spring
 * Boot test reads the OpenID Connect discovery document of the registration {@code entra} at
 * startup, so the provider must run before any application context starts. The system property
 * {@value #ISSUER_PROPERTY} gives its issuer to {@code config/application.yaml} of the tests.
 * The provider stops with the JVM, so cached application contexts can use it until the end.
 */
public final class MockIdentityProviderSession implements LauncherSessionListener {

    static final String ISSUER_PROPERTY = "mock-idp.issuer-uri";

    private static MockIdentityProvider provider;

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        provider();
    }

    /** The provider of this JVM. Starts it on the first call. */
    public static synchronized MockIdentityProvider provider() {
        if (provider == null) {
            provider = new MockIdentityProvider();
            System.setProperty(ISSUER_PROPERTY, provider.issuer());
        }
        return provider;
    }
}
