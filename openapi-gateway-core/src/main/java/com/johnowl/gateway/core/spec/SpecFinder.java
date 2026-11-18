package com.johnowl.gateway.core.spec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/** Expands files and directories into a sorted list of OAS files. */
public final class SpecFinder {

    private static final List<String> EXTENSIONS = List.of(".yaml", ".yml", ".json");

    /**
     * Returns each file argument as it is, and the OAS files of each directory argument,
     * recursively. The directory search skips symbolic links, so that a link in a reviewed directory
     * cannot make the CLI read a file outside it. The result is sorted by normalized path and contains each file once, also if two
     * arguments name the same file in different ways (relative and absolute, or through a symbolic link).
     *
     * @throws NoSuchFileException if an argument does not exist
     * @throws IOException if a directory cannot be read
     */
    public List<Path> find(List<Path> filesOrDirectories) throws IOException {
        List<Path> found = new ArrayList<>();
        for (Path argument : filesOrDirectories) {
            Path normalized = argument.normalize();
            if (Files.isDirectory(normalized)) {
                try (Stream<Path> files = Files.walk(normalized)) {
                    files.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                            .filter(SpecFinder::hasOasExtension)
                            .map(Path::normalize)
                            .forEach(found::add);
                } catch (UncheckedIOException e) {
                    throw e.getCause();
                }
            } else if (Files.exists(normalized)) {
                found.add(normalized);
            } else {
                throw new NoSuchFileException(argument.toString());
            }
        }

        Set<Path> result = new TreeSet<>();
        Set<Path> realPaths = new HashSet<>();
        for (Path file : found.stream().sorted().toList()) {
            if (realPaths.add(file.toRealPath())) {
                result.add(file);
            }
        }
        return List.copyOf(result);
    }

    private static boolean hasOasExtension(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return EXTENSIONS.stream().anyMatch(name::endsWith);
    }
}
