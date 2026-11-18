package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

/** The single-flight redemption of spec 023 on one gateway instance. */
class SessionRedemptionsTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    private final SessionRedemptions redemptions = new SessionRedemptions();

    @Test
    void sameSessionAndAudienceShareOneRedemption() {
        AtomicInteger calls = new AtomicInteger();
        Sinks.One<OAuth2AuthorizedClient> grant = Sinks.one();

        Mono<OAuth2AuthorizedClient> first = redemptions.redeem("s1", "entra-a", () -> {
            calls.incrementAndGet();
            return grant.asMono();
        }).cache();
        Mono<OAuth2AuthorizedClient> second = redemptions.redeem("s1", "entra-a", () -> {
            calls.incrementAndGet();
            return Mono.just(client("other"));
        }).cache();
        first.subscribe();
        second.subscribe();
        grant.tryEmitValue(client("a"));

        assertThat(first.block(WAIT)).isSameAs(second.block(WAIT));
        assertThat(calls).hasValue(1);
        assertThat(redemptions.running()).isZero();
    }

    @Test
    void anotherAudienceWaitsUntilTheRunningRedemptionEnds() {
        Sinks.One<OAuth2AuthorizedClient> grantA = Sinks.one();
        List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();

        Mono<OAuth2AuthorizedClient> a = redemptions.redeem("s1", "entra-a", () -> {
            order.add("start a");
            return grantA.asMono().doOnNext(c -> order.add("end a"));
        }).cache();
        Mono<OAuth2AuthorizedClient> b = redemptions.redeem("s1", "entra-b", () -> {
            order.add("start b");
            return Mono.just(client("b"));
        }).cache();
        a.subscribe();
        b.subscribe();
        assertThat(order).containsExactly("start a");

        grantA.tryEmitValue(client("a"));

        assertThat(b.block(WAIT).getAccessToken().getTokenValue()).isEqualTo("b");
        assertThat(order).containsExactly("start a", "end a", "start b");
    }

    @Test
    void twoSessionsRedeemInParallel() {
        AtomicInteger calls = new AtomicInteger();
        redemptions.redeem("s1", "entra-a", () -> {
            calls.incrementAndGet();
            return Mono.never();
        }).subscribe();
        redemptions.redeem("s2", "entra-a", () -> {
            calls.incrementAndGet();
            return Mono.never();
        }).subscribe();

        assertThat(calls).hasValue(2);
        assertThat(redemptions.running()).isEqualTo(2);
    }

    @Test
    void theWaitingRequestGetsTheErrorAndTheEntryIsRemoved() {
        Sinks.One<OAuth2AuthorizedClient> grant = Sinks.one();
        AtomicInteger calls = new AtomicInteger();
        Mono<OAuth2AuthorizedClient> first = redemptions.redeem("s1", "entra-a", () -> {
            calls.incrementAndGet();
            return grant.asMono();
        }).cache();
        Mono<OAuth2AuthorizedClient> second = redemptions.redeem("s1", "entra-a", () -> {
            calls.incrementAndGet();
            return Mono.just(client("x"));
        }).cache();
        first.subscribe(c -> { }, e -> { });
        second.subscribe(c -> { }, e -> { });

        grant.tryEmitError(new IllegalStateException("grant failed"));

        StepVerifier.create(first).expectErrorMessage("grant failed").verify(WAIT);
        StepVerifier.create(second).expectErrorMessage("grant failed").verify(WAIT);
        assertThat(calls).hasValue(1);
        assertThat(redemptions.running()).isZero();
    }

    @Test
    void aCancelledRequestDoesNotCancelTheRedemption() {
        Sinks.One<OAuth2AuthorizedClient> grant = Sinks.one();
        AtomicInteger cancelled = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();

        Disposable request = redemptions.redeem("s1", "entra-a", () -> grant.asMono()
                .doOnCancel(cancelled::incrementAndGet)
                .doOnNext(c -> completed.incrementAndGet())).subscribe();
        request.dispose();
        grant.tryEmitValue(client("a"));

        assertThat(cancelled).hasValue(0);
        assertThat(completed).hasValue(1);
        assertThat(redemptions.running()).isZero();
    }

    private static OAuth2AuthorizedClient client(String token) {
        Instant now = Instant.now();
        return new OAuth2AuthorizedClient(AudienceClientRegistrationsTest.entra("bff"), "oid-1",
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, token, now, now.plusSeconds(3600)));
    }
}
