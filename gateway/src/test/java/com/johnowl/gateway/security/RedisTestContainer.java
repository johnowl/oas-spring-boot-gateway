package com.johnowl.gateway.security;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One Redis container for each test JVM. {@link RedisTestContainerContextCustomizerFactory} gives
 * its address to every Spring Boot test. The gateway connects with the ACL user of spec 022
 * ("Redis ACL"): only the keys {@code spring:session:*}, no channels, and a command allowlist. The
 * tests check Redis with the default user ({@link #admin()}).
 */
final class RedisTestContainer {

    static final String USER = "gateway";

    static final String PASSWORD = "test-only-redis-password";

    /** The command allowlist of spec 022, "Redis ACL". */
    static final List<String> ALLOWED_COMMANDS = List.of(
            "+auth", "+hello", "+ping", "+select", "+client|setname", "+client|setinfo",
            "+hgetall", "+hget", "+hset", "+hmset", "+hdel", "+del", "+exists", "+expire", "+pexpire", "+rename",
            "+set", "+get", "+incr", "+evalsha", "+script|load");

    private static GenericContainer<?> container;

    private static RedisCommands<String, byte[]> admin;

    private RedisTestContainer() {
    }

    /** The host of the container. Starts the container if needed. */
    static String host() {
        return container().getHost();
    }

    /** The Redis port of the container. Starts the container if needed. */
    static int port() {
        return container().getMappedPort(6379);
    }

    /** A client with the default user, for checks. Values are bytes, keys are strings. */
    static synchronized RedisCommands<String, byte[]> admin() {
        if (admin == null) {
            GenericContainer<?> redis = container();
            admin = RedisClient.create("redis://" + redis.getHost() + ":" + redis.getMappedPort(6379))
                    .connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE)).sync();
        }
        return admin;
    }

    /** The session keys in Redis. */
    static List<String> sessionKeys() {
        return admin().keys("spring:session:sessions:*");
    }

    /**
     * Checks if the ACL user of the gateway may run a command, without running it ({@code ACL DRYRUN}).
     *
     * @return {@code OK}, or the reason of the denial
     */
    static String dryRun(String... command) {
        List<String> args = new ArrayList<>(List.of("redis-cli", "ACL", "DRYRUN", USER));
        args.addAll(List.of(command));
        try {
            Container.ExecResult result = container().execInContainer(args.toArray(String[]::new));
            return (result.getStdout() + result.getStderr()).trim();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static synchronized GenericContainer<?> container() {
        if (container == null) {
            // At least 8.2.2, the version with the fixes of the script engine (spec 022).
            GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:8.2.2")).withExposedPorts(6379);
            redis.start();
            List<String> acl = new ArrayList<>(List.of("redis-cli", "ACL", "SETUSER", USER, "on", ">" + PASSWORD,
                    "resetchannels", "~spring:session:*", "-@all"));
            acl.addAll(ALLOWED_COMMANDS);
            try {
                Container.ExecResult result = redis.execInContainer(acl.toArray(String[]::new));
                if (!result.getStdout().trim().equals("OK")) {
                    throw new IllegalStateException("ACL SETUSER failed: " + result.getStdout() + result.getStderr());
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            container = redis;
        }
        return container;
    }
}
