package com.xy.manager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GeneratedFileManager {

    private static final Pattern HEAD = Pattern.compile("(?is)<head\\b[^>]*>.*?</head\\s*>");
    private static final Pattern BODY = Pattern.compile("(?is)<body\\b[^>]*>(.*?)</body\\s*>");
    private static final Set<String> MULTI_FILE_NAMES = Set.of("index.html", "style.css", "script.js");
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
        for (var file : files.entrySet()) {
            Files.writeString(directory.resolve(file.getKey()), file.getValue(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
    }

    public String readHtml(long userId, long appId, long generationId) throws IOException {
        return readFile(versionDirectory(userId, appId, generationId), "index.html");
    }

    public Map<String, String> readMultiFile(long userId, long appId, long generationId) throws IOException {
        Path directory = versionDirectory(userId, appId, generationId);
        return Map.of("index.html", readFile(directory, "index.html"),
                "style.css", readFile(directory, "style.css"), "script.js", readFile(directory, "script.js"));
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
