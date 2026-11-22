package com.xy.generation.service.impl;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 只记录本次生成工具已经保存的本地素材。 */
final class VueAssetPrompt {
    private record Asset(String kind, LinkedHashSet<String> uses) {}
    private final Map<String, Asset> assets = new LinkedHashMap<>();

    void add(String path, String kind, String use) {
        assets.computeIfAbsent(path, p -> new Asset(kind, new LinkedHashSet<>())).uses().add(use);
    }
    void addAll(VueAssetPrompt batch) {
        batch.assets.forEach((path, asset) -> asset.uses().forEach(use -> add(path, asset.kind(), use)));
    }
    void remove(String path) { assets.remove(path); }
    int size() { return assets.size(); }

    String progress() {
        String kinds = assets.values().stream().map(Asset::kind).collect(Collectors.joining("、"));
        return "\n已将 " + size() + " 项素材加入生成提示：" + kinds + "。\n";
    }

    String manifest() {
        if (assets.isEmpty()) return "";
        var text = new StringBuilder("本轮可用素材（路径相对工程根目录）：\n");
        assets.forEach((path, asset) -> text.append("- ").append(asset.kind()).append("：")
                .append(path).append("；").append(String.join("；", asset.uses())).append('\n'));
        return text.append("按用户需求放到相应位置，从使用它的源码文件相对导入。").toString();
    }

    List<ChatMessage> forGeneration(List<ChatMessage> conversation, String plannedUserPrompt) {
        if (assets.isEmpty() && plannedUserPrompt == null) return List.copyOf(conversation);
        var request = new ArrayList<>(conversation);
        // 工作流已增强首次提示；返工删除素材时，从用户原话更新清单，避免重复附加或留下旧路径。
        for (int i = 0; i < request.size(); i++) {
            if (request.get(i) instanceof UserMessage original) {
                String base = plannedUserPrompt == null ? original.singleText() : plannedUserPrompt;
                String enhanced = assets.isEmpty() ? base : base + "\n\n" + manifest();
                if (!enhanced.equals(original.singleText())) request.set(i, UserMessage.from(enhanced));
                break;
            }
        }
        return request;
    }
}
