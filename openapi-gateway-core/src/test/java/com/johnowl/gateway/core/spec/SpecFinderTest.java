package com.johnowl.gateway.core.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpecFinderTest {

    private final SpecFinder finder = new SpecFinder();

    @TempDir
    Path dir;

    @Test
    void findsOasFilesRecursivelyInSortedOrder() throws IOException {
        Path b = touch("b.yaml");
        Path a = touch("a.json");
        Path nested = touch("nested/deeper/c.yml");

        assertThat(finder.find(List.of(dir))).containsExactly(a, b, nested);
    }

    @Test
    void ignoresOtherExtensionsInDirectories() throws IOException {
        Path spec = touch("orders.yaml");
        touch("README.md");
        touch("notes.txt");

        assertThat(finder.find(List.of(dir))).containsExactly(spec);
    }

    @Test
    void returnsFileArgumentsWithAnyExtension() throws IOException {
        Path file = touch("orders.openapi");

        assertThat(finder.find(List.of(file))).containsExactly(file);
    }

    @Test
    void returnsEachFileOnce() throws IOException {
        Path spec = touch("specs/orders.yaml");

        assertThat(finder.find(List.of(dir, spec, dir.resolve("specs/../specs/orders.yaml"))))
                .containsExactly(spec);
    }

    @Test
    void returnsFileOnceForRelativeAndAbsoluteArguments() throws IOException {
        Path relative = Path.of("pom.xml");

        assertThat(finder.find(List.of(relative, relative.toAbsolutePath()))).hasSize(1);
    }

    @Test
    void returnsFileOnceForSymbolicLink() throws IOException {
        Path spec = touch("orders.yaml");
        Path link = Files.createSymbolicLink(dir.resolve("link.yaml"), spec);

        assertThat(finder.find(List.of(link, spec))).containsExactly(link);
    }

    @Test
    void directorySearchSkipsSymbolicLinks(@TempDir Path outside) throws IOException {
        Path spec = touch("openapi/orders.yaml");
        Path secret = Files.writeString(outside.resolve("token"), "secret");
        Files.createDirectories(outside.resolve("more"));
        Files.writeString(outside.resolve("more/other.yaml"), "openapi: 3.0.3");
        Files.createSymbolicLink(dir.resolve("openapi/leak.json"), secret);
        Files.createSymbolicLink(dir.resolve("openapi/linked-dir"), outside.resolve("more"));

        assertThat(finder.find(List.of(dir.resolve("openapi")))).containsExactly(spec);
    }

    @Test
    void throwsIOExceptionForUnreadableDirectory() throws IOException {
        touch("locked/orders.yaml");
        Path locked = dir.resolve("locked");
        assumeTrue(locked.toFile().setReadable(false) && !Files.isReadable(locked), "cannot remove read permission");
        try {
            assertThatThrownBy(() -> finder.find(List.of(dir))).isInstanceOf(AccessDeniedException.class);
        } finally {
            locked.toFile().setReadable(true);
        }
    }

    @Test
    void throwsForMissingPath() {
        Path missing = dir.resolve("does-not-exist");

        assertThatThrownBy(() -> finder.find(List.of(missing)))
                .isInstanceOf(NoSuchFileException.class)
                .hasMessageContaining("does-not-exist");
    }

    private Path touch(String relative) throws IOException {
        Path file = dir.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, "");
    }
}
