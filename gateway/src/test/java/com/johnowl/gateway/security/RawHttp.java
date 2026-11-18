package com.johnowl.gateway.security;

import com.johnowl.gateway.Loopback;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Raw HTTP/1.1 on a real connection to the gateway, so that forged {@code Host}, {@code Forwarded}, and
 * {@code X-Forwarded-*} headers reach the HTTP server unchanged. A mock-bound {@code WebTestClient} skips
 * the HTTP server (spec 025, QA-47-15).
 */
final class RawHttp {

    private RawHttp() {
    }

    /**
     * Sends the request to {@code 127.0.0.1}, so that the connection peer of the gateway is {@code 127.0.0.1}.
     *
     * @param request the request line and the headers, each line with {@code \r\n}, and an empty last line
     * @return the status line and the headers of the response
     */
    static String send(int port, String request) throws IOException {
        try (Socket socket = new Socket(Loopback.ADDRESS, port)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) != -1) {
                head.write(b);
                if (head.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
                    break;
                }
            }
            return head.toString(StandardCharsets.US_ASCII);
        }
    }

    /** The first value of a response header, or {@code null}. */
    static String header(String response, String name) {
        return response.lines()
                .filter(line -> line.regionMatches(true, 0, name + ":", 0, name.length() + 1))
                .findFirst()
                .map(line -> line.substring(name.length() + 1).trim())
                .orElse(null);
    }
}
