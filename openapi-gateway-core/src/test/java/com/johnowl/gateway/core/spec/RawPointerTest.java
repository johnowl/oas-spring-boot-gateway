package com.johnowl.gateway.core.spec;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class RawPointerTest {

    @Test
    void buildsTheEscapedPointerText() {
        RawPointer pointer = RawPointer.root().child("paths").child("/orders/{id}").child("get").child("a~b")
                .child(3);

        assertThat(RawPointer.root()).hasToString("");
        assertThat(pointer).hasToString("/paths/~1orders~1{id}/get/a~0b/3");
    }

    // SEC-69-1: a child holds its parent, not a copy of the parent text.
    @Test
    void aChildDoesNotCopyTheTextOfItsParent() {
        RawPointer parent = RawPointer.root().child("k".repeat(1_000_000));

        RawPointer child = parent.child(0);

        assertThat(child.parent()).isSameAs(parent);
        assertThat(child.toString()).hasSize(1 + 1_000_000 + 2);
    }

    // With pointer texts, 200,000 children of a key of 1,000,000 characters copy 200 billion characters.
    @Test
    @Timeout(10)
    void manyChildrenOfALongKeyAreCheap() {
        RawPointer parent = RawPointer.root().child("k".repeat(1_000_000));

        RawPointer last = parent;
        for (int i = 0; i < 200_000; i++) {
            last = parent.child(i);
        }

        assertThat(last.parent()).isSameAs(parent);
    }
}
