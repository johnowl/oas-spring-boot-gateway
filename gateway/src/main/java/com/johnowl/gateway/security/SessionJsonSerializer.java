package com.johnowl.gateway.security;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.security.jackson.SecurityJacksonModules;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.BeanPropertyWriter;
import tools.jackson.databind.ser.ValueSerializerModifier;

/**
 * Writes the session values to Redis as JSON, never with Java serialization (spec 022, "Session
 * serialization"). Each object carries its class name, and a type validator allows only the types
 * of the Jackson modules of Spring Security: the security context, the OIDC user, the authorization
 * request, and the authorized client. Strings and numbers need no type.
 *
 * <p>A value that does not read, for example a type that is not allowed, becomes
 * {@link #UNREADABLE}. The session repository then treats the whole session as missing.
 *
 * <p>The client secret of a {@link ClientRegistration} is never written. An authorized client
 * holds its registration, so without this rule every session in Redis would contain the secret of
 * {@code gateway-bff}. A registration that is read back has no secret; code that needs the secret
 * takes the registration from the {@code ReactiveClientRegistrationRepository}.
 */
final class SessionJsonSerializer implements RedisSerializer<Object> {

    /** The value of a session attribute that does not read. */
    static final Object UNREADABLE = new Object() {
        @Override
        public String toString() {
            return "UNREADABLE";
        }
    };

    private static final Logger log = LoggerFactory.getLogger(SessionJsonSerializer.class);

    private static final byte[] EMPTY = new byte[0];

    private final JsonMapper mapper;

    SessionJsonSerializer(ClassLoader classLoader) {
        // The modules add their own types to the validator; this builder allows nothing else.
        BasicPolymorphicTypeValidator.Builder allowedTypes = BasicPolymorphicTypeValidator.builder();
        SimpleModule noClientSecret = new SimpleModule("gateway-no-client-secret");
        noClientSecret.setSerializerModifier(new ValueSerializerModifier() {
            @Override
            public List<BeanPropertyWriter> changeProperties(SerializationConfig config,
                    BeanDescription.Supplier beanDescription, List<BeanPropertyWriter> properties) {
                if (!ClientRegistration.class.equals(beanDescription.getBeanClass())) {
                    return properties;
                }
                return properties.stream().filter(property -> !property.getName().equals("clientSecret")).toList();
            }
        });
        mapper = JsonMapper.builder()
                .addModules(SecurityJacksonModules.getModules(classLoader, allowedTypes))
                .addModule(noClientSecret)
                .build();
    }

    @Override
    public byte[] serialize(Object value) {
        return value == null ? EMPTY : mapper.writeValueAsBytes(value);
    }

    @Override
    public Object deserialize(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            return mapper.readValue(bytes, Object.class);
        } catch (JacksonException e) {
            // No value in the log: it can contain a token.
            log.warn("A session value in Redis does not read as an allowed JSON type ({}). The session is not used.",
                    e.getClass().getSimpleName());
            return UNREADABLE;
        }
    }
}
