package com.xy.generation.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VueBrandLogoTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void polygonTracedEntirelyBackOverItselfHasNoFill() {
        String design = """
                {"name":"折返","layers":[{"shape":"polygon","color":"#24516B","coordinates":[10,10,90,10,10,90,90,10]}]}""";
        assertThat(VueBrandLogo.render(mapper, design)).isEmpty();
    }

    @Test
    void brandNameIsLiteralTextAndOnlyDrawableLocalShapesCanEnterTheComponent() throws Exception {
        String brand = "海 & 帆 · \"日出\" <书店>";
        String design = mapper.writeValueAsString(Map.of("name", brand, "layers", List.of(
                Map.of("shape", "circle", "coordinates", List.of(50, 35, 25), "color", "#345678"))));
        var svg = Jsoup.parse(VueBrandLogo.render(mapper, design).orElseThrow()).selectFirst("svg");
        assertThat(svg.attr("aria-label")).isEqualTo(brand);
        assertThat(svg.children()).extracting(element -> element.tagName()).containsExactly("circle");
        for (String invalid : List.of(design.replace("#345678", "url(https://example.org/color)"),
                design.replace("circle", "image"), design.replace("[50,35,25]", "[50,35,0]"))) {
            assertThat(VueBrandLogo.render(mapper, invalid)).isEmpty();
        }
    }
}
