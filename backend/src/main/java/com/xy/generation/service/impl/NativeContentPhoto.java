package com.xy.generation.service.impl;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Range;
import org.jsoup.parser.Parser;

// 照片按本轮标记独立保存；模型编辑含署名的 img，交付的 HTML 才包含 JPEG。
final class NativeContentPhoto {
    private static final String THEME = "data-xy-photo";
    private static final String SOURCE = "data-xy-source";
    private static final String LICENSE = "data-xy-license";
    private static final String UNAVAILABLE = "data-xy-photo-unavailable";
    private static final List<String> IMAGE_ATTRIBUTES = List.of(THEME, SOURCE, LICENSE, UNAVAILABLE, "src", "srcset",
            "sizes", "alt", "width", "height", "loading", "decoding", "crossorigin", "referrerpolicy", "fetchpriority",
            "attributionsrc", "usemap", "ismap", "longdesc", "lowsrc");
    private static final Pattern MARKER = Pattern.compile(THEME, Pattern.CASE_INSENSITIVE);
    static final String HTML_HINT = """
            如页面适合真实内容照片，在正文合适位置放一张 <img data-xy-photo="coffee" alt="一杯拿铁咖啡">，
            主题任选其一：%s。写准确的 alt，不写 src、srcset 或外部地址；系统按主题查找照片，找不到时显示替代文字。
            用户不要照片、只要插画或没有合适主题时省略。""".formatted(String.join("、", OpenversePhotoSearch.themes()));
    static final String EDIT_HINT = "已有 data-xy-photo 图片的来源和许可随标签提供，系统在交付时填入 src；保留、移动或删除标签即可。";

    private final OpenversePhotoSearch search;
    private final HashMap<String, OpenversePhotoSearch.Photo> photos = new HashMap<>();
    private final HashSet<String> attempted = new HashSet<>();

    NativeContentPhoto(OpenversePhotoSearch search) { this.search = search; }

    boolean available() { return !photos.isEmpty(); }

    String inherit(String html) {
        return rewriteImages(html, image -> {
            String src = image.attr("src");
            if (!src.startsWith("data:image/jpeg;base64,") || !image.hasAttr(SOURCE)
                    || !"CC0".equals(image.attr(LICENSE))) return null;
            remember(image.attr(THEME), new OpenversePhotoSearch.Photo(src, image.attr(SOURCE), image.attr(LICENSE)));
            return image.removeAttr("src").outerHtml();
        });
    }

    List<OpenversePhotoSearch.Photo> collect(String theme, int count, GenerationSession session) {
        if (!OpenversePhotoSearch.themes().contains(theme)) return List.of();
        return lookup(session, () -> search.findMany(theme, count));
    }

    private List<OpenversePhotoSearch.Photo> lookup(GenerationSession session, Supplier<List<OpenversePhotoSearch.Photo>> query) {
        if (!session.isRunning()) return List.of();
        session.onPartialResponse("\n正在查找内容配图…\n");
        if (!session.isRunning()) return List.of();
        List<OpenversePhotoSearch.Photo> found;
        try { found = query.get(); }
        catch (RuntimeException unavailable) { found = List.of(); }
        synchronized (session) {
            if (!session.isRunning()) return List.of();
            session.onPartialResponse(found.isEmpty() ? "\n未取得可用配图，使用替代文字。\n" : "\n已准备本地内容配图（CC0）。\n");
        }
        return found;
    }

    void remember(String marker, OpenversePhotoSearch.Photo result) {
        attempted.add(marker);
        if (result != null) photos.put(marker, result);
    }

    String prepare(String html, boolean maySearch, GenerationSession session) {
        return rewriteImages(html, image -> {
            String requested = image.attr(THEME);
            if (maySearch && attempted.isEmpty() && OpenversePhotoSearch.themes().contains(requested)) {
                var first = lookup(session, () -> search.find(requested).stream().toList());
                remember(requested, first.stream().findFirst().orElse(null));
            }
            var photo = photos.get(requested);
            if (photo != null) {
                return image.removeAttr("src").removeAttr("srcset").attr(SOURCE, photo.source())
                        .attr(LICENSE, photo.license()).outerHtml();
            }
            String alt = image.attr("alt");
            var fallback = image.clone().tagName("span").text(alt.isBlank() ? "暂无配图" : alt);
            IMAGE_ATTRIBUTES.forEach(fallback::removeAttr); // 保留按钮所指的 id、hidden、class、aria-* 等通用属性。
            if (attempted.contains(requested)) fallback.attr(UNAVAILABLE, requested);
            return fallback.outerHtml();
        });
    }

    String embed(String html) {
        if (!available()) return html;
        return rewriteImages(html, image -> {
            var photo = photos.get(image.attr(THEME));
            return photo == null ? null : image.attr("src", photo.src()).outerHtml();
        });
    }

    private static String rewriteImages(String html, Function<Element, String> edit) {
        if (!MARKER.matcher(html).find()) return html;
        var body = Parser.htmlParser().setTrackPosition(true).parseInput(html, "").body();
        var images = body.select("img[" + THEME + "]").stream().filter(img -> img.sourceRange().isTracked())
                .sorted(Comparator.comparingInt(img -> img.sourceRange().startPos())).toList();
        var edits = new TreeMap<Range, String>(Comparator.comparingInt(Range::startPos));
        for (var image : images) {
            String value = edit.apply(image);
            if (value == null) continue;
            edits.put(image.sourceRange(), value);
            if (!image.parent().normalName().equals("picture")) continue;
            // 浏览器优先选同一 picture 中 img 之前的 source；只停用这些候选，保留 source 元素和其他图片。
            for (var sibling = image.previousElementSibling(); sibling != null; sibling = sibling.previousElementSibling()) {
                if (sibling.normalName().equals("source") && sibling.hasAttr("srcset") && sibling.sourceRange().isTracked()) {
                    edits.put(sibling.sourceRange(), sibling.removeAttr("srcset").outerHtml());
                }
            }
        }
        if (edits.isEmpty()) return html;
        var out = new StringBuilder();
        int offset = 0;
        for (var editTag : edits.entrySet()) {
            var range = editTag.getKey();
            out.append(html, offset, range.startPos()).append(editTag.getValue());
            offset = range.endPos();
        }
        return out.append(html, offset, html.length()).toString();
    }
}
