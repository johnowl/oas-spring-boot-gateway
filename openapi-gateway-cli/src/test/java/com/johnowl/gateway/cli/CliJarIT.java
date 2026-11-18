package com.johnowl.gateway.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the shaded jar as a separate process, as a pipeline does. */
class CliJarIT {

    private static final Path JAR = Path.of("target/oas-gateway-validate.jar");

    @Test
    void printsVersion() throws Exception {
        Process process = java("--version");

        assertThat(process.exitValue()).isZero();
        assertThat(output(process)).startsWith("oas-gateway-validate " + System.getProperty("project.version"));
    }

    @Test
    void returnsOneForErrorViolation() throws Exception {
        Process process = java("src/test/resources/cli/error");

        assertThat(process.exitValue()).isEqualTo(1);
        assertThat(output(process)).contains("ERROR x-gateway-schema");
    }

    /** The examples of explain are resources of the core jar, so the shaded jar must contain them (spec 032). */
    @Test
    void explainsARuleWithTheExamplesInTheJar() throws Exception {
        Process process = java("explain", "path-no-verbs");

        assertThat(process.exitValue()).isZero();
        assertThat(output(process)).contains("Bad example\n  paths:\n", "Good example\n  paths:\n");
    }

    /**
     * QA-71-3: {@code ./rules}, {@code ./explain}, {@code ./diff} and {@code rules/} are directories to validate, not
     * subcommands. {@code rules} alone returns 2, because a directory with this name exists (SEC-71-1).
     */
    @Test
    void validatesDirectoriesNamedLikeASubcommand(@TempDir Path workingDirectory) throws Exception {
        for (String name : List.of("rules", "explain", "diff")) {
            Files.createDirectories(workingDirectory.resolve(name));
            Files.copy(Path.of("src/test/resources/cli/error/orders.yaml"),
                    workingDirectory.resolve(name).resolve("orders.yaml"));
        }

        for (String path : List.of("./rules", "./explain", "./diff", "rules/")) {
            Process process = java(workingDirectory, path);

            String output = output(process);
            assertThat(process.exitValue()).as(path).isEqualTo(1);
            assertThat(output).as(path).contains("ERROR x-gateway-schema");
        }
        Process rules = java(workingDirectory, "rules");
        assertThat(rules.exitValue()).isEqualTo(2);
        assertThat(output(rules)).isEqualTo("Error: A path named 'rules' exists. Write ./rules to validate it, or run"
                + " the command from another directory." + System.lineSeparator());
    }

    /** spec 034: the access diff of the jar returns 1 for a loosening change. */
    @Test
    void diffReturnsOneForALooseningChange() throws Exception {
        Process process = java("diff", "src/test/resources/diff/loosening/base", "src/test/resources/diff/loosening/head");

        assertThat(process.exitValue()).isEqualTo(1);
        assertThat(output(process)).contains("LOOSENING became-public GET /orders-api/orders/{id}",
                "8 access loosening changes in 6 operations.");
    }

    /** The jar has no Spring, no Reactor, no Netty, and no Azure SDK. */
    @Test
    void jarContainsNoSpringCloudGatewayOrWebFlux() throws IOException {
        try (JarFile jar = new JarFile(JAR.toFile())) {
            assertThat(jar.stream().map(entry -> entry.getName()))
                    .noneMatch(name -> name.startsWith("org/springframework/"))
                    .noneMatch(name -> name.startsWith("reactor/"))
                    .noneMatch(name -> name.startsWith("io/netty/"))
                    .noneMatch(name -> name.startsWith("com/azure/"));
        }
    }

    private static Process java(String... args) throws Exception {
        return java(Path.of(""), args);
    }

    private static Process java(Path workingDirectory, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java")
                .toString(), "-jar", JAR.toAbsolutePath().toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(workingDirectory.toAbsolutePath().toFile())
                .redirectErrorStream(true).start();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return process;
    }

    private static String output(Process process) throws IOException {
        return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
