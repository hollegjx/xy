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
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

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
        String previousSource = messages.stream().filter(AiMessage.class::isInstance)
                .map(AiMessage.class::cast).map(AiMessage::text).findFirst().orElse(null);
        boolean first = previousSource == null;
        var conversation = new ArrayList<>(messages);
        if (first) {
            var system = (SystemMessage) conversation.getFirst();
            conversation.set(0, SystemMessage.from(system.text() + "\n" + ThemeIllustrations.HTML_HINT));
        }
        if (!qualityCheck) {
            if (first) model.chat(conversation, receiveCandidate(session, true, session::onCompleteResponse));
            else GenerationSession.OutputHandler.super.generate(model, messages, session);
            return;
        }
        workflow = new GenerationQualityWorkflow(mode, model, mapper, session, conversation, previousSource,
                () -> candidate.aiMessage().text(),
                done -> model.chat(List.copyOf(conversation), receiveCandidate(session, first, response -> {
                    candidate = response;
                    conversation.add(response.aiMessage());
                    done.run();
                })),
                () -> session.onCompleteResponse(candidate), session::onPartialResponse);
        workflow.run();
    }

    private StreamingChatResponseHandler receiveCandidate(GenerationSession session, boolean first, Consumer<ChatResponse> next) {
        return session.modelStep(true, response -> {
            if (response.finishReason() != FinishReason.STOP) {
                session.fail("模型未正常完成输出，请重试");
                return;
            }
            if (response.aiMessage().text() == null || response.aiMessage().text().isBlank()) {
                session.fail(invalidOutputMessage());
                return;
            }
            try {
                var prepared = first ? embedIllustrations(response, session) : response;
                if (session.isRunning()) next.accept(prepared);
            } catch (IOException error) {
                session.fail("未能嵌入内置插画，请重试");
            }
        });
    }

    private ChatResponse embedIllustrations(ChatResponse response, GenerationSession session) throws IOException {
        String html = response.aiMessage().text();
        Map<String, String> files = null;
        if (mode == CodeGenTypeEnum.MULTI_FILE) {
            try {
                files = parseMultiFile(html);
            } catch (IllegalArgumentException invalid) {
                return response; // 不完整的候选仍可进入质检修复；保存时沿用原有文件校验。
            }
            html = files.get("index.html");
            if (html == null) return response;
        }
        var embedded = ThemeIllustrations.embedInBody(html);
        if (embedded.labels().isEmpty()) return response;
        String output = embedded.html();
        if (files != null) {
            files.put("index.html", output);
            output = mapper.writeValueAsString(files);
        }
        session.onPartialResponse("\n候选页面已嵌入主题插画：" + String.join("、", embedded.labels()) + "。\n");
        return response.toBuilder().aiMessage(AiMessage.from(output)).build();
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
