package com.xy.manager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GeneratedFileManager {

    private static final Pattern HEAD = Pattern.compile("(?is)<head\\b[^>]*>.*?</head\\s*>");
    private static final Pattern BODY = Pattern.compile("(?is)<body\\b[^>]*>(.*?)</body\\s*>");
    private final Path root;

    public GeneratedFileManager(@Value("${xy.generation.output-directory:./generated}") Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public void saveHtml(long userId, long appId, long generationId, String content) throws IOException {
        String html = validateHtml(content);
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
        Files.writeString(directory.resolve("index.html"), html, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    public String readHtml(long userId, long appId, long generationId) throws IOException {
        Path directory = root.toRealPath();
        for (long id : new long[]{userId, appId, generationId}) {
            directory = directory.resolve(Long.toString(id));
            if (Files.isSymbolicLink(directory)) throw new IOException("预览目录不可使用符号链接");
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new NoSuchFileException("生成目录不存在");
            }
        }
        Path file = directory.resolve("index.html");
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
