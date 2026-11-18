package com.johnowl.gateway.core.trie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PathTrieMatchTest {

    private final PathTrie<String> trie = new PathTrie<>();

    @BeforeEach
    void insertExample() {
        trie.insert("/users/me", Set.of("GET"), "/users/me");
        trie.insert("/users/{id}", Set.of("GET", "DELETE"), "/users/{id}");
        trie.insert("/users/me/settings", Set.of("GET"), "/users/me/settings");
    }

    @ParameterizedTest
    @CsvSource(nullValues = "empty", value = {
        "GET,    /users/me,           /users/me",
        "GET,    /users/42,           /users/{id}",
        "DELETE, /users/me,           /users/{id}",
        "GET,    /users/42/settings,  empty",
        "GET,    /users/me/settings/, /users/me/settings",
        "GET,    /orders,             empty",
        "POST,   /users/42,           empty",
        "GET,    /users,              empty",
        "GET,    /users/,             empty"
    })
    void matchesTheExampleTable(String method, String path, String expected) {
        assertThat(match(method, path)).isEqualTo(Optional.ofNullable(expected));
    }

    @Test
    void matchesRootAndDeepPaths() {
        trie.insert("/", Set.of("GET"), "root");
        trie.insert("/a/{b}/c/{d}/e/f", Set.of("PUT"), "deep");

        assertThat(match("GET", "/")).contains("root");
        assertThat(match("PUT", "/a/1/c/2/e/f/")).contains("deep");
    }

    @Test
    void trailingSlashOnPatternAndRequestIsTheSame() {
        trie.insert("/orders/", Set.of("GET"), "orders");

        assertThat(match("GET", "/orders")).contains("orders");
        assertThat(match("GET", "/orders/")).contains("orders");
    }

    @Test
    void literalComparisonIsCaseSensitive() {
        assertThat(match("GET", "/USERS/me")).isEmpty();
    }

    // SEC-59-1: a backend that ignores case serves /users/me, so the rule of /users/{id} must not apply.
    @ParameterizedTest
    @ValueSource(strings = {"/users/ME", "/users/Me", "/users/mE", "/users/ME/settings"})
    void caseVariantOfALiteralSiblingDoesNotFallToTheVariable(String path) {
        assertThat(match("GET", path)).isEmpty();
        assertThat(match("DELETE", path)).isEmpty();
    }

    @Test
    void caseVariantWithCharactersOutsideAsciiDoesNotFallToTheVariable() {
        trie.insert("/users/{id}/keys/kid", Set.of("GET"), "kid");
        trie.insert("/users/{id}/keys/{key}", Set.of("GET"), "key");

        // U+212A KELVIN SIGN and U+0131 LATIN SMALL LETTER DOTLESS I fold to k and i.
        assertThat(match("GET", "/users/42/keys/\u212Aid")).isEmpty();
        assertThat(match("GET", "/users/42/keys/k\u0131d")).isEmpty();
        assertThat(match("GET", "/users/42/keys/kid")).contains("kid");
        assertThat(match("GET", "/users/42/keys/kit")).contains("key");
    }

    @Test
    void caseVariantWithoutAVariableSiblingIsNoMatch() {
        trie.insert("/files/report", Set.of("GET"), "report");

        assertThat(match("GET", "/files/REPORT")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/files/{*path}", "/users/{id:\\d+}", "/files/**", "/files/*", "/files/{name}.json", "/a/{}", "/a/{x"})
    void rejectsUnsupportedSyntax(String pattern) {
        assertThatIllegalArgumentException().isThrownBy(() -> trie.insert(pattern, Set.of("GET"), "x"));
    }

    @Test
    void rejectsSameNormalizedPatternAndMethod() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> trie.insert("/users/{userId}", Set.of("GET"), "duplicate"))
                .withMessageContaining("already has a value for GET");
    }

    @Test
    void failedInsertStoresNoValueForAnyMethod() {
        // PATCH comes first and is new; GET is a duplicate. The insert must not store PATCH.
        assertThatIllegalArgumentException().isThrownBy(
                () -> trie.insert("/users/{userId}", new LinkedHashSet<>(List.of("PATCH", "GET")), "duplicate"));

        assertThat(match("PATCH", "/users/1")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/a/b/{x:1}", "/orders/new/{id}/{*rest}", "/users/{userId}"})
    void failedInsertCreatesNoNode(String pattern) {
        int nodes = trie.nodeCount();

        assertThatIllegalArgumentException().isThrownBy(() -> trie.insert(pattern, Set.of("GET"), "x"));

        assertThat(trie.nodeCount()).isEqualTo(nodes);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/users//", "/users//me", "/users/.", "/users/..", "/users/../me", "/users/./settings"})
    void pathWithEmptyOrDotSegmentNeverMatches(String path) {
        trie.insert("/users/{id}/{name}", Set.of("GET"), "/users/{id}/{name}");

        assertThat(match("GET", path)).isEmpty();
    }

    @Test
    void acceptsSamePatternWithOtherMethod() {
        trie.insert("/users/{userId}", Set.of("PATCH"), "patch");

        assertThat(match("PATCH", "/users/1")).contains("patch");
    }

    private Optional<String> match(String method, String path) {
        return trie.match(PathTrie.segments(path), method);
    }
}
