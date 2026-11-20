package com.xy.generation.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class OpenversePhotoSearchTest {
    @Test
    void onlyPresetThemesCanReachOpenverseIncludingTheFlickrFallback() {
        List<String> requests = new ArrayList<>();
        var search = new OpenversePhotoSearch(new ObjectMapper()) {
            @Override byte[] get(String url, int limit) {
                requests.add(url);
                return "{\"results\":[{\"source\":\"museum\"}]}".getBytes(StandardCharsets.UTF_8);
            }
        };
        for (String input : List.of("Alex Example portrait", "Alex", "coffee Alex", "latte", "")) {
            assertThat(search.find(input)).isEmpty();
        }
        assertThat(requests).isEmpty();
        search.find("coffee");
        String fixed = "https://api.openverse.org/v1/images/?q=latte&license=cc0&page_size=1";
        assertThat(requests).containsExactly(fixed, fixed + "&source=flickr");
    }

    @ParameterizedTest
    @ValueSource(strings = {"cc0", "changed-license", "other-photo", "redirect-host", "unavailable", "broken-jpeg"})
    void checksOriginalLicenseAndPhotoBeforeDownloading(String scenario) {
        String page = "https://www.flickr.com/photos/demo/123";
        List<String> requests = new ArrayList<>();
        var search = new OpenversePhotoSearch(new ObjectMapper()) {
            @Override byte[] get(String url, int limit) throws IOException {
                requests.add(url);
                if (url.startsWith("https://api.openverse.org/")) {
                    if (scenario.equals("unavailable")) throw new IOException("timeout");
                    return ("""
                            {"results":[{"license":"cc0","source":"flickr","foreign_landing_url":"%s"}]}
                            """.formatted(scenario.equals("redirect-host") ? "https://invalid.example/123" : page))
                            .getBytes(StandardCharsets.UTF_8);
                }
                if (url.equals(page)) {
                    return ("""
                            <meta property="og:image" content="https://live.staticflickr.com/12/%s_aabb_b.jpg">
                            <div class="photo-license-info"><a rel="license" href="%s"></a></div>
                            """.formatted(scenario.equals("other-photo") ? "456" : "123", scenario.equals("changed-license")
                            ? "https://creativecommons.org/licenses/by/4.0/" : "https://creativecommons.org/publicdomain/zero/1.0/"))
                            .getBytes(StandardCharsets.UTF_8);
                }
                assertThat(url).isEqualTo("https://live.staticflickr.com/12/123_aabb_z.jpg");
                if (scenario.equals("broken-jpeg")) return new byte[]{-1, -40, -1, -39};
                var jpeg = new ByteArrayOutputStream();
                ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "JPEG", jpeg);
                return jpeg.toByteArray();
            }
        };
        if (scenario.equals("cc0")) {
            assertThat(search.find("coffee")).hasValueSatisfying(photo -> {
                assertThat(photo.src()).startsWith("data:image/jpeg;base64,/9j/");
                assertThat(photo.source()).isEqualTo(page);
                assertThat(photo.license()).isEqualTo("CC0");
            });
        } else {
            assertThat(search.find("coffee")).isEmpty();
            assertThat(requests).noneMatch(url -> url.contains("invalid.example"));
            if (!scenario.equals("broken-jpeg")) assertThat(requests).noneMatch(url -> url.startsWith("https://live."));
        }
    }
}
