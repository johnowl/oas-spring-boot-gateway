package com.johnowl.gateway.core.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Set;
import org.junit.jupiter.api.Test;

class AccessRuleTest {

    private static final String AUDIENCE = "6f1c2e8a-0000-0000-0000-000000000000";

    @Test
    void publicRuleHasNoAudienceAndNoPermissions() {
        assertThatIllegalArgumentException().isThrownBy(() -> new AccessRule(true, AUDIENCE, Set.of(), Set.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new AccessRule(true, null, Set.of("A.X"), Set.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new AccessRule(true, null, Set.of(), Set.of("A.X")));
        assertThat(AccessRule.publicAccess().allows(Set.of())).isTrue();
    }

    @Test
    void allOfNeedsEveryPermission() {
        AccessRule rule = new AccessRule(false, AUDIENCE, Set.of("A.X", "A.Y"), Set.of());

        assertThat(rule.allows(Set.of("A.X", "A.Y"))).isTrue();
        assertThat(rule.allows(Set.of("A.X", "A.Y", "B.Z"))).isTrue();
        assertThat(rule.allows(Set.of("A.X"))).isFalse();
        assertThat(rule.allows(Set.of())).isFalse();
    }

    @Test
    void anyOfNeedsOnePermission() {
        AccessRule rule = new AccessRule(false, AUDIENCE, Set.of(), Set.of("A.X", "B.Y"));

        assertThat(rule.allows(Set.of("B.Y"))).isTrue();
        assertThat(rule.allows(Set.of("A.X"))).isTrue();
        assertThat(rule.allows(Set.of("C.Z"))).isFalse();
    }

    @Test
    void allOfAndAnyOfMustBothHold() {
        AccessRule rule = new AccessRule(false, AUDIENCE, Set.of("A.X"), Set.of("B.Y", "B.Z"));

        assertThat(rule.allows(Set.of("A.X"))).isFalse();
        assertThat(rule.allows(Set.of("B.Z"))).isFalse();
        assertThat(rule.allows(Set.of("A.X", "B.Z"))).isTrue();
    }

    @Test
    void authenticatedOnlyAllowsEmptyPermissions() {
        assertThat(new AccessRule(false, AUDIENCE, Set.of(), Set.of()).allows(Set.of())).isTrue();
    }
}
