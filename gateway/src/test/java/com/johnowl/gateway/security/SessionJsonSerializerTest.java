package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import reactor.blockhound.BlockingOperationError;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.cfg.DeserializationContexts;
import tools.jackson.databind.deser.DeserializerCache;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

class SessionJsonSerializerTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    private final SessionJsonSerializer serializer = new SessionJsonSerializer(getClass().getClassLoader());

    @Test
    void readsBackTheSecurityContext() {
        OidcIdToken idToken = new OidcIdToken("id-token-value", Instant.now(), Instant.now().plusSeconds(300),
                Map.of("sub", "s", "oid", "oid-1", "name", "Test User"));
        DefaultOidcUser user = new DefaultOidcUser(Set.of(new OidcUserAuthority(idToken)), idToken, "oid");
        SecurityContextImpl context = new SecurityContextImpl(
                new OAuth2AuthenticationToken(user, user.getAuthorities(), "entra"));

        Object read = roundTrip(context);

        assertThat(read).isInstanceOf(SecurityContextImpl.class);
        assertThat(((SecurityContextImpl) read).getAuthentication().getName()).isEqualTo("oid-1");
    }

    @Test
    void readsBackTheAuthorizationRequest() {
        OAuth2AuthorizationRequest request = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://login.example.com/authorize").clientId("client-id-1")
                .redirectUri("https://gateway.example.com/login/oauth2/code/entra").state("state-1")
                .attributes(attributes -> attributes.put("code_verifier", "verifier-1"))
                .build();

        assertThat(roundTrip(request)).usingRecursiveComparison().isEqualTo(request);
    }

    @Test
    void readsBackTheAuthorizedClientsWithoutTheClientSecret() {
        OAuth2AuthorizedClient client = new OAuth2AuthorizedClient(registration(), "oid-1",
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "access-1", Instant.now(),
                        Instant.now().plusSeconds(3600)),
                new OAuth2RefreshToken("refresh-1", Instant.now()));
        Map<String, OAuth2AuthorizedClient> clients = new HashMap<>(Map.of("entra", client));

        byte[] json = serializer.serialize(clients);
        @SuppressWarnings("unchecked")
        Map<String, OAuth2AuthorizedClient> read = (Map<String, OAuth2AuthorizedClient>) serializer.deserialize(json);

        assertThat(new String(json, StandardCharsets.UTF_8)).contains("refresh-1")
                .doesNotContain("test-only-dummy-secret").doesNotContain("clientSecret");
        assertThat(read.get("entra").getRefreshToken().getTokenValue()).isEqualTo("refresh-1");
        assertThat(read.get("entra").getClientRegistration().getClientSecret()).isNullOrEmpty();
    }

    @Test
    void readsBackStringsAndNumbers() {
        assertThat(roundTrip("/orders-api/orders/42?view=full")).isEqualTo("/orders-api/orders/42?view=full");
        assertThat(roundTrip(1_763_000_000_000L)).isEqualTo(1_763_000_000_000L);
        assertThat(roundTrip(1800)).isEqualTo(1800);
        assertThat(serializer.serialize(null)).isEmpty();
        assertThat(serializer.deserialize(new byte[0])).isNull();
    }

    @Test
    void writesJsonNotJavaSerialization() {
        for (Object value : List.of("a", 1L, new HashMap<>(Map.of("k", "v")))) {
            byte[] bytes = serializer.serialize(value);
            assertThat(bytes[0]).as("Java serialization starts with 0xACED").isNotEqualTo((byte) 0xAC);
        }
    }

    @Test
    void typeOutsideTheAllowlistDoesNotRead() {
        byte[] url = """
                {"@class":"java.net.URL","protocol":"http","host":"evil.example","port":-1,"file":"/"}
                """.getBytes(StandardCharsets.UTF_8);

        assertThat(serializer.deserialize(url)).isSameAs(SessionJsonSerializer.UNREADABLE);
    }

    @Test
    void invalidJsonAndJavaSerializationDoNotRead() throws IOException {
        ByteArrayOutputStream java = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(java)) {
            out.writeObject("value");
        }

        assertThat(serializer.deserialize("{not json".getBytes(StandardCharsets.UTF_8)))
                .isSameAs(SessionJsonSerializer.UNREADABLE);
        assertThat(serializer.deserialize(java.toByteArray())).isSameAs(SessionJsonSerializer.UNREADABLE);
    }

    /**
     * Redis reads the session values on its event loop. Jackson builds some deserializers again for each
     * read, under the lock of its deserializer cache, for example for the registration of an authorized
     * client. Two parallel reads can then wait for each other. Before the BlockHound integration of the
     * tests, BlockHound ended such a request with {@code 500} (CI run 43712577572). The test holds the lock
     * and reads on a parallel thread, where BlockHound is active: the read waits for the lock, and then
     * reads the client.
     */
    @Test
    void aReadThatWaitsForTheLockOfTheDeserializerCacheIsNotABlockingCall() throws Exception {
        OAuth2AuthorizedClient client = new OAuth2AuthorizedClient(registration(), "oid-1",
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "access-1", Instant.now(),
                        Instant.now().plusSeconds(3600)),
                new OAuth2RefreshToken("refresh-1", Instant.now()));
        byte[] json = serializer.serialize(client);
        serializer.deserialize(json);
        ReentrantLock lock = deserializerCacheLock(serializer);
        CompletableFuture<Object> read;

        lock.lock();
        try {
            read = Mono.fromCallable(() -> serializer.deserialize(json)).subscribeOn(Schedulers.parallel()).toFuture();
            await().atMost(WAIT).until(() -> lock.hasQueuedThreads() || read.isDone());
            assertThat(read).as("the read waits for the lock").isNotDone();
        } finally {
            lock.unlock();
        }

        assertThat(read.get(WAIT.toSeconds(), TimeUnit.SECONDS)).isInstanceOfSatisfying(OAuth2AuthorizedClient.class,
                stored -> assertThat(stored.getRefreshToken().getTokenValue()).isEqualTo("refresh-1"));
    }

    /**
     * The BlockHound integration of the tests allows the build and the resolution of a deserializer, for all
     * Jackson mappers, but never {@code deserialize()}. The first read of the test builds the deserializer of
     * {@link Sleeping} under the allowed method, and then calls its {@code deserialize()}, which sleeps.
     */
    @Test
    void aBlockingCallInDeserializeStillFailsWithBlockingOperationError() {
        ObjectMapper mapper = JsonMapper.builder()
                .addModule(new SimpleModule().addDeserializer(Sleeping.class, new SleepingDeserializer()))
                .build();
        Mono<Sleeping> read = Mono.fromCallable(() -> mapper.readValue("{}", Sleeping.class)).subscribeOn(Schedulers.parallel());

        assertThatThrownBy(() -> read.block(WAIT)).hasCauseInstanceOf(BlockingOperationError.class);
    }

    /** The lock under which the mapper of the serializer builds a deserializer. */
    static ReentrantLock deserializerCacheLock(SessionJsonSerializer serializer) throws ReflectiveOperationException {
        ObjectMapper mapper = (ObjectMapper) field(SessionJsonSerializer.class, "mapper").get(serializer);
        Object contexts = field(ObjectMapper.class, "_deserializationContexts").get(mapper);
        Object cache = field(DeserializationContexts.class, "_cache").get(contexts);
        return (ReentrantLock) field(DeserializerCache.class, "_incompleteDeserializersLock").get(cache);
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private Object roundTrip(Object value) {
        return serializer.deserialize(serializer.serialize(value));
    }

    private static ClientRegistration registration() {
        return ClientRegistration.withRegistrationId("entra")
                .clientId("client-id-1")
                .clientSecret("test-only-dummy-secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://gateway.example.com/login/oauth2/code/{registrationId}")
                .scope("openid")
                .authorizationUri("https://login.example.com/authorize")
                .tokenUri("https://login.example.com/token")
                .providerConfigurationMetadata(Map.of("end_session_endpoint", "https://login.example.com/logout"))
                .build();
    }

    private record Sleeping() {
    }

    private static final class SleepingDeserializer extends ValueDeserializer<Sleeping> {

        @Override
        public Sleeping deserialize(JsonParser parser, DeserializationContext context) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            parser.skipChildren();
            return new Sleeping();
        }
    }
}
