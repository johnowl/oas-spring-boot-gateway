package com.johnowl.gateway.core.spec;

import com.johnowl.gateway.core.validation.JsonPointers;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The JSON pointer of a node in a raw tree. A pointer holds its parent and its own reference token only. The
 * loader builds the text only when it reports a node (SEC-69-1). A walk that built the text of every node copied
 * each key once for each node below it: a long key and many nodes took minutes.
 */
final class RawPointer {

    private static final RawPointer ROOT = new RawPointer(null, null);

    private final RawPointer parent;
    private final String token;

    private RawPointer(RawPointer parent, String token) {
        this.parent = parent;
        this.token = token;
    }

    /** The pointer of the document root, the empty text. */
    static RawPointer root() {
        return ROOT;
    }

    /** The pointer of the value of a key of this mapping. The key is escaped when the text is built. */
    RawPointer child(String key) {
        return new RawPointer(this, key);
    }

    /** The pointer of an item of this sequence. */
    RawPointer child(int index) {
        return new RawPointer(this, Integer.toString(index));
    }

    RawPointer parent() {
        return parent;
    }

    /** The JSON pointer text, for example {@code /paths/~1orders/get}. Builds the text each time, without recursion. */
    @Override
    public String toString() {
        Deque<String> tokens = new ArrayDeque<>();
        for (RawPointer pointer = this; pointer.parent != null; pointer = pointer.parent) {
            tokens.push(pointer.token);
        }
        StringBuilder text = new StringBuilder();
        for (String token : tokens) {
            text.append('/').append(JsonPointers.escape(token));
        }
        return text.toString();
    }
}
