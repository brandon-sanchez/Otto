package otto.storage;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import otto.OttoProperties;

import static org.assertj.core.api.Assertions.assertThat;

class FileDocumentBackendTest {

    @TempDir
    private Path directory;

    @Test
    void documentNamesRemainDistinctAcrossFilesystems() {
        FileDocumentBackend backend = new FileDocumentBackend(properties(directory));

        backend.store("alert:a:b", new byte[] { 1 });
        backend.store("alert:a?b", new byte[] { 2 });

        assertThat(backend.load("alert:a:b").orElseThrow()).containsExactly(1);
        assertThat(backend.load("alert:a?b").orElseThrow()).containsExactly(2);
    }

    @Test
    void documentNamesRemainDistinctOnCaseInsensitiveFilesystems() {
        FileDocumentBackend backend = new FileDocumentBackend(properties(directory));

        backend.store("00@", new byte[] { 1 });
        backend.store("00Z", new byte[] { 2 });

        assertThat(backend.load("00@").orElseThrow()).containsExactly(1);
        assertThat(backend.load("00Z").orElseThrow()).containsExactly(2);
    }

    private static OttoProperties properties(Path storageDirectory) {
        return new OttoProperties(null, null, storageDirectory.toString(), null, null, null, null,
                null, null, null, 0.0, null, null);
    }
}
