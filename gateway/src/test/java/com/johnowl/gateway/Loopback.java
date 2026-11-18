package com.johnowl.gateway;

/**
 * The address of every server and client of the tests: {@code 127.0.0.1}, never the wildcard address and never
 * {@code localhost}.
 *
 * <p>Reactor Netty binds a server socket with {@code SO_REUSEADDR}. On macOS, a socket with this option can bind
 * the wildcard address on a port that another process already uses on {@code 127.0.0.1}. A connection to
 * {@code 127.0.0.1} or {@code localhost} on that port then reaches the other process, because its address is more
 * specific. A test then got an answer of another program on the machine: {@code 426 Upgrade Required} from a local
 * WebSocket server, and {@code 404} from IntelliJ IDEA. A bind to {@code 127.0.0.1} fails if another process
 * already uses {@code 127.0.0.1} on that port, so with port {@code 0} the server always gets a port of its own.
 * {@code localhost} can resolve to {@code ::1} first, where no test server listens.
 */
public final class Loopback {

    public static final String ADDRESS = "127.0.0.1";

    private Loopback() {
    }

    /** The HTTP URL of a test server on this port, without a path. */
    public static String url(int port) {
        return "http://" + ADDRESS + ":" + port;
    }

    /**
     * The arguments that bind the gateway and its management server to {@link #ADDRESS}, for a test that runs the
     * application itself.
     */
    public static String[] serverAddressArguments() {
        return new String[] {"--server.address=" + ADDRESS, "--management.server.address=" + ADDRESS};
    }
}
