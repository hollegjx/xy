package com.xy.manager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.jsoup.Jsoup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeneratedFileManagerTest {

    private static final String HTML = "<!DOCTYPE html><html><head></head><body>你好</body></html>";
    @TempDir
    Path directory;

    @Test
    void preservesTheOtherButtonAndLineEndingsWhenReplacingLiteralText() throws Exception {
        GeneratedFileManager manager = new GeneratedFileManager(directory);
        String first = "<button id=\"first\">查看</button>\r\n";
        String second = "<button id=\"second\">查看</button>\r\n";
        manager.writeVueSource(42, 55, 101, "src/App.vue", first + second);
        assertThat(manager.replaceVueSourceFragment(42, 55, 101, "src/App.vue", "查看", "浏览")).isFalse();
        assertThat(manager.replaceVueSourceFragment(42, 55, 101, "src/App.vue", "id=\"second\">查看",
                "id=\"second\">查看 $5 作品")).isTrue();
        assertThat(manager.readVueSource(42, 55, 101, "src/App.vue"))
                .isEqualTo(first + "<button id=\"second\">查看 $5 作品</button>\r\n");
    }

    @Test
    void savesFencedHtmlUnderItsOwnerAppAndVersionWithoutOverwriting() throws Exception {
        GeneratedFileManager store = new GeneratedFileManager(directory);
        store.saveHtml(42, 55, 101, "```html\n" + HTML + "\n```");
        Path file = directory.resolve("42/55/101/index.html");
        assertThat(Files.readString(file)).isEqualTo(HTML);
        assertThat(store.readHtml(42, 55, 101)).isEqualTo(HTML);
        assertThatThrownBy(() -> store.saveHtml(42, 55, 101, HTML)).isInstanceOf(IOException.class);
        assertThat(Files.readString(file)).isEqualTo(HTML);
    }

    @Test
    void rejectsIncompleteDocumentsBeforeCreatingFiles() {
        GeneratedFileManager store = new GeneratedFileManager(directory);
        assertThatThrownBy(() -> store.saveHtml(42, 55, 101, "<!DOCTYPE html><html><body>中断"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(directory.resolve("42")).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"../App.vue", "/tmp/xy-App.vue", "src/../../App.vue", "src/main.js", "src/Main.js",
            "src\\App.vue", "src/node_modules/main.js", "package.json"})
    void vueToolsRejectPathsOutsideEditableSource(String path) {
        GeneratedFileManager manager = new GeneratedFileManager(directory);
        assertThatThrownBy(() -> manager.writeVueSource(42, 55, 101, path, "source"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(directory.resolve("42")).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"src/App.vue", "src/app.vue", "src/main.js", "src/../src/App.vue"})
    void componentDeletionKeepsProjectEntries(String path) throws Exception {
        GeneratedFileManager manager = new GeneratedFileManager(directory);
        manager.writeVueSource(42, 55, 101, "src/App.vue", "<template>首页</template>");
        manager.finishVueProject(42, 55, 101, Map.of("src/main.js", "main"));
        assertThatThrownBy(() -> manager.deleteVueComponent(42, 55, 101, path))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(manager.readVueSource(42, 55, 101, "src/App.vue")).isEqualTo("<template>首页</template>");
        assertThat(manager.readVueSource(42, 55, 101, "src/main.js")).isEqualTo("main");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void vueToolsAndDownloadsRejectLinkedSourceDirectoriesAndFiles(boolean directoryLink) throws Exception {
        GeneratedFileManager manager = new GeneratedFileManager(directory);
        manager.writeVueSource(42, 55, 101, "src/App.vue", "<template>旧内容</template>");
        manager.writeVueSource(42, 55, 101, "src/App.vue", "<template>新内容</template>");
        Map<String, String> template = Map.of("package.json", "{}", "index.html", "entry",
                "vite.config.js", "config", "src/main.js", "main");
        manager.finishVueProject(42, 55, 101, template);
        assertThat(manager.readVueProject(42, 55, 101)).containsEntry("src/App.vue", "<template>新内容</template>");
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Path secret = Files.writeString(outside.resolve("Card.vue"), "private");
        String linkedPath = directoryLink ? "src/link/Card.vue" : "src/link.vue";
        Files.createSymbolicLink(directory.resolve("42/55/101/src/link" + (directoryLink ? "" : ".vue")),
                directoryLink ? outside : secret);
        assertThatThrownBy(() -> manager.writeVueSource(42, 55, 101, linkedPath, "changed")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> manager.readVueSource(42, 55, 101, linkedPath)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> manager.deleteVueComponent(42, 55, 101, linkedPath)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> manager.readVueSourceZip(42, 55, 101)).isInstanceOf(IOException.class);
        assertThat(Files.readString(secret)).isEqualTo("private");
    }

    @ParameterizedTest
    @ValueSource(strings = {"script_path", "style_path", "linked_script"})
    void refusesUnsafeAssetsBeforeSavingAVuePreview(String invalid) throws Exception {
        GeneratedFileManager manager = new GeneratedFileManager(directory);
        manager.saveHtml(42, 55, 101, HTML);
        Path output = Files.createDirectory(directory.resolve("output"));
        String script = invalid.equals("script_path") ? "../app.js" : "./app.js";
        Files.writeString(output.resolve("index.html"), HTML.replace("</head>",
                "<script type=\"module\" src=\"" + script + "\"></script>"
                        + (invalid.equals("style_path") ? "<link rel=\"stylesheet\" href=\"../secret.css\">" : "") + "</head>"));
        if (invalid.equals("linked_script")) Files.createSymbolicLink(output.resolve("app.js"), directory.resolve("42/55/101/index.html"));
        else Files.writeString(output.resolve("app.js"), "source");
        assertThatThrownBy(() -> manager.saveVuePreview(42, 55, 101,
                new GeneratedFileManager.VueBuildPaths(directory, output))).isInstanceOf(IOException.class);
        assertThat(directory.resolve("42/55/101/preview.html")).doesNotExist();
    }

    @Test
    void refusesToFollowASymlinkOutsideTheApplicationDirectory() throws Exception {
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Path root = Files.createDirectory(directory.resolve("generated"));
        Files.createSymbolicLink(root.resolve("42"), outside);
        GeneratedFileManager store = new GeneratedFileManager(root);
        assertThatThrownBy(() -> store.saveHtml(42, 55, 101, HTML)).isInstanceOf(IOException.class);
        assertThat(outside.resolve("55")).doesNotExist();
    }

    @Test
    void differentAppsCanCreateTheSameOwnerDirectoryConcurrently() throws Exception {
        GeneratedFileManager store = new GeneratedFileManager(directory);
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

    @ParameterizedTest
    @ValueSource(strings = {"42", "42/55", "42/55/101", "42/55/101/index.html"})
    void refusesToReadThroughSymlinksAtEveryApplicationPathLevel(String relative) throws Exception {
        Path root = Files.createDirectory(directory.resolve("generated"));
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Path secret = Files.writeString(outside.resolve("secret.html"), "不可暴露的内容");
        Path link = root.resolve(relative);
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, relative.endsWith("index.html") ? secret : outside);
        GeneratedFileManager store = new GeneratedFileManager(root);
        assertThatThrownBy(() -> store.readHtml(42, 55, 101)).isInstanceOf(IOException.class);
    }

    @Test
    void savesThreeOriginalFilesAndBundlesTheirExactContentsForPreview() throws Exception {
        GeneratedFileManager manager = new GeneratedFileManager(directory);
        Map<String, String> files = multiFiles();
        manager.saveMultiFile(42, 55, 101, files);
        for (var file : files.entrySet()) {
            assertThat(Files.readString(directory.resolve("42/55/101/" + file.getKey()))).isEqualTo(file.getValue());
        }
        var preview = Jsoup.parse(manager.readMultiFilePreview(42, 55, 101));
        String cssUrl = preview.selectFirst("link[href]").attr("href");
        String jsUrl = preview.selectFirst("script[src]").attr("src");
        assertThat(Base64.getDecoder().decode(cssUrl.substring(cssUrl.indexOf(',') + 1)))
                .isEqualTo(files.get("style.css").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(Base64.getDecoder().decode(jsUrl.substring(jsUrl.indexOf(',') + 1)))
                .isEqualTo(files.get("script.js").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> manager.saveMultiFile(42, 55, 101, files)).isInstanceOf(IOException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"../style.css", "/tmp/xy-style.css", "nested/style.css", "style.css/../index.html"})
    void rejectsUnapprovedFilePathsBeforeWritingAnything(String path) {
        GeneratedFileManager manager = new GeneratedFileManager(directory);
        Map<String, String> files = new LinkedHashMap<>(multiFiles());
        files.put(path, files.remove("style.css"));
        assertThatThrownBy(() -> manager.saveMultiFile(42, 55, 101, files)).isInstanceOf(IllegalArgumentException.class);
        assertThat(directory.resolve("42")).doesNotExist();
    }

    @Test
    void rejectsMissingBlankOrUnlinkedAssetsBeforeWritingAnything() {
        GeneratedFileManager manager = new GeneratedFileManager(directory);
        Map<String, String> files = new LinkedHashMap<>(multiFiles());
        files.remove("style.css");
        assertThatThrownBy(() -> manager.saveMultiFile(42, 55, 101, files)).isInstanceOf(IllegalArgumentException.class);
        files.put("style.css", " ");
        assertThatThrownBy(() -> manager.saveMultiFile(42, 55, 101, files)).isInstanceOf(IllegalArgumentException.class);
        files.put("style.css", "body{}");
        for (String html : List.of(HTML, multiFiles().get("index.html").replace("script.js", "https://external.invalid/app.js"))) {
            files.put("index.html", html);
            assertThatThrownBy(() -> manager.saveMultiFile(42, 55, 101, files)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(directory.resolve("42")).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"style.css", "script.js"})
    void refusesToReadMultiFileAssetsThroughSymlinks(String filename) throws Exception {
        GeneratedFileManager manager = new GeneratedFileManager(directory);
        manager.saveMultiFile(42, 55, 101, multiFiles());
        Path asset = directory.resolve("42/55/101/" + filename);
        Path outside = Files.writeString(directory.resolve("private-file"), "private");
        Files.delete(asset);
        Files.createSymbolicLink(asset, outside);
        assertThatThrownBy(() -> manager.readMultiFilePreview(42, 55, 101)).isInstanceOf(IOException.class);
    }

    private Map<String, String> multiFiles() {
        return Map.of("index.html", HTML.replace("</head>", "<link rel=\"stylesheet\" href=\"./style.css\"></head>")
                        .replace("</body>", "<script src=\"./script.js\"></script></body>"),
                "style.css", "body { color: red; } /* </style> */", "script.js", "window.closingTag = '</script>';");
    }

    @Test
    void removingAMissingPublicationDoesNotCreateDirectories() throws Exception {
        Path generated = directory.resolve("generated");
        Path published = directory.resolve("published");
        GeneratedFileManager manager = new GeneratedFileManager(generated);
        manager.removePublishedHtml(published, "a".repeat(32));
        assertThat(generated).doesNotExist();
        assertThat(published).doesNotExist();

        Files.createDirectory(published);
        manager.removePublishedHtml(published, "a".repeat(32));
        assertThat(published.resolve("a".repeat(32))).doesNotExist();
        assertThat(generated).doesNotExist();
    }

    @Test
    void publishingRejectsOverlappingPrivateDirectoriesAndLinkedPublicEntries() throws Exception {
        Path generated = Files.createDirectory(directory.resolve("generated"));
        Path published = Files.createDirectory(directory.resolve("published"));
        String key = "a".repeat(32);
        GeneratedFileManager manager = new GeneratedFileManager(generated);
        assertThatThrownBy(() -> manager.publishHtml(generated, key, HTML)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> manager.publishHtml(directory, key, HTML)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> manager.publishHtml(published, "../generated", HTML)).isInstanceOf(IOException.class);
        Files.createSymbolicLink(published.resolve(key), generated);
        assertThatThrownBy(() -> manager.publishHtml(published, key, HTML)).isInstanceOf(IOException.class);
        assertThat(generated.resolve("index.html")).doesNotExist();
    }
}
