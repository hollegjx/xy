package com.xy.generation.service.impl;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 只记录本次生成工具已经保存的本地素材。 */
final class VueAssetPrompt {
    private record Asset(String kind, String use) {}
    private final Map<String, Asset> assets = new LinkedHashMap<>();

    void add(String path, String kind, String use) { assets.put(path, new Asset(kind, use)); }
    void remove(String path) { assets.remove(path); }
    int size() { return assets.size(); }

    String progress() {
        String kinds = assets.values().stream().map(Asset::kind).collect(Collectors.joining("、"));
        return "\n已将 " + size() + " 项素材加入生成提示：" + kinds + "。\n";
    }

    List<ChatMessage> forGeneration(List<ChatMessage> conversation) {
        if (assets.isEmpty()) return List.copyOf(conversation);
        var request = new ArrayList<>(conversation);
        // 首次生成只有一条用户需求。只替换发给代码模型的副本，工具调用和回执保持原来的顺序。
        for (int i = 0; i < request.size(); i++) {
            if (request.get(i) instanceof UserMessage original) {
                var text = new StringBuilder(original.singleText()).append("\n\n本轮可用素材（路径相对工程根目录）：\n");
                assets.forEach((path, asset) -> text.append("- ").append(asset.kind()).append("：")
                        .append(path).append("；").append(asset.use()).append('\n'));
                text.append("按用户需求放到相应位置，从使用它的源码文件相对导入。");
                request.set(i, UserMessage.from(text.toString()));
                break;
            }
        }
        return request;
    }
}
