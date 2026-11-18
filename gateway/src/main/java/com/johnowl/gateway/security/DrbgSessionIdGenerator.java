package com.johnowl.gateway.security;

import java.security.DrbgParameters;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.SecureRandomParameters;
import java.util.Base64;
import org.springframework.session.SessionIdGenerator;

/**
 * Creates session ids with 256 random bits, without I/O for each id. The default generator of
 * Spring Session uses {@code UUID.randomUUID()}, and its {@code SecureRandom} can read
 * {@code /dev/urandom}. Spring Session calls the generator on the event loop when it changes the
 * session id at login and at logout (found by BlockHound).
 *
 * <p>The {@code DRBG} generator of the JDK (NIST SP 800-90A) takes its seed once, here, at
 * startup. After that it does not read entropy for each id. The generator asks for a security
 * strength of 256 bits, the same as the length of the ids. The default strength of the JDK is 128
 * bits.
 */
final class DrbgSessionIdGenerator implements SessionIdGenerator {

    private static final int BYTES = 32;

    private static final int STRENGTH_BITS = 256;

    private final SecureRandom random;

    DrbgSessionIdGenerator() {
        try {
            random = SecureRandom.getInstance("DRBG",
                    DrbgParameters.instantiation(STRENGTH_BITS, DrbgParameters.Capability.RESEED_ONLY, null));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("The JDK has no DRBG SecureRandom with a strength of 256 bits", e);
        }
        // Takes the seed now, on the startup thread.
        random.nextBytes(new byte[BYTES]);
    }

    /** The instantiation parameters of the generator, for the tests. */
    SecureRandomParameters parameters() {
        return random.getParameters();
    }

    @Override
    public String generate() {
        byte[] bytes = new byte[BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
