package com.xy.generation.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.net.ssl.HttpsURLConnection;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

@Component
public class OpenversePhotoSearch {
    enum Theme {
        architecture("city architecture"), art("art supplies"), coffee("latte"), food("fresh food"),
        nature("forest landscape"), reading("books"), technology("computer workspace"), travel("mountain landscape");

        final String query;
        Theme(String query) { this.query = query; }

        static String queryFor(String theme) {
            if (theme == null) return null;
            try { return valueOf(theme).query; }
            catch (IllegalArgumentException unknown) { return null; }
        }
    }
    private static final Pattern LANDING = Pattern.compile("https://www\\.flickr\\.com/photos/[A-Za-z0-9_@%-]+/([0-9]+)/?");
    private static final Pattern IMAGE = Pattern.compile("https://live\\.staticflickr\\.com/([0-9]+)/([0-9]+)_([a-fA-F0-9]+)(?:_[a-z])?\\.jpg");
    private final ObjectMapper mapper;

    public OpenversePhotoSearch(ObjectMapper mapper) { this.mapper = mapper; }

    public record Photo(String src, String source, String license) {
        String workId() {
            var landing = LANDING.matcher(source);
            if (!landing.matches()) throw new IllegalArgumentException("照片缺少已核对的 Flickr 原作");
            return landing.group(1);
        }
    }

    static List<String> themes() { return java.util.Arrays.stream(Theme.values()).map(Enum::name).toList(); }

    public Optional<Photo> find(String theme) {
        // 出站查询只取服务端预设值，模型和用户的自由文本均不能进入 URL。
        String query = Theme.queryFor(theme);
        if (query == null) return Optional.empty();
        try {
            String search = "https://api.openverse.org/v1/images/?q="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&license=cc0&page_size=1";
            JsonNode hit = firstResult(search);
            if (!hit.isMissingNode() && !"flickr".equals(hit.path("source").asText())) {
                hit = firstResult(search + "&source=flickr");
            }
            return verifyPhoto(hit);
        } catch (IOException error) {
            return Optional.empty();
        }
    }

    // 同一主题的多张照片从一次分页搜索取候选，各自核对原页许可，重复作品不会占用另一个位置。
    public List<Photo> findMany(String theme, int count) {
        return findMany(theme, count, Set.of());
    }

    /** 不下载已排除的原作；不同主题可独立发现同一原作，汇总时再按可选范围分配。 */
    public List<Photo> findMany(String theme, int count, Set<String> excludedIds) {
        String query = Theme.queryFor(theme);
        if (query == null || count < 1) return List.of();
        int pageSize = Math.min(count, 20);
        var photos = new ArrayList<Photo>();
        var usedIds = new HashSet<>(excludedIds);
        String base = "https://api.openverse.org/v1/images/?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&license=cc0&source=flickr&page_size=" + pageSize;
        boolean hasNext = true;
        for (int page = 1; hasNext && !Thread.currentThread().isInterrupted(); page++) {
            try {
                var result = mapper.reader().readTree(get(base + "&page=" + page, 512_000));
                var hits = result.path("results");
                if (hits.isEmpty()) break;
                hasNext = page < result.path("page_count").asInt(page);
                for (var hit : hits) {
                    if (Thread.currentThread().isInterrupted()) return List.copyOf(photos);
                    var landing = LANDING.matcher(hit.path("foreign_landing_url").asText());
                    if (!landing.matches() || usedIds.contains(landing.group(1))) continue;
                    try { verifyPhoto(hit).ifPresent(p -> { if (usedIds.add(landing.group(1))) photos.add(p); }); }
                    catch (IOException unavailable) { /* 单张损坏不影响同批其他照片。 */ }
                    if (photos.size() == count) return List.copyOf(photos);
                }
            } catch (IOException unavailable) { break; }
        }
        return List.copyOf(photos);
    }

    private Optional<Photo> verifyPhoto(JsonNode hit) throws IOException {
        if (!"cc0".equals(hit.path("license").asText()) || !"flickr".equals(hit.path("source").asText())) {
            return Optional.empty();
        }
        String page = hit.path("foreign_landing_url").asText();
        var landing = LANDING.matcher(page);
        if (!landing.matches()) return Optional.empty();
        // 索引只用于发现照片，许可及实际图片均以这张照片的原始页面为准。
        var doc = Jsoup.parse(new String(get(page, 2_000_000), StandardCharsets.UTF_8));
        boolean cc0 = doc.select(".photo-license-info a[rel~=license][href]").stream()
                .anyMatch(a -> a.attr("href").matches("https?://creativecommons\\.org/publicdomain/zero/1\\.0/(?:deed\\.[a-zA-Z_-]+)?"));
        var meta = doc.selectFirst("meta[property=og:image][content]");
        if (!cc0 || meta == null) return Optional.empty();
        var image = IMAGE.matcher(meta.attr("content"));
        if (!image.matches() || !image.group(2).equals(landing.group(1))) return Optional.empty();
        // 固定图床和 640px JPEG，不接受模型给出的 URL，也不追随重定向。
        String thumb = "https://live.staticflickr.com/" + image.group(1) + "/" + image.group(2)
                + "_" + image.group(3) + "_z.jpg";
        byte[] bytes = get(thumb, 512_000);
        if (bytes.length < 4 || bytes[0] != (byte) 0xff || bytes[1] != (byte) 0xd8
                || bytes[bytes.length - 2] != (byte) 0xff || bytes[bytes.length - 1] != (byte) 0xd9
                || !decodableThumbnail(bytes)) return Optional.empty();
        return Optional.of(new Photo("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes), page, "CC0"));
    }

    private JsonNode firstResult(String url) throws IOException {
        return mapper.reader().readTree(get(url, 65536)).path("results").path(0);
    }

    private boolean decodableThumbnail(byte[] bytes) throws IOException {
        try (var in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var reader = ImageIO.getImageReadersByFormatName("JPEG").next();
            try {
                reader.setInput(in);
                return reader.getWidth(0) <= 640 && reader.getHeight(0) <= 640 && reader.read(0) != null;
            } finally {
                reader.dispose();
            }
        }
    }

    byte[] get(String url, int limit) throws IOException {
        var connection = (HttpsURLConnection) URI.create(url).toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(4000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("User-Agent", "XingYun/1.0 (+https://xy.xiaoxy.com)");
        try {
            if (connection.getResponseCode() != 200) throw new IOException("图库未返回内容");
            try (var in = connection.getInputStream()) {
                byte[] bytes = in.readNBytes(limit + 1);
                if (bytes.length > limit) throw new IOException("素材过大");
                return bytes;
            }
        } finally {
            connection.disconnect();
        }
    }
}
