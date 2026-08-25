package otto.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import otto.OttoProperties;

/**
 * The laptop's storage: one JSON file per named document under the
 * configured directory. Writes go to a temp file first and move into
 * place, so a crash mid-write never leaves a torn document.
 */
@Component
@Profile("!aws")
public class FileDocumentBackend implements DocumentBackend {

    private final Path dir;

    public FileDocumentBackend(OttoProperties properties) {
        this.dir = Path.of(properties.storageDir()).toAbsolutePath().normalize();
    }

    @Override
    public Optional<byte[]> load(String name) {
        Path file = fileFor(name);
        if (!Files.exists(file)) {
            file = legacyFileFor(name).orElse(file);
            if (!Files.exists(file)) {
                return Optional.empty();
            }
        }
        try {
            return Optional.of(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read stored document " + name, e);
        }
    }

    @Override
    public void store(String name, byte[] json) {
        Path file = fileFor(name);
        try {
            Files.createDirectories(dir);
            Path temp = Files.createTempFile(dir, "doc-" + encodedName(name), ".tmp");
            Files.write(temp, json);
            try {
                Files.move(temp, file,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write stored document " + name, e);
        }
    }

    private Path fileFor(String name) {
        return dir.resolve(encodedName(name) + ".json");
    }

    private Optional<Path> legacyFileFor(String name) {
        try {
            Path legacy = dir.resolve(name + ".json").normalize();
            return legacy.getParent().equals(dir) ? Optional.of(legacy) : Optional.empty();
        } catch (java.nio.file.InvalidPathException ignored) {
            return Optional.empty();
        }
    }

    private static String encodedName(String name) {
        return HexFormat.of().formatHex(name.getBytes(StandardCharsets.UTF_8));
    }
}
