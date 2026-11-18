package com.johnowl.gateway.core.spec;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks every reference before swagger-parser resolves it. The loader never accesses the network and never
 * reads a file outside the directory of the root OAS file, also through references in referenced files.
 *
 * <p>A reference is the value of a {@code $ref} field or of a {@code discriminator.mapping} entry.
 */
final class ReferenceGuard {

    private static final Pattern SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:");
    private static final Pattern LOCAL_NAME = Pattern.compile("[^./\\\\:%]*");

    /** A forbidden reference. The pointer is the pointer of the reference in the root file. */
    record Problem(String pointer, String message) {
    }

    /** A reference. The walk builds the text of the pointer only for a reported reference (SEC-69-1). */
    private record Reference(RawPointer pointer, String value) {
    }

    /**
     * A document that the loader reads: the root document, or a file that a reference reaches.
     *
     * @param raw the raw tree
     * @param name the file name relative to the directory of the root file, or {@code null} for the root document
     * @param rootPointer the pointer of the reference in the root file that leads to this file, or {@code null} for
     *     the root document
     */
    record Document(Path file, JsonNode raw, String name, String rootPointer) {
    }

    /** The forbidden references, and each document that the guard read, the root document first. */
    record Result(List<Problem> problems, List<Document> documents) {
    }

    private ReferenceGuard() {
    }

    /**
     * Returns the forbidden references of a document, and the documents that the guard read. A referenced file is
     * in the result only if its references are allowed so far and it is inside the directory of the root file.
     *
     * @param raw the raw tree of the root document
     * @param rootFile the root file, or {@code null} if the content does not come from a file. Then only local
     *     references ({@code #/...}) are allowed.
     */
    static Result check(JsonNode raw, Path rootFile) throws IOException {
        List<Problem> problems = new ArrayList<>();
        List<Document> documents = new ArrayList<>();
        if (rootFile == null) {
            documents.add(new Document(null, raw, null, null));
            for (Reference reference : references(raw)) {
                if (!isLocal(reference.value())) {
                    problems.add(new Problem(reference.pointer().toString(), notAllowed(reference.value(),
                            "only local references (#/...) are allowed in content that does not come from a file")));
                }
            }
            return new Result(problems, documents);
        }

        Path root = rootFile.toRealPath();
        Path rootDirectory = root.getParent();
        Set<Path> visited = new HashSet<>(Set.of(root));
        Deque<Document> queue = new ArrayDeque<>();
        queue.add(new Document(root, raw, null, null));
        while (!queue.isEmpty()) {
            Document document = queue.removeFirst();
            documents.add(document);
            for (Reference reference : references(document.raw())) {
                String reason = checkFileReference(reference.value(), document.file().getParent(), rootDirectory);
                if (reason != null) {
                    problems.add(new Problem(rootPointer(document, reference), in(document)
                            + notAllowed(reference.value(), reason)));
                    continue;
                }
                Path target = target(reference.value(), document.file().getParent());
                if (target == null || !Files.isRegularFile(target)) {
                    continue; // Local, or missing: swagger-parser reports the missing file.
                }
                String pointer = rootPointer(document, reference);
                Path real = target.toRealPath();
                if (!real.startsWith(rootDirectory)) {
                    problems.add(new Problem(pointer, in(document) + notAllowed(reference.value(),
                            "the file is outside the directory of the OAS file")));
                } else if (visited.add(real)) {
                    try {
                        String content = RawDocuments.read(real);
                        queue.add(new Document(real, RawDocuments.parse(real.toString(), content),
                                rootDirectory.relativize(real).toString(), pointer));
                    } catch (RawDocuments.InvalidDocumentException e) {
                        problems.add(new Problem(pointer, "Referenced file '" + rootDirectory.relativize(real)
                                + "': " + e.getMessage()));
                    }
                }
            }
        }
        return new Result(problems, documents);
    }

    /** The pointer of the reference in the root file, or of the reference in the root file that leads to the file. */
    private static String rootPointer(Document document, Reference reference) {
        return document.rootPointer() == null ? reference.pointer().toString() : document.rootPointer();
    }

    /** Returns the reason why the reference is not allowed, or {@code null} if it is allowed. */
    private static String checkFileReference(String value, Path directory, Path rootDirectory) {
        if (isLocal(value)) {
            return null;
        }
        String file = filePart(value);
        if (SCHEME.matcher(file).find()) {
            return "remote references and URLs are not allowed";
        }
        if (file.startsWith("/") || file.contains("\\") || file.contains("%")) {
            return "only relative paths without backslashes and percent-encoding are allowed";
        }
        if (!directory.resolve(file).normalize().startsWith(rootDirectory)) {
            return "the file is outside the directory of the OAS file";
        }
        return null;
    }

    private static Path target(String value, Path directory) {
        return isLocal(value) ? null : directory.resolve(filePart(value)).normalize();
    }

    /**
     * A local reference is a JSON pointer in the same document ({@code #/...}) or a component name without
     * {@code .}, {@code /}, {@code \}, {@code :} and {@code %}, for example {@code Pet}. swagger-parser reads such a
     * name as {@code #/components/schemas/Pet}.
     */
    static boolean isLocal(String value) {
        return LOCAL_NAME.matcher(filePart(value)).matches();
    }

    private static String filePart(String value) {
        int hash = value.indexOf('#');
        return hash < 0 ? value : value.substring(0, hash);
    }

    private static String notAllowed(String value, String reason) {
        return "Reference '" + value + "' is not allowed: " + reason;
    }

    private static String in(Document document) {
        return document.name() == null ? "" : "In referenced file '" + document.name() + "': ";
    }

    private static List<Reference> references(JsonNode raw) {
        List<Reference> references = new ArrayList<>();
        collect(raw, RawPointer.root(), references);
        return references;
    }

    private static void collect(JsonNode node, RawPointer pointer, List<Reference> references) {
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collect(node.get(i), pointer.child(i), references);
            }
            return;
        }
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            RawPointer fieldPointer = pointer.child(field.getKey());
            JsonNode value = field.getValue();
            if (field.getKey().equals("$ref") && value.isTextual()) {
                references.add(new Reference(fieldPointer, value.asText()));
            }
            if (field.getKey().equals("discriminator") && value.path("mapping").isObject()) {
                for (Map.Entry<String, JsonNode> entry : value.get("mapping").properties()) {
                    if (entry.getValue().isTextual()) {
                        references.add(new Reference(fieldPointer.child("mapping").child(entry.getKey()),
                                entry.getValue().asText()));
                    }
                }
            }
            collect(value, fieldPointer, references);
        }
    }
}
