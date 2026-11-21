package com.xy.manager;

import cn.hutool.core.compress.ZipWriter;
import cn.hutool.core.io.IORuntimeException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class GeneratedFileManager {

    private static final Pattern HEAD = Pattern.compile("(?is)<head\\b[^>]*>.*?</head\\s*>");
    private static final Pattern BODY = Pattern.compile("(?is)<body\\b[^>]*>(.*?)</body\\s*>");
    private static final Set<String> MULTI_FILE_NAMES = Set.of("index.html", "style.css", "script.js");
    private static final Set<String> VUE_TEMPLATE_NAMES = Set.of("package.json", "index.html", "vite.config.js", "src/main.js");
    public static final String VUE_PHOTO = "xy-photo.json";
    private static final Pattern NUMBERED_PHOTO = Pattern.compile("xy-photo-(?:[2-9]|[1-9][0-9]+)\\.json");
    // 版本私有标记，不属于模型可写的 src 或打包、构建的文件清单。
    private static final String VUE_ARCHITECTURE_MARKER = ".xy-architecture";
    private static final Pattern ARCHITECTURE_MARKER = Pattern.compile("\\.xy-architecture(?:-([2-9]|[1-9][0-9]+))?");
    private final Path root;

    private final Optional<CosCoverStorage> coverStorage;

    public GeneratedFileManager(Path root) {
        this(root, Optional.empty());
    }

    @Autowired
    GeneratedFileManager(@Value("${xy.generation.output-directory:./generated}") Path root, Optional<CosCoverStorage> coverStorage) {
        this.coverStorage = coverStorage;
        this.root = root.toAbsolutePath().normalize();
    }

    public void saveHtml(long userId, long appId, long generationId, String content) throws IOException {
        saveFiles(userId, appId, generationId, Map.of("index.html", validateHtml(content)));
    }

    public void saveMultiFile(long userId, long appId, long generationId, Map<String, String> files) throws IOException {
        // 固定三文件白名单；模型不能添加路径、目录或覆盖平台文件。
        if (!files.keySet().equals(MULTI_FILE_NAMES)
                || files.values().stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("必须包含完整的 index.html、style.css 和 script.js");
        }
        String html = validateHtml(files.get("index.html"));
        parseMultiFileHtml(html);
        saveFiles(userId, appId, generationId, Map.of("index.html", html,
                "style.css", files.get("style.css"), "script.js", files.get("script.js")));
    }

    private void saveFiles(long userId, long appId, long generationId, Map<String, String> files) throws IOException {
        Path directory = createVersionDirectory(userId, appId, generationId);
        for (var file : files.entrySet()) {
            Files.writeString(directory.resolve(file.getKey()), file.getValue(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
    }

    private Path createVersionDirectory(long userId, long appId, long generationId) throws IOException {
        Files.createDirectories(root);
        Path directory = root.toRealPath();
        // 文件名由服务端固定，模型输出不能提供路径；各层禁止符号链接。
        for (long id : new long[]{userId, appId, generationId}) {
            directory = directory.resolve(Long.toString(id));
            try {
                Files.createDirectory(directory);
            } catch (FileAlreadyExistsException existing) {
                // 不同应用可同时创建同一用户目录，继续核对其类型。
            }
            if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("生成目录不可用");
            }
        }
        return directory;
    }

    public void writeVueSource(long userId, long appId, long generationId, String relativePath, String content)
            throws IOException {
        Path relative = vueSourcePath(relativePath);
        if (relativePath.equalsIgnoreCase("src/main.js") || content == null || content.isBlank()) {
            throw new IllegalArgumentException("不能覆盖工程入口或写入空文件");
        }
        Path directory = createVersionDirectory(userId, appId, generationId);
        for (Path segment : relative.getParent()) {
            directory = directory.resolve(segment);
            try {
                Files.createDirectory(directory);
            } catch (FileAlreadyExistsException existing) {
                // 工具可重复写入同一版本，已有目录必须仍是普通目录。
            }
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("源码目录不可用");
        }
        Files.writeString(directory.resolve(relative.getFileName()), content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
    }

    public void finishVueProject(long userId, long appId, long generationId, Map<String, String> template)
            throws IOException {
        Path directory = createVersionDirectory(userId, appId, generationId);
        Path source = directory.resolve("src");
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(source.resolve("App.vue"), LinkOption.NOFOLLOW_LINKS)
                || readFile(source, "App.vue").isBlank()) {
            throw new IllegalArgumentException("缺少 src/App.vue");
        }
        for (var file : template.entrySet()) {
            Files.writeString(directory.resolve(file.getKey()), file.getValue(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
    }

    public static boolean isVuePhoto(String name) {
        return VUE_PHOTO.equals(name) || name != null && NUMBERED_PHOTO.matcher(name).matches();
    }

    public void saveVuePhoto(long userId, long appId, long generationId, String name, String json) throws IOException {
        if (!isVuePhoto(name)) throw new IllegalArgumentException("无效的照片路径");
        Files.writeString(createVersionDirectory(userId, appId, generationId).resolve(name),
                json, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    public List<String> listVuePhotos(long userId, long appId, long generationId) throws IOException {
        try (var entries = Files.list(versionDirectory(userId, appId, generationId))) {
            return entries.map(path -> path.getFileName().toString()).filter(GeneratedFileManager::isVuePhoto)
                    .sorted(java.util.Comparator.comparingInt(String::length).thenComparing(String::compareTo)).toList();
        }
    }

    public String readVuePhoto(long userId, long appId, long generationId, String name) throws IOException {
        if (!isVuePhoto(name)) throw new IllegalArgumentException("无效的照片路径");
        return readFile(versionDirectory(userId, appId, generationId), name);
    }

    public List<Integer> vueArchitectureSlots(long userId, long appId, long generationId) throws IOException {
        try (var entries = Files.list(versionDirectory(userId, appId, generationId))) {
            return entries.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> ARCHITECTURE_MARKER.matcher(path.getFileName().toString()))
                    .filter(java.util.regex.Matcher::matches)
                    .map(match -> match.group(1) == null ? 1 : Integer.parseInt(match.group(1))).sorted().toList();
        }
    }

    public void markVueArchitecture(long userId, long appId, long generationId, int slot) throws IOException {
        if (slot < 1) throw new IllegalArgumentException("无效的架构图序号");
        String marker = VUE_ARCHITECTURE_MARKER + (slot == 1 ? "" : "-" + slot);
        Files.createFile(versionDirectory(userId, appId, generationId).resolve(marker));
    }

    public List<String> copyVueSources(long userId, long appId, long previousId, long generationId) throws IOException {
        List<String> paths = new ArrayList<>();
        for (var file : readVueProject(userId, appId, previousId).entrySet()) {
            if (VUE_TEMPLATE_NAMES.contains(file.getKey())) continue;
            if (isVuePhoto(file.getKey())) saveVuePhoto(userId, appId, generationId, file.getKey(), file.getValue());
            else writeVueSource(userId, appId, generationId, file.getKey(), file.getValue());
            paths.add(file.getKey());
        }
        for (int slot : vueArchitectureSlots(userId, appId, previousId))
            markVueArchitecture(userId, appId, generationId, slot);
        return paths;
    }

    public String readVueSource(long userId, long appId, long generationId, String path) throws IOException {
        Path file = resolveVueSource(userId, appId, generationId, path);
        return readFile(file.getParent(), file.getFileName().toString());
    }

    public void deleteVueComponent(long userId, long appId, long generationId, String path) throws IOException {
        Path file = resolveVueSource(userId, appId, generationId, path);
        if (!path.endsWith(".vue") || path.equalsIgnoreCase("src/App.vue")) {
            throw new IllegalArgumentException("只能删除非入口 Vue 组件");
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("组件文件不可用");
        Files.delete(file);
    }

    private Path resolveVueSource(long userId, long appId, long generationId, String path) throws IOException {
        Path relative = vueSourcePath(path);
        Path directory = versionDirectory(userId, appId, generationId);
        for (Path segment : relative.getParent()) {
            directory = directory.resolve(segment);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("源码目录不可用");
        }
        return directory.resolve(relative.getFileName());
    }

    /** 按原文精确替换；没有唯一匹配时不写文件。 */
    public boolean replaceVueSourceFragment(long userId, long appId, long generationId, String path,
            String oldText, String newText) throws IOException {
        if (oldText.isEmpty() || oldText.equals(newText)) throw new IllegalArgumentException("替换内容无效");
        String source = readVueSource(userId, appId, generationId, path);
        int start = source.indexOf(oldText);
        if (start < 0 || source.indexOf(oldText, start + 1) >= 0) return false;
        writeVueSource(userId, appId, generationId, path,
                source.substring(0, start) + newText + source.substring(start + oldText.length()));
        return true;
    }

    public Map<String, String> readVueProject(long userId, long appId, long generationId) throws IOException {
        Path directory = versionDirectory(userId, appId, generationId);
        Map<String, String> sources = readVueSources(userId, appId, generationId);
        Map<String, String> files = new LinkedHashMap<>();
        for (String name : VUE_TEMPLATE_NAMES) files.put(name, readFile(directory, name));
        for (var name : listVuePhotos(userId, appId, generationId)) files.put(name, readFile(directory, name));
        files.putAll(sources);
        if (files.values().stream().anyMatch(String::isBlank)) throw new IOException("源码文件不完整");
        return files;
    }

    public List<String> listVueSources(long userId, long appId, long generationId) throws IOException {
        Path directory;
        try {
            directory = versionDirectory(userId, appId, generationId);
        } catch (NoSuchFileException emptyDraft) {
            return List.of();
        }
        Path source = directory.resolve("src");
        if (Files.notExists(source, LinkOption.NOFOLLOW_LINKS)) return List.of();
        List<String> names = new ArrayList<>();
        try (var paths = Files.walk(source)) {
            for (Path path : paths.sorted().toList()) {
                if (Files.isSymbolicLink(path)) throw new IOException("源码不可使用符号链接");
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
                String name = directory.relativize(path).toString();
                try {
                    vueSourcePath(name);
                } catch (IllegalArgumentException invalid) {
                    throw new IOException("存在无效的源码路径");
                }
                names.add(name);
            }
        }
        return names;
    }

    public Map<String, String> readVueSources(long userId, long appId, long generationId) throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("src/App.vue", readVueSource(userId, appId, generationId, "src/App.vue"));
        for (String name : listVueSources(userId, appId, generationId)) {
            if (!sources.containsKey(name)) sources.put(name, readVueSource(userId, appId, generationId, name));
        }
        return sources;
    }

    private Path vueSourcePath(String value) {
        if (value == null || !value.startsWith("src/") || value.contains("\\")
                || !value.matches(".*\\.(vue|js|css|json)")) throw new IllegalArgumentException("源码路径无效");
        for (String part : value.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")
                    || part.equalsIgnoreCase("node_modules") || part.equalsIgnoreCase("dist")) {
                throw new IllegalArgumentException("源码路径无效");
            }
        }
        return Path.of(value);
    }

    public SourceArchive readVueSourceZip(long userId, long appId, long generationId) throws IOException {
        var directory = versionDirectory(userId, appId, generationId);
        var names = new LinkedHashSet<>(VUE_TEMPLATE_NAMES);
        names.add("src/App.vue");
        names.addAll(listVueSources(userId, appId, generationId));
        names.addAll(listVuePhotos(userId, appId, generationId));
        return archive(names, name -> {
            var text = name.startsWith("src/") ? readVueSource(userId, appId, generationId, name) : readFile(directory, name);
            if (text.isBlank()) throw new IOException("源码文件不完整");
            return text;
        });
    }

    public record VueBuildPaths(Path source, Path output) {}

    public VueBuildPaths prepareVueBuild(long userId, long appId, long generationId) throws IOException {
        Map<String, String> source = readVueProject(userId, appId, generationId);
        Path workspace = Files.createDirectory(versionDirectory(userId, appId, generationId).resolve(".build"));
        Path input = Files.createDirectory(workspace.resolve("source"));
        Path output = Files.createDirectory(workspace.resolve("output"));
        for (var file : source.entrySet()) {
            Path target = input.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        }
        return new VueBuildPaths(input, output);
    }

    public void saveVuePreview(long userId, long appId, long generationId, VueBuildPaths paths) throws IOException {
        Document html = Jsoup.parse(validateHtml(readFile(paths.output(), "index.html")));
        html.outputSettings().prettyPrint(false);
        var scripts = html.select("script[src]");
        if (scripts.size() != 1 || !Set.of("app.js", "./app.js").contains(scripts.first().attr("src"))) {
            throw new IOException("Vue 构建脚本不完整");
        }
        scripts.attr("src", dataUrl("text/javascript", readFile(paths.output(), "app.js")));
        for (var style : html.select("link[rel=stylesheet][href]")) {
            String name = style.attr("href").replaceFirst("^\\./", "");
            if (!name.matches("[A-Za-z0-9_-]+\\.css")) throw new IOException("Vue 构建样式路径无效");
            style.attr("href", dataUrl("text/css", readFile(paths.output(), name)));
        }
        Files.writeString(versionDirectory(userId, appId, generationId).resolve("preview.html"), html.outerHtml(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private static String coverKey(long userId, long appId, long versionId) {
        return "xy/covers/" + userId + "/" + appId + "/" + versionId + ".jpg";
    }

    public boolean hasCover(long userId, long appId, long versionId) {
        if (coverStorage.isPresent() && coverStorage.get().exists(coverKey(userId, appId, versionId))) return true;
        try {
            return Files.isRegularFile(versionDirectory(userId, appId, versionId).resolve("cover.jpg"), LinkOption.NOFOLLOW_LINKS);
        } catch (IOException unavailable) {
            return false;
        }
    }

    public void saveCover(long userId, long appId, long versionId, byte[] jpeg) throws IOException {
        if (coverStorage.isPresent()) {
            coverStorage.get().save(coverKey(userId, appId, versionId), jpeg);
            return;
        }
        Path directory = versionDirectory(userId, appId, versionId);
        Path temporary = Files.createTempFile(directory, ".cover-", ".tmp");
        try {
            Files.write(temporary, jpeg);
            Files.move(temporary, directory.resolve("cover.jpg"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public byte[] readCover(long userId, long appId, long versionId) throws IOException {
        if (coverStorage.isPresent()) {
            try {
                return coverStorage.get().read(coverKey(userId, appId, versionId));
            } catch (NoSuchFileException migrated) {
                // 启用 COS 前已发布的封面仍从原位置读取。
            }
        }
        Path file = versionDirectory(userId, appId, versionId).resolve("cover.jpg");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new NoSuchFileException("无封面");
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            return input.readAllBytes();
        }
    }

    public String readVuePreview(long userId, long appId, long generationId) throws IOException {
        return readFile(versionDirectory(userId, appId, generationId), "preview.html");
    }

    public String readHtml(long userId, long appId, long generationId) throws IOException {
        return readFile(versionDirectory(userId, appId, generationId), "index.html");
    }

    public Map<String, String> readMultiFile(long userId, long appId, long generationId) throws IOException {
        Path directory = versionDirectory(userId, appId, generationId);
        return Map.of("index.html", readFile(directory, "index.html"),
                "style.css", readFile(directory, "style.css"), "script.js", readFile(directory, "script.js"));
    }

    public SourceArchive readSourceZip(long userId, long appId, long generationId, boolean multiFile) throws IOException {
        var directory = versionDirectory(userId, appId, generationId);
        return archive(multiFile ? MULTI_FILE_NAMES : Set.of("index.html"), name -> readFile(directory, name));
    }

    /** 先完成打包再发响应；请求结束（包括客户端断开）即删除临时文件。 */
    public static final class SourceArchive implements AutoCloseable {
        private final Path file;
        private SourceArchive(Path file) { this.file = file; }
        public long size() throws IOException { return Files.size(file); }
        public void writeTo(OutputStream out) throws IOException { Files.copy(file, out); }
        @Override public void close() throws IOException { Files.deleteIfExists(file); }
    }

    @FunctionalInterface
    private interface SourceReader { String read(String name) throws IOException; }

    private SourceArchive archive(Set<String> names, SourceReader reader) throws IOException {
        Path file = Files.createTempFile("xy-source-", ".zip");
        boolean ready = false;
        try {
            try (var out = Files.newOutputStream(file); var zip = new ZipWriter(out, StandardCharsets.UTF_8)) {
                for (var name : names) {
                    var bytes = reader.read(name).getBytes(StandardCharsets.UTF_8);
                    zip.add(name, new ByteArrayInputStream(bytes));
                }
            }
            ready = true;
            return new SourceArchive(file);
        } catch (IORuntimeException error) {
            throw new IOException("源码压缩失败", error);
        } finally {
            if (!ready) Files.deleteIfExists(file);
        }
    }

    public String readMultiFilePreview(long userId, long appId, long generationId) throws IOException {
        Path directory = versionDirectory(userId, appId, generationId);
        Document document = parseMultiFileHtml(readFile(directory, "index.html"));
        String css = readFile(directory, "style.css");
        String js = readFile(directory, "script.js");
        if (css.isBlank() || js.isBlank()) throw new IOException("生成文件内容不完整");
        // Data URL 保留源码里的结束标签字面量；浏览器不需要携带会话去请求 CSS/JS。
        document.select("link[rel=stylesheet][href]").attr("href", dataUrl("text/css", css));
        document.select("script[src]").attr("src", dataUrl("text/javascript", js));
        return document.outerHtml();
    }

    public void publishHtml(Path publishRoot, String deployKey, String html) throws IOException {
        Path directory = publicationDirectory(publishRoot, deployKey, true);
        Path temporary = directory.resolve(".publish-" + UUID.randomUUID() + ".tmp");
        try {
            Files.writeString(temporary, html, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            // 单文件快照完整写入后才切换；文件系统不支持原子替换时直接报错，保留旧页面。
            Files.move(temporary, directory.resolve("index.html"),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public void removePublishedHtml(Path publishRoot, String deployKey) throws IOException {
        Path file = publicationDirectory(publishRoot, deployKey, false).resolve("index.html");
        if (!Files.notExists(file, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("发布入口不是普通文件");
        }
        Files.deleteIfExists(file);
    }

    private Path publicationDirectory(Path publishRoot, String deployKey, boolean create) throws IOException {
        if (deployKey == null || !deployKey.matches("[a-f0-9]{32}")) {
            throw new IOException("无效的发布标识");
        }
        Path requestedRoot = publishRoot.toAbsolutePath().normalize();
        if (requestedRoot.startsWith(root) || root.startsWith(requestedRoot)) {
            throw new IOException("发布目录不能与私有生成目录重叠");
        }
        if (create) Files.createDirectories(requestedRoot);
        Path publicDirectory;
        try {
            publicDirectory = requestedRoot.toRealPath();
        } catch (NoSuchFileException missing) {
            if (create) throw missing;
            return requestedRoot.resolve(deployKey);
        }
        Path generatedDirectory = create || !Files.notExists(root) ? root.toRealPath() : root;
        if (publicDirectory.startsWith(generatedDirectory) || generatedDirectory.startsWith(publicDirectory)) {
            throw new IOException("发布目录不能与私有生成目录重叠");
        }
        Path directory = publicDirectory.resolve(deployKey);
        if (create) {
            try {
                Files.createDirectory(directory);
            } catch (FileAlreadyExistsException existing) {
                // 重新发布复用此目录，随后验证它不是链接或普通文件。
            }
        }
        if (Files.isSymbolicLink(directory)
                || (!Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
                || Files.isSymbolicLink(directory.resolve("index.html"))) {
            throw new IOException("发布目录或文件不可使用符号链接");
        }
        return directory;
    }

    private Document parseMultiFileHtml(String html) {
        Document document = Jsoup.parse(validateHtml(html));
        document.outputSettings().prettyPrint(false);
        var styles = document.select("link[rel=stylesheet][href]");
        var scripts = document.select("script[src]");
        if (styles.isEmpty() || scripts.isEmpty()
                || styles.stream().anyMatch(element -> !Set.of("style.css", "./style.css").contains(element.attr("href")))
                || scripts.stream().anyMatch(element -> !Set.of("script.js", "./script.js").contains(element.attr("src")))) {
            throw new IllegalArgumentException("HTML 必须引用本版本的 style.css 和 script.js");
        }
        return document;
    }

    private String dataUrl(String mediaType, String content) {
        return "data:" + mediaType + ";charset=UTF-8;base64,"
                + Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
    }

    private Path versionDirectory(long userId, long appId, long generationId) throws IOException {
        Path directory = root.toRealPath();
        for (long id : new long[]{userId, appId, generationId}) {
            directory = directory.resolve(Long.toString(id));
            if (Files.isSymbolicLink(directory)) throw new IOException("预览目录不可使用符号链接");
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new NoSuchFileException("生成目录不存在");
            }
        }
        return directory;
    }

    private String readFile(Path directory, String filename) throws IOException {
        Path file = directory.resolve(filename);
        if (Files.isSymbolicLink(file)) throw new IOException("预览文件不可使用符号链接");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException("生成文件不存在");
        }
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String validateHtml(String content) {
        if (content == null) throw new IllegalArgumentException("模型没有返回 HTML");
        String html = content.strip();
        if (html.startsWith("```")) {
            int lineEnd = html.indexOf('\n');
            if (lineEnd >= 0 && html.endsWith("```")) {
                String fence = html.substring(0, lineEnd).strip();
                if (fence.equalsIgnoreCase("```html") || fence.equals("```")) {
                    html = html.substring(lineEnd + 1, html.length() - 3).strip();
                }
            }
        }
        String lower = html.toLowerCase(Locale.ROOT);
        var body = BODY.matcher(html);
        if (!lower.startsWith("<!doctype html>") || !lower.endsWith("</html>")
                || !Pattern.compile("(?i)<html\\b[^>]*>").matcher(html).find()
                || !HEAD.matcher(html).find() || !body.find() || body.group(1).isBlank()) {
            throw new IllegalArgumentException("模型未返回完整的 HTML 文档");
        }
        return html;
    }
}
