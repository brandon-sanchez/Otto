package otto.storage;

import java.nio.file.Files;
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

    @Test
    void aDeletedDocumentReadsEmpty() {
        JsonStore store = new JsonStore(new FileDocumentBackend(properties(directory)));
        store.write("account-deletion-request", "open");

        store.delete("account-deletion-request");
        store.delete("never-stored");

        assertThat(store.read("account-deletion-request", String.class)).isEmpty();
    }

    @Test
    void deletingADocumentRemovesTheCopyStoredUnderItsOldFileName() throws Exception {
        Files.writeString(directory.resolve("settings.json"), "\"old\"");
        JsonStore store = new JsonStore(new FileDocumentBackend(properties(directory)));

        store.delete("settings");

        assertThat(store.read("settings", String.class)).isEmpty();
    }

    private static OttoProperties properties(Path storageDirectory) {
        return new OttoProperties(null, null, storageDirectory.toString(), null, null, null, null,
                null,
                null, null, null, 0.0, null, null);
    }
}
