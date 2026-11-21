package com.xy.generation.service.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xy.manager.GeneratedFileManager;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

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

    record Choice(Use use, OpenversePhotoSearch.Photo photo) {}

    private final Set<String> delivered = new HashSet<>();
    private int positions;

    private List<Use> uses(List<ToolExecutionRequest> calls, ObjectMapper mapper) {
        var uses = new ArrayList<Use>();
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
            uses.add(new Use(call, path, theme, purpose));
        }
        return uses;
    }

    private static final class Candidates {
        final Map<String, OpenversePhotoSearch.Photo> photos = new LinkedHashMap<>();
        final Set<String> seen;
        final String theme;
        boolean exhausted;
        Candidates(String theme, Set<String> delivered) { this.theme = theme; this.seen = new HashSet<>(delivered); }

        void fetch(int count, OpenversePhotoSearch search) {
            int before = photos.size();
            try {
                for (var photo : search.findMany(theme, count, Set.copyOf(seen))) {
                    String id = photo.workId();
                    if (seen.add(id)) photos.put(id, photo);
                }
            } catch (RuntimeException unavailable) { /* 仍可使用这个主题此前已核实的候选。 */ }
            exhausted = photos.size() - before < count;
        }
    }

    CompletableFuture<List<Choice>> collect(List<ToolExecutionRequest> calls, ObjectMapper mapper,
            OpenversePhotoSearch search, Executor workers, BooleanSupplier active) {
        var uses = uses(calls, mapper);
        var counts = new LinkedHashMap<String, Integer>();
        uses.forEach(use -> counts.merge(use.theme(), 1, Integer::sum));
        var pools = new LinkedHashMap<String, Candidates>();
        var jobs = new ArrayList<CompletableFuture<Void>>();
        counts.forEach((theme, count) -> {
            var pool = new Candidates(theme, delivered);
            pools.put(theme, pool);
            jobs.add(CompletableFuture.runAsync(() -> { if (active.getAsBoolean()) pool.fetch(count, search); }, workers));
        });
        return CompletableFuture.allOf(jobs.toArray(CompletableFuture<?>[]::new)).thenApplyAsync(unused -> {
            var owners = new LinkedHashMap<String, Use>();
            for (var use : uses) {
                while (active.getAsBoolean()) {
                    var reachable = new LinkedHashSet<Candidates>();
                    if (assign(use, pools, owners, reachable)) break;
                    // 只有互相争用同一原作的主题才需要找备选；全部穷尽则仅这个位置回退。
                    var next = reachable.stream().filter(pool -> !pool.exhausted).findFirst();
                    if (next.isEmpty()) break;
                    next.get().fetch(1, search);
                }
                if (!active.getAsBoolean()) return List.of();
            }
            var assigned = new LinkedHashMap<Use, OpenversePhotoSearch.Photo>();
            owners.forEach((id, use) -> assigned.put(use, pools.get(use.theme()).photos.get(id)));
            delivered.addAll(owners.keySet());
            return uses.stream().map(use -> new Choice(use, assigned.get(use))).toList();
        }, workers);
    }

    // 先用空闲原作；若只剩重叠的候选，尝试把已占用它的位置移到同主题的另一张原作。
    private boolean assign(Use use, Map<String, Candidates> pools, Map<String, Use> owners, Set<Candidates> reachable) {
        var pool = pools.get(use.theme());
        if (!reachable.add(pool)) return false;
        for (var id : pool.photos.keySet()) if (!owners.containsKey(id)) {
            owners.put(id, use);
            return true;
        }
        for (var id : pool.photos.keySet()) if (assign(owners.get(id), pools, owners, reachable)) {
            owners.put(id, use);
            return true;
        }
        return false;
    }
}
