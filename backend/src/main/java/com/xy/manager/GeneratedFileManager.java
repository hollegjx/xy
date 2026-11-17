package com.xy.manager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GeneratedFileManager {

    private static final Pattern HEAD = Pattern.compile("(?is)<head\\b[^>]*>.*?</head\\s*>");
    private static final Pattern BODY = Pattern.compile("(?is)<body\\b[^>]*>(.*?)</body\\s*>");
    private static final Set<String> MULTI_FILE_NAMES = Set.of("index.html", "style.css", "script.js");
    private static final Set<String> VUE_TEMPLATE_NAMES = Set.of("package.json", "index.html", "vite.config.js", "src/main.js");
    private final Path root;

    public GeneratedFileManager(@Value("${xy.generation.output-directory:./generated}") Path root) {
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

    public Map<String, String> readVueProject(long userId, long appId, long generationId) throws IOException {
        Path directory = versionDirectory(userId, appId, generationId);
        Path source = directory.resolve("src");
        if (Files.isSymbolicLink(source)) throw new IOException("源码目录不可使用符号链接");
        Map<String, String> files = new LinkedHashMap<>();
        for (String name : VUE_TEMPLATE_NAMES) files.put(name, readFile(directory, name));
        files.put("src/App.vue", readFile(source, "App.vue"));
        try (var paths = Files.walk(source)) {
            for (Path path : paths.sorted().toList()) {
                if (Files.isSymbolicLink(path)) throw new IOException("源码不可使用符号链接");
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
                String name = directory.relativize(path).toString();
                try {
                    vueSourcePath(name);
                } catch (IllegalArgumentException error) {
                    throw new IOException("存在无效的源码路径");
                }
                files.put(name, readFile(path.getParent(), path.getFileName().toString()));
            }
        }
        if (files.values().stream().anyMatch(String::isBlank)) throw new IOException("源码文件不完整");
        return files;
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

    public byte[] readVueSourceZip(long userId, long appId, long generationId) throws IOException {
        return zip(readVueProject(userId, appId, generationId));
    }

    public String readHtml(long userId, long appId, long generationId) throws IOException {
        return readFile(versionDirectory(userId, appId, generationId), "index.html");
    }

    public Map<String, String> readMultiFile(long userId, long appId, long generationId) throws IOException {
        Path directory = versionDirectory(userId, appId, generationId);
        return Map.of("index.html", readFile(directory, "index.html"),
                "style.css", readFile(directory, "style.css"), "script.js", readFile(directory, "script.js"));
    }

    public byte[] readSourceZip(long userId, long appId, long generationId, boolean multiFile) throws IOException {
        // 只读取固定生成文件，全部打包成功后才返回，避免缺文件时下载到残缺 ZIP。
        Map<String, String> files = multiFile ? readMultiFile(userId, appId, generationId)
                : Map.of("index.html", readHtml(userId, appId, generationId));
        return zip(files);
    }

    private byte[] zip(Map<String, String> files) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (var file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return output.toByteArray();
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
