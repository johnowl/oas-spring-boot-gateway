package com.johnowl.gateway;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A free local port, for a test that needs a fixed port before the application starts. The gateway
 * does not start with {@code management.server.port=0} (spec 024, "Management port").
 *
 * <p>The port is below the ephemeral ranges of Linux (from 32768) and macOS (from 49152). A server that
 * binds port {@code 0}, for example a mock backend, therefore never gets the same port between the
 * choice and the bind of the gateway.
 *
 * <p>The check binds {@link Loopback#ADDRESS} with {@code SO_REUSEADDR}, as Reactor Netty does, and the
 * server must bind the same address. If another process takes the port between the check and the bind,
 * the bind of the server fails: the test never talks to the other process (see {@link Loopback}).
 */
public final class FreePort {

    private static final int FIRST = 10_000;

    private static final int LAST = 30_000;

    private FreePort() {
    }

    /** A port that is free on {@link Loopback#ADDRESS} now. */
    public static int next() {
        for (int attempt = 0; attempt < 100; attempt++) {
            int port = ThreadLocalRandom.current().nextInt(FIRST, LAST);
            try (ServerSocket socket = new ServerSocket()) {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(Loopback.ADDRESS, port));
                return port;
            } catch (IOException e) {
                // In use: try another port.
            }
        }
        throw new IllegalStateException("No free local port between " + FIRST + " and " + LAST);
    }
}
