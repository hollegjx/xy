package com.xy.generation.service.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xy.manager.GeneratedFileManager;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 一份工程内按模型提出的顺序为照片留位置；同主题合查，不同主题可同时查。 */
final class VuePhotos {
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record Credit(String source, String license, String theme, String purpose) {}

    record Use(ToolExecutionRequest call, String path, String theme, String purpose) {
        Credit credit(OpenversePhotoSearch.Photo photo) {
            return new Credit(photo.source(), photo.license(), theme, purpose);
        }

        String file(OpenversePhotoSearch.Photo photo, ObjectMapper mapper) throws JsonProcessingException {
            ObjectNode metadata = mapper.valueToTree(credit(photo));
            return mapper.writeValueAsString(metadata.put("src", photo.src()));
        }
    }

    private final Set<String> usedIds = ConcurrentHashMap.newKeySet();
    private int positions;

    Map<String, List<Use>> group(List<ToolExecutionRequest> calls, ObjectMapper mapper) {
        var groups = new LinkedHashMap<String, List<Use>>();
        for (var call : calls) {
            String theme = "", purpose = "";
            try {
                var args = mapper.readTree(call.arguments());
                if (args != null && args.isObject()) {
                    theme = args.path("theme").asText("");
                    purpose = args.path("purpose").asText("").strip();
                }
            } catch (JsonProcessingException | IllegalArgumentException invalid) { /* 本处无图，其他位置仍可用。 */ }
            String path = ++positions == 1 ? GeneratedFileManager.VUE_PHOTO : "xy-photo-" + positions + ".json";
            groups.computeIfAbsent(theme, ignored -> new ArrayList<>()).add(new Use(call, path, theme, purpose));
        }
        return groups;
    }

    List<OpenversePhotoSearch.Photo> collect(String theme, int count, OpenversePhotoSearch search) {
        try { return search.findMany(theme, count, usedIds); }
        catch (RuntimeException unavailable) { return List.of(); }
    }
}
