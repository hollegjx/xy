package com.xy.generation.service.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xy.manager.GeneratedFileManager;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
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
    static final ToolSpecification TOOL = VueAssetTool.describe(PhotoSlot.class);

    @FunctionalInterface
    private interface Lookup { List<OpenversePhotoSearch.Photo> fetch(int count, Set<String> excluded); }

    // 每个工具调用占一个位置。同主题共用它绑定的图库查询，批次数量和已用原作均由工程决定。
    private record PhotoSlot(ToolExecutionRequest call, String path, OpenversePhotoSearch provider) {
        @Tool("首次创建时，为每个需要真实照片的位置选择预设主题；多处用途可同批调用")
        public Use findPhoto(@P("预设照片主题") OpenversePhotoSearch.Theme theme,
                @P(value = "在页面中的用途或位置，仅用于本地素材清单", required = false) String purpose) {
            return new Use(call, path, theme == null ? "" : theme.name(), purpose == null ? "" : purpose.strip(),
                    (count, excluded) -> theme == null ? List.of() : provider.findMany(theme.name(), count, excluded));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record Credit(String source, String license, String theme, String purpose) {}

    record Use(ToolExecutionRequest call, String path, String theme, String purpose, Lookup lookup) {
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

    private List<Use> uses(List<ToolExecutionRequest> calls, OpenversePhotoSearch search, ObjectMapper mapper) {
        var uses = new ArrayList<Use>();
        for (var call : calls) {
            String path = ++positions == 1 ? GeneratedFileManager.VUE_PHOTO : "xy-photo-" + positions + ".json";
            var slot = new PhotoSlot(call, path, search);
            uses.add(VueAssetTool.call(slot, call, Use.class).orElseGet(() -> {
                String purpose = "";
                try {
                    var raw = mapper.readTree(call.arguments());
                    if (raw != null) purpose = raw.path("purpose").asText("");
                } catch (JsonProcessingException | IllegalArgumentException ignored) { /* 仍为这个位置保留回执。 */ }
                return slot.findPhoto(null, purpose);
            }));
        }
        return uses;
    }

    private static final class Candidates {
        final Map<String, OpenversePhotoSearch.Photo> photos = new LinkedHashMap<>();
        final Set<String> seen;
        final Lookup lookup;
        boolean exhausted;
        Candidates(Lookup lookup, Set<String> delivered) { this.lookup = lookup; this.seen = new HashSet<>(delivered); }

        void fetch(int count) {
            int before = photos.size();
            try {
                for (var photo : lookup.fetch(count, Set.copyOf(seen))) {
                    String id = photo.workId();
                    if (seen.add(id)) photos.put(id, photo);
                }
            } catch (RuntimeException unavailable) { /* 仍可使用这个主题此前已核实的候选。 */ }
            exhausted = photos.size() - before < count;
        }
    }

    CompletableFuture<List<Choice>> collect(List<ToolExecutionRequest> calls, ObjectMapper mapper,
            OpenversePhotoSearch search, Executor workers, BooleanSupplier active) {
        var uses = uses(calls, search, mapper);
        var counts = new LinkedHashMap<String, Integer>();
        var pools = new LinkedHashMap<String, Candidates>();
        uses.forEach(use -> {
            counts.merge(use.theme(), 1, Integer::sum);
            pools.computeIfAbsent(use.theme(), ignored -> new Candidates(use.lookup(), delivered));
        });
        var jobs = new ArrayList<CompletableFuture<Void>>();
        counts.forEach((theme, count) -> jobs.add(CompletableFuture.runAsync(() -> {
            if (active.getAsBoolean()) pools.get(theme).fetch(count);
        }, workers)));
        return CompletableFuture.allOf(jobs.toArray(CompletableFuture<?>[]::new)).thenApplyAsync(unused -> {
            var owners = new LinkedHashMap<String, Use>();
            for (var use : uses) {
                while (active.getAsBoolean()) {
                    var reachable = new LinkedHashSet<Candidates>();
                    if (assign(use, pools, owners, reachable)) break;
                    // 只有互相争用同一原作的主题才需要找备选；全部穷尽则仅这个位置回退。
                    var next = reachable.stream().filter(pool -> !pool.exhausted).findFirst();
                    if (next.isEmpty()) break;
                    next.get().fetch(1);
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
