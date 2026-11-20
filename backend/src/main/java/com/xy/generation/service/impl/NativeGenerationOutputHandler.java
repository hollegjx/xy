package com.xy.generation.service.impl;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.xy.app.model.enums.CodeGenTypeEnum;
import com.xy.manager.GeneratedFileManager;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 处理本次原生生成的完整输出；文件存取仍统一经过 GeneratedFileManager。
final class NativeGenerationOutputHandler implements GenerationSession.OutputHandler {

    private final long userId;
    private final long appId;
    private final long generationId;
    private final CodeGenTypeEnum mode;
    private final GeneratedFileManager fileManager;
    private final ObjectMapper mapper;
    private final ObjectReader multiFileReader;
    private final boolean qualityCheck;
    private GenerationQualityWorkflow workflow;
    private ChatResponse candidate;

    NativeGenerationOutputHandler(long userId, long appId, long generationId, String codeGenType,
            GeneratedFileManager fileManager, ObjectMapper objectMapper, boolean qualityCheck) {
        this.userId = userId;
        this.appId = appId;
        this.generationId = generationId;
        this.mode = CodeGenTypeEnum.fromValue(codeGenType);
        this.fileManager = fileManager;
        this.mapper = objectMapper;
        this.qualityCheck = qualityCheck;
        this.multiFileReader = objectMapper.readerFor(JsonNode.class)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @Override
    public void generate(StreamingChatModel model, List<ChatMessage> messages, GenerationSession session) {
        if (!qualityCheck) {
            GenerationSession.OutputHandler.super.generate(model, messages, session);
            return;
        }
        var conversation = new ArrayList<>(messages);
        String previousSource = messages.stream().filter(AiMessage.class::isInstance)
                .map(AiMessage.class::cast).map(AiMessage::text).findFirst().orElse(null);
        workflow = new GenerationQualityWorkflow(mode, model, mapper, session, conversation, previousSource,
                () -> candidate.aiMessage().text(),
                done -> model.chat(List.copyOf(conversation), session.modelStep(true, response -> {
                    if (response.finishReason() != FinishReason.STOP) {
                        session.fail("模型未正常完成输出，请重试");
                        return;
                    }
                    if (response.aiMessage().text() == null || response.aiMessage().text().isBlank()) {
                        session.fail(invalidOutputMessage());
                        return;
                    }
                    candidate = response;
                    conversation.add(response.aiMessage());
                    done.run();
                })),
                () -> session.onCompleteResponse(candidate), session::onPartialResponse);
        workflow.run();
    }

    @Override
    public void stop() { if (workflow != null) workflow.stop(); }

    @Override
    public void save(String output) throws IOException {
        if (mode == CodeGenTypeEnum.MULTI_FILE) {
            fileManager.saveMultiFile(userId, appId, generationId, parseMultiFile(output));
        } else {
            fileManager.saveHtml(userId, appId, generationId, output);
        }
    }

    @Override
    public String invalidOutputMessage() {
        return mode == CodeGenTypeEnum.MULTI_FILE ? "模型未返回完整的 HTML/CSS/JS 文件，请重试" : "模型未返回完整的 HTML 文档，请重试";
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
