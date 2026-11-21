package com.xy.generation.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xy.manager.GeneratedFileManager;
import com.xy.manager.VueProjectBuildManager;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.model.output.FinishReason;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class VuePhotoCollectionTest {
    private final ObjectMapper json = new ObjectMapper();
    private static final String GALLERY = "https://www.flickr.com/photos/demo/";

    @Test
    void collectsByPurposeInParallelAndShipsDistinctOfflinePhotosAcrossToolRounds(@TempDir Path dir) throws Exception {
        var started = new CountDownLatch(2);
        var visits = new CopyOnWriteArrayList<String>();
        var originals = Map.of(101, jpeg(Color.RED), 102, jpeg(Color.GREEN), 103, jpeg(Color.BLUE), 201, jpeg(Color.YELLOW));
        var photos = new OpenversePhotoSearch(json) {
            @Override byte[] get(String url, int limit) throws IOException {
                visits.add(url);
                if (url.startsWith("https://api.openverse.org")) {
                    int page = Integer.parseInt(url.substring(url.lastIndexOf('=') + 1));
                    boolean nature = url.contains("q=forest+landscape"), pair = url.contains("q=latte") && url.contains("page_size=2");
                    if (nature || pair) {
                        started.countDown();
                        try { assertThat(started.await(3, TimeUnit.SECONDS)).as("两个主题正在同时查询").isTrue(); }
                        catch (InterruptedException error) { throw new IOException(error); }
                    }
                    List<String> ids = nature ? List.of(GALLERY + 201) : pair ? List.of(GALLERY + 101, GALLERY + 102)
                            : url.contains("q=fresh+food") ? List.of()
                            : List.of(page < 3 ? "https://www.flickr.com/photos/alias/" + (100 + page) + "/" : GALLERY + 103);
                    return json.writeValueAsBytes(Map.of("page_count", 4, "results", ids.stream().map(id ->
                            Map.of("source", "flickr", "license", "cc0", "foreign_landing_url", id)).toList()));
                }
                if (url.startsWith(GALLERY)) {
                    String id = url.substring(GALLERY.length());
                    return ("<meta property='og:image' content='https://live.staticflickr.com/12/" + id + "_aabb.jpg'>"
                            + "<div class='photo-license-info'><a rel='license' href='https://creativecommons.org/publicdomain/zero/1.0/'></a></div>")
                            .getBytes(StandardCharsets.UTF_8);
                }
                assertThat(url).startsWith("https://live.staticflickr.com/12/");
                return originals.get(Integer.parseInt(url.substring(url.lastIndexOf('/') + 1, url.indexOf('_'))));
            }
        };
        String page = """
                <script setup>
                import hero from '../xy-photo.json'
                import terrace from '../xy-photo-2.json'
                import menu from '../xy-photo-3.json'
                import album from '../xy-photo-5.json'
                </script>
                <template><img v-for="photo in [hero, terrace, menu, album]" :src="photo.src" :alt="photo.purpose"><p>糕点清单</p></template>""";
        var files = new GeneratedFileManager(dir);
        var db = mock(GenerationPersistence.class);
        var model = mock(StreamingChatModel.class);
        var rounds = new AtomicInteger();
        doAnswer(i -> {
            ChatRequest request = i.getArgument(0);
            String input = ((UserMessage) request.messages().get(1)).singleText();
            assertThat(request.messages().toString()).doesNotContain("data:image/jpeg;base64,");
            AiMessage reply = switch (rounds.incrementAndGet()) {
                case 1 -> AiMessage.from(List.of(photo("coffee", "横幅"), photo("nature", "露台"),
                        photo("coffee", "菜单"), photo("food", "糕点")));
                case 2 -> {
                    assertThat(input).containsSubsequence("xy-photo.json", "横幅", "xy-photo-2.json", "露台", "xy-photo-3.json", "菜单")
                            .doesNotContain("xy-photo-4.json");
                    var receipts = request.messages().stream().filter(ToolExecutionResultMessage.class::isInstance)
                            .map(ToolExecutionResultMessage.class::cast).toList();
                    assertThat(receipts).extracting(ToolExecutionResultMessage::id).containsExactly("横幅", "露台", "菜单", "糕点");
                    assertThat(receipts.getLast().text()).contains("无图布局");
                    yield AiMessage.from(photo("coffee", "图集"));
                }
                case 3 -> {
                    assertThat(input).containsSubsequence("xy-photo-5.json", "图集");
                    yield AiMessage.from(ToolExecutionRequest.builder().id("write").name("writeFile")
                            .arguments(json.writeValueAsString(Map.of("relativePath", "src/App.vue", "content", page))).build());
                }
                default -> AiMessage.from("完成");
            };
            i.<StreamingChatResponseHandler>getArgument(1).onCompleteResponse(ChatResponse.builder().aiMessage(reply)
                    .finishReason(reply.hasToolExecutionRequests() ? FinishReason.TOOL_EXECUTION : FinishReason.STOP).build());
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        try (var workers = Executors.newVirtualThreadPerTaskExecutor(); var in = getClass().getResourceAsStream("/templates/vue-project.json")) {
            var output = new VueProjectGeneration(1, 2, 3, files, json, json.readValue(in, new TypeReference<>() {}),
                    mock(VueProjectBuildManager.class), false, photos, workers, 8, mock(ArtworkProviders.class));
            var session = new GenerationSession(2, 1, 3, new SseEmitter(), db, output, () -> {}, mock(ScheduledExecutorService.class));
            session.generate(model, List.of(SystemMessage.from("制作网站"), UserMessage.from("制作咖啡馆网站")));
        }
        verify(db).complete(eq(2L), eq(1L), eq(3L), anyString());
        assertThat(visits).noneMatch(url -> url.contains("/alias/") || url.contains("&page=4") || url.contains("横幅"));
        var expected = Map.of("xy-photo.json", 101, "xy-photo-2.json", 201, "xy-photo-3.json", 102, "xy-photo-5.json", 103);
        var purposes = Map.of(101, "横幅", 201, "露台", 102, "菜单", 103, "图集");
        try (var archive = files.readVueSourceZip(1, 2, 3); var bytes = new ByteArrayOutputStream()) {
            archive.writeTo(bytes);
            var found = new HashSet<String>();
            try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                    if (!entry.getName().startsWith("xy-photo")) continue;
                    found.add(entry.getName());
                    int id = expected.get(entry.getName());
                    var saved = json.readTree(zip.readAllBytes());
                    assertThat(saved.path("purpose").asText()).isEqualTo(purposes.get(id));
                    assertThat(saved.path("source").asText()).isEqualTo(GALLERY + id);
                    assertThat(saved.path("license").asText()).isEqualTo("CC0");
                    assertThat(saved.path("src").asText()).isEqualTo("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(originals.get(id)));
                }
            }
            assertThat(found).isEqualTo(expected.keySet());
        }
    }

    private ToolExecutionRequest photo(String theme, String purpose) throws IOException {
        return ToolExecutionRequest.builder().id(purpose).name("findPhoto")
                .arguments(json.writeValueAsString(Map.of("theme", theme, "purpose", purpose))).build();
    }

    private static byte[] jpeg(Color color) throws IOException {
        var image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 3; x++) for (int y = 0; y < 2; y++) image.setRGB(x, y, color.getRGB());
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "JPEG", out);
        return out.toByteArray();
    }
}
