package com.xy.generation.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jsoup.Jsoup;

/** 模型只看素材位置和替代文字。发送给模型前暂存图像，交付文件时再还原。 */
final class NativeAssetImages {
    private final ArtworkProviders providers;
    private final Map<String,String> images = new HashMap<>();
    private Optional<String> logo;
    NativeAssetImages(ArtworkProviders providers) { this.providers = providers; }

    Optional<String> logo(ObjectMapper mapper, String design) {
        if (logo == null) logo = providers.logoImage(mapper, design);
        return logo.or(() -> VueBrandLogo.renderSvg(mapper, design));
    }

    String diagram(VueArchitectureDiagram.Diagram diagram) {
        return providers.rendersMermaid() ? "<img data-xy-artwork=\"diagram\" alt=\"架构图\" src=\""
                + providers.diagramImage(diagram) + "\" />" : NativeArchitectureSvg.render(diagram);
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
