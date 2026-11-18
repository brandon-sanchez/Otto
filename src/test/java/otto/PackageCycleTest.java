package otto;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class PackageCycleTest {

    private static final Path SOURCES = Path.of("src/main/java/otto");

    /** The packages whose references to each other must stay one-way. */
    private static final List<String> LAYERED = List.of(
            "alerts", "ask", "aws", "board", "claims", "delivery", "followed", "records",
            "scope", "sleeper", "telegram", "waivers", "webhook");

    /** A reference to a type in another otto package, by import or by full name. */
    private static final Pattern REFERENCE = Pattern.compile("\\botto\\.([a-z]+)\\.[A-Z]");

    @Test
    void theLayeredPackagesReferenceEachOtherOneWayOnly() {
        Map<String, Set<String>> references = new TreeMap<>();
        LAYERED.forEach(pkg -> references.put(pkg, referencedLayeredPackages(pkg)));

        assertThat(cycle(references)).as("a package cycle").isEmpty();
    }

    @Test
    void aCycleIsFound() {
        Map<String, Set<String>> references = Map.of(
                "a", Set.of("b"), "b", Set.of("c"), "c", Set.of("a"));

        assertThat(cycle(references)).hasValueSatisfying(
                found -> assertThat(found).containsExactly("a", "b", "c", "a"));
    }

    private static Set<String> referencedLayeredPackages(String pkg) {
        Set<String> referenced = new LinkedHashSet<>();
        try (Stream<Path> files = Files.list(SOURCES.resolve(pkg))) {
            files.filter(file -> file.toString().endsWith(".java"))
                    .map(PackageCycleTest::read)
                    .forEach(source -> {
                        Matcher matcher = REFERENCE.matcher(source);
                        while (matcher.find()) {
                            referenced.add(matcher.group(1));
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        referenced.remove(pkg);
        referenced.retainAll(LAYERED);
        return referenced;
    }

    private static Optional<List<String>> cycle(Map<String, Set<String>> references) {
        for (String start : new TreeMap<>(references).keySet()) {
            Optional<List<String>> found = cycleFrom(start, new ArrayList<>(List.of(start)),
                    references);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private static Optional<List<String>> cycleFrom(String at, List<String> path,
            Map<String, Set<String>> references) {
        for (String next : references.getOrDefault(at, Set.of()).stream().sorted().toList()) {
            if (next.equals(path.getFirst())) {
                List<String> cycle = new ArrayList<>(path);
                cycle.add(next);
                return Optional.of(cycle);
            }
            if (!path.contains(next)) {
                path.add(next);
                Optional<List<String>> found = cycleFrom(next, path, references);
                if (found.isPresent()) {
                    return found;
                }
                path.removeLast();
            }
        }
        return Optional.empty();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
