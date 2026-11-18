package com.johnowl.gateway.security;

import java.util.List;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

/**
 * Registers the test bean of {@link LocalIdentityProvider} in each Spring test context, because every
 * Spring Boot test uses the issuer of {@link MockIdentityProvider} on localhost. The file
 * {@code META-INF/spring.factories} of the tests lists this factory. Production code has no such bean.
 */
public final class LocalIdentityProviderContextCustomizerFactory implements ContextCustomizerFactory {

    @Override
    public ContextCustomizer createContextCustomizer(Class<?> testClass,
            List<ContextConfigurationAttributes> configAttributes) {
        return new LocalIdentityProviderCustomizer();
    }

    /** All instances are equal, so the customizer does not prevent context caching. */
    private static final class LocalIdentityProviderCustomizer implements ContextCustomizer {

        @Override
        public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration config) {
            context.getBeanFactory().registerSingleton("localIdentityProviderOfTheTests", new TestLocalIdentityProvider.Marker());
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof LocalIdentityProviderCustomizer;
        }

        @Override
        public int hashCode() {
            return LocalIdentityProviderCustomizer.class.hashCode();
        }
    }
}
