package com.xy.generation.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import org.jsoup.Jsoup;

/** 模型只看素材位置和替代文字。发送给模型前暂存图像，交付文件时再还原。 */
final class NativeAssetImages {
    private final ArtworkProviders providers;
    private final Map<String,String> images = new HashMap<>();
    private final Map<VueBrandLogo.DesignKey, FutureTask<Optional<String>>> logos = new ConcurrentHashMap<>();
    NativeAssetImages(ArtworkProviders providers) { this.providers = providers; }

    Optional<String> logo(ObjectMapper mapper, String design) {
        JsonNode parsed;
        try { parsed = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(design); }
        catch (java.io.IOException | IllegalArgumentException invalid) { return Optional.empty(); }
        if (parsed == null || !parsed.isObject()) return Optional.empty();
        var key = VueBrandLogo.designKey(parsed);
        if (key.name().isEmpty()) return Optional.empty();
        // 只在表中登记任务，慢调用在表外运行；同一设计复用，不同设计可同时绘制。
        var task = logos.computeIfAbsent(key, ignored -> new FutureTask<>(
                () -> providers.logoImage(mapper, design).or(() -> VueBrandLogo.renderSvg(mapper, design))));
        task.run();
        try { return task.get(); }
        catch (InterruptedException stopped) { Thread.currentThread().interrupt(); return Optional.empty(); }
        catch (ExecutionException unavailable) { return Optional.empty(); }
    }

    String diagram(VueArchitectureDiagram.Diagram diagram) {
        return providers.rendersMermaid() ? "<img data-xy-artwork=\"diagram\" alt=\"架构图\" src=\""
                + providers.diagramImage(diagram) + "\" />" : NativeArchitectureSvg.render(diagram);
    }

    static String historyText(String html) {
        if (!html.toLowerCase(java.util.Locale.ROOT).contains("data:image/")) return html;
        var doc = Jsoup.parse(html);
        var bitmaps = doc.select("img[data-xy-photo][src^=data:image/], img[data-xy-artwork][src^=data:image/]");
        if (bitmaps.isEmpty()) return html;
        bitmaps.removeAttr("src");
        return doc.outerHtml();
    }

    String conceal(String html) {
        if (!html.contains("data-xy-artwork")) return html;
        var doc = Jsoup.parse(html);
        boolean changed = false;
        for (var image : doc.select("img[data-xy-artwork][src^=data:image/]")) {
            String id = UUID.randomUUID().toString();
            images.put(id, image.attr("src"));
            image.removeAttr("src").attr("data-xy-art-id", id);
            changed = true;
        }
        return changed ? doc.outerHtml() : html;
    }

    String embed(String html) {
        if (images.isEmpty() || !html.contains("data-xy-art-id")) return html;
        var doc = Jsoup.parse(html);
        for (var image : doc.select("img[data-xy-art-id]")) {
            var src = images.get(image.attr("data-xy-art-id"));
            if (src != null) image.attr("src", src);
            image.removeAttr("data-xy-art-id");
        }
        return doc.outerHtml();
    }
}
