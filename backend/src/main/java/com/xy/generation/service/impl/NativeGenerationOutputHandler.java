package com.xy.generation.service.impl;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.xy.app.model.enums.CodeGenTypeEnum;
import com.xy.manager.GeneratedFileManager;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

// 处理本次原生生成的完整输出；文件存取仍统一经过 GeneratedFileManager。
final class NativeGenerationOutputHandler {

    private final long userId;
    private final long appId;
    private final long generationId;
    private final boolean multiFile;
    private final GeneratedFileManager fileManager;
    private final ObjectReader multiFileReader;

    NativeGenerationOutputHandler(long userId, long appId, long generationId, String codeGenType,
            GeneratedFileManager fileManager, ObjectMapper objectMapper) {
        this.userId = userId;
        this.appId = appId;
        this.generationId = generationId;
        this.multiFile = CodeGenTypeEnum.MULTI_FILE.getValue().equals(codeGenType);
        this.fileManager = fileManager;
        this.multiFileReader = objectMapper.readerFor(JsonNode.class)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    void save(String output) throws IOException {
        if (multiFile) {
            fileManager.saveMultiFile(userId, appId, generationId, parseMultiFile(output));
        } else {
            fileManager.saveHtml(userId, appId, generationId, output);
        }
    }

    String invalidOutputMessage() {
        return multiFile ? "模型未返回完整的 HTML/CSS/JS 文件，请重试" : "模型未返回完整的 HTML 文档，请重试";
    }

    private Map<String, String> parseMultiFile(String output) {
        try {
            JsonNode node = multiFileReader.readValue(output);
            if (node == null || !node.isObject()) throw new IllegalArgumentException("多文件输出必须是 JSON 对象");
            Map<String, String> files = new LinkedHashMap<>();
            node.properties().forEach(field -> {
                if (!field.getValue().isTextual()) throw new IllegalArgumentException("文件内容必须是字符串");
                files.put(field.getKey(), field.getValue().textValue());
            });
            return files;
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("多文件输出不是完整的 JSON");
        }
    }
}
