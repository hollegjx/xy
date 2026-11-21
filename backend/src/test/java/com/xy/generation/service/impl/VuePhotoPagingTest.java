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
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class VuePhotoPagingTest {
    @Test
    void aChangedLicenseOnPageOneDoesNotHideTheUsablePhotoFromTheVueDownload(@TempDir Path dir) throws Exception {
        var json = new ObjectMapper();
        var jpeg = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "JPEG", jpeg);
        String gallery = "https://www.flickr.com/photos/demo/";
        var pages = new ArrayList<Integer>();
        var photos = new OpenversePhotoSearch(json) {
            @Override byte[] get(String url, int limit) throws IOException {
                if (url.startsWith("https://api.openverse.org/")) {
                    int page = url.contains("&page=2") ? 2 : 1;
                    pages.add(page);
                    return json.writeValueAsBytes(Map.of("page_count", 2, "results", List.of(
                            Map.of("source", "flickr", "license", "cc0", "foreign_landing_url", gallery + page))));
                }
                if (url.equals(gallery + "1") || url.equals(gallery + "2")) {
                    String id = url.substring(gallery.length());
                    String license = id.equals("1") ? "licenses/by/4.0/" : "publicdomain/zero/1.0/";
                    return ("<meta property='og:image' content='https://live.staticflickr.com/12/" + id + "_aabb.jpg'>"
                            + "<div class='photo-license-info'><a rel='license' href='https://creativecommons.org/" + license + "'></a></div>")
                            .getBytes(StandardCharsets.UTF_8);
                }
                assertThat(url).isEqualTo("https://live.staticflickr.com/12/2_aabb_z.jpg");
                return jpeg.toByteArray();
            }
        };
        var files = new GeneratedFileManager(dir);
        var db = mock(GenerationPersistence.class);
        var model = mock(StreamingChatModel.class);
        doAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            var last = request.messages().getLast();
            AiMessage reply;
            if (last instanceof UserMessage) {
                reply = AiMessage.from(tool("findPhoto", "{\"theme\":\"coffee\"}"));
            } else if (last instanceof ToolExecutionResultMessage result && result.toolName().equals("findPhoto")) {
                String vue = result.text().contains("xy-photo.json")
                        ? "<script setup>import photo from '../xy-photo.json'</script><template><img :src='photo.src'></template>"
                        : "<template>咖啡馆</template>";
                reply = AiMessage.from(tool("writeFile", json.writeValueAsString(Map.of("relativePath", "src/App.vue", "content", vue))));
            } else reply = AiMessage.from("完成");
            invocation.<StreamingChatResponseHandler>getArgument(1).onCompleteResponse(ChatResponse.builder().aiMessage(reply)
                    .finishReason(reply.hasToolExecutionRequests() ? FinishReason.TOOL_EXECUTION : FinishReason.STOP).build());
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        try (var workers = Executors.newVirtualThreadPerTaskExecutor();
             var in = getClass().getResourceAsStream("/templates/vue-project.json")) {
            Map<String, String> template = json.readValue(in, new TypeReference<>() {});
            var output = new VueProjectGeneration(1, 2, 3, files, json, template, mock(VueProjectBuildManager.class),
                    false, photos, workers, 8, mock(ArtworkProviders.class));
            var session = new GenerationSession(2, 1, 3, new SseEmitter(), db, output, () -> {}, mock(ScheduledExecutorService.class));
            session.generate(model, List.of(SystemMessage.from("制作网页"), UserMessage.from("咖啡馆主页")));
        }
        verify(db).complete(eq(2L), eq(1L), eq(3L), anyString());
        assertThat(pages).as("原图许可已变时，继续查询下一页").containsExactly(1, 2);
        try (var archive = files.readVueSourceZip(1, 2, 3); var bytes = new ByteArrayOutputStream()) {
            archive.writeTo(bytes);
            var entries = new HashMap<String, String>();
            try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry())
                    entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
            assertThat(entries.get("src/App.vue")).contains("../xy-photo.json");
            assertThat(json.readValue(entries.get("xy-photo.json"), OpenversePhotoSearch.Photo.class))
                    .isEqualTo(new OpenversePhotoSearch.Photo("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg.toByteArray()),
                            gallery + "2", "CC0"));
        }
    }

    private static ToolExecutionRequest tool(String name, String args) {
        return ToolExecutionRequest.builder().id(name).name(name).arguments(args).build();
    }
}
