package com.xy.generation.file;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeneratedFileStoreTest {

    private static final String HTML = "<!DOCTYPE html><html><head></head><body>你好</body></html>";
    @TempDir
    Path directory;

    @Test
    void savesFencedHtmlUnderItsOwnerAppAndVersionWithoutOverwriting() throws Exception {
        GeneratedFileStore store = new GeneratedFileStore(directory);
        store.saveHtml(42, 55, 101, "```html\n" + HTML + "\n```");
        Path file = directory.resolve("42/55/101/index.html");
        assertThat(Files.readString(file)).isEqualTo(HTML);
        assertThatThrownBy(() -> store.saveHtml(42, 55, 101, HTML)).isInstanceOf(IOException.class);
        assertThat(Files.readString(file)).isEqualTo(HTML);
    }

    @Test
    void rejectsIncompleteDocumentsBeforeCreatingFiles() {
        GeneratedFileStore store = new GeneratedFileStore(directory);
        assertThatThrownBy(() -> store.saveHtml(42, 55, 101, "<!DOCTYPE html><html><body>中断"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(directory.resolve("42")).doesNotExist();
    }

    @Test
    void refusesToFollowASymlinkOutsideTheApplicationDirectory() throws Exception {
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Path root = Files.createDirectory(directory.resolve("generated"));
        Files.createSymbolicLink(root.resolve("42"), outside);
        GeneratedFileStore store = new GeneratedFileStore(root);
        assertThatThrownBy(() -> store.saveHtml(42, 55, 101, HTML)).isInstanceOf(IOException.class);
        assertThat(outside.resolve("55")).doesNotExist();
    }

    @Test
    void differentAppsCanCreateTheSameOwnerDirectoryConcurrently() throws Exception {
        GeneratedFileStore store = new GeneratedFileStore(directory);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var writes = new ArrayList<Future<?>>();
            for (long appId = 1; appId <= 16; appId++) {
                long id = appId;
                writes.add(executor.submit(() -> {
                    store.saveHtml(42, id, 101, HTML);
                    return null;
                }));
            }
            for (Future<?> write : writes) write.get();
        }
        assertThat(Files.readString(directory.resolve("42/16/101/index.html"))).isEqualTo(HTML);
    }
}
