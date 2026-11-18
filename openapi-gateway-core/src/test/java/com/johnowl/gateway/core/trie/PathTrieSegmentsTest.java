package com.johnowl.gateway.core.trie;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PathTrieSegmentsTest {

    @Test
    void normalizesPaths() {
        assertThat(PathTrie.segments("/")).isEmpty();
        assertThat(PathTrie.segments("/users")).containsExactly("users");
        assertThat(PathTrie.segments("/users/")).containsExactly("users");
        assertThat(PathTrie.segments("/users/me/orders")).containsExactly("users", "me", "orders");
    }

    @Test
    void keepsEmptySegmentsInsideThePath() {
        assertThat(PathTrie.segments("/users//42")).isEqualTo(List.of("users", "", "42"));
    }
}
