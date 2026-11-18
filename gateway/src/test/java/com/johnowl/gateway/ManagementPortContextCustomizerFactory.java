package com.johnowl.gateway;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

/**
 * Gives each Spring Boot test context a free management port. With {@code RANDOM_PORT}, Spring Boot
 * sets {@code management.server.port=0}, and the gateway does not start with this value (spec 024,
 * "Management port"). The port is chosen when the context starts, so two cached contexts never share
 * it. A test that sets {@code management.server.port} itself keeps its value.
 *
 * <p>The gateway and the management server bind {@link Loopback#ADDRESS}, not the wildcard address, so that
 * a test never reaches another process on the same port (see {@link Loopback}). A test that sets
 * {@code server.address} or {@code management.server.address} itself keeps its value.
 */
public final class ManagementPortContextCustomizerFactory implements ContextCustomizerFactory {

    private static final String MANAGEMENT_PORT = "management.server.port";

    private static final String SERVER_ADDRESS = "server.address";

    private static final String MANAGEMENT_ADDRESS = "management.server.address";

    @Override
    public ContextCustomizer createContextCustomizer(Class<?> testClass,
            List<ContextConfigurationAttributes> configAttributes) {
        return new ManagementPortContextCustomizer();
    }

    /** All instances are equal, so the customizer does not prevent context caching. */
    private static final class ManagementPortContextCustomizer implements ContextCustomizer {

        @Override
        public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration config) {
            Map<String, Object> properties = new HashMap<>();
            if (!isSet(config, MANAGEMENT_PORT)) {
                properties.put(MANAGEMENT_PORT, FreePort.next());
            }
            for (String address : List.of(SERVER_ADDRESS, MANAGEMENT_ADDRESS)) {
                if (!isSet(config, address)) {
                    properties.put(address, Loopback.ADDRESS);
                }
            }
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("loopbackServers", properties));
        }

        private static boolean isSet(MergedContextConfiguration config, String name) {
            for (String property : config.getPropertySourceProperties()) {
                if (property.startsWith(name) && property.length() > name.length()
                        && "=: ".indexOf(property.charAt(name.length())) >= 0) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ManagementPortContextCustomizer;
        }

        @Override
        public int hashCode() {
            return ManagementPortContextCustomizer.class.hashCode();
        }
    }
}
