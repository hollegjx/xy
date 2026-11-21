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
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.BaseSubscriber;

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
    private final NativeContentPhoto photo;
    private GenerationQualityWorkflow workflow;
    private ChatResponse candidate;
    private final NativeCodeStream writer;
    private final NativeAssetImages images;
    private final Disposable.Swap stream = Disposables.swap();

    NativeGenerationOutputHandler(long userId, long appId, long generationId, String codeGenType,
            GeneratedFileManager fileManager, ObjectMapper objectMapper, boolean qualityCheck, OpenversePhotoSearch photoSearch, NativeCodeStream writer, ArtworkProviders artwork) {
        this.writer = writer;
        this.images = new NativeAssetImages(artwork);
        this.userId = userId;
        this.appId = appId;
        this.generationId = generationId;
        this.mode = CodeGenTypeEnum.fromValue(codeGenType);
        this.fileManager = fileManager;
        this.mapper = objectMapper;
        this.qualityCheck = qualityCheck;
        this.photo = new NativeContentPhoto(photoSearch);
        this.multiFileReader = objectMapper.readerFor(JsonNode.class)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @Override
    public void generate(StreamingChatModel model, List<ChatMessage> messages, GenerationSession session) {
        var conversation = new ArrayList<>(messages);
        String previousSource = null;
        int baseline = conversation.size() - 2;
        if (conversation.get(baseline) instanceof AiMessage previous) {
            try {
                // 老回复提供对话线索；只有磁盘中最近成功的版本可以为本轮提供素材。
                for (int i = 1; i < baseline; i++) {
                    if (conversation.get(i) instanceof AiMessage reply) {
                        conversation.set(i, AiMessage.from(editIndex(reply.text(), NativeAssetImages::historyText)));
                    }
                }
                previousSource = editIndex(previous.text(), html -> images.conceal(photo.inherit(html)));
            } catch (IOException error) {
                session.onContextError(error);
                return;
            }
            conversation.set(baseline, AiMessage.from(previousSource));
        }
        boolean first = previousSource == null;
        if (first || photo.available()) {
            var system = (SystemMessage) conversation.getFirst();
            String hint = first ? String.join("\n", ThemeIllustrations.HTML_HINT, NativeContentPhoto.HTML_HINT, NativeVectorAssets.HTML_HINT)
                    : NativeContentPhoto.EDIT_HINT;
            conversation.set(0, SystemMessage.from(system.text() + "\n" + hint));
        }
        if (!qualityCheck) {
            stream(conversation, session, receiveCandidate(session, first, session::onCompleteResponse));
            return;
        }
        workflow = new GenerationQualityWorkflow(mode, model, mapper, session, conversation, previousSource,
                () -> candidate.aiMessage().text(),
                done -> stream(List.copyOf(conversation), session, receiveCandidate(session, first, response -> {
                    candidate = response;
                    conversation.add(response.aiMessage());
                    done.run();
                })),
                null, () -> session.onCompleteResponse(candidate), session::onPartialResponse);
        workflow.run();
    }

    private void stream(List<ChatMessage> messages, GenerationSession session, StreamingChatResponseHandler target) {
        var subscriber = new BaseSubscriber<NativeCodeStream.Chunk>() {
            @Override protected void hookOnNext(NativeCodeStream.Chunk chunk) {
                if (chunk.delta() != null) target.onPartialResponse(chunk.delta());
                if (chunk.finalResponse() != null) target.onCompleteResponse(chunk.finalResponse());
            }
            @Override protected void hookOnError(Throwable error) {
                if (error instanceof dev.langchain4j.guardrail.OutputGuardrailException)
                    session.fail("模型重试后仍未提供完整的网站源码，请调整需求后重试");
                else target.onError(error);
            }
        };
        if (stream.update(subscriber)) writer.generate(messages, session).subscribe(subscriber);
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
                String original = response.aiMessage().text();
                String output = editIndex(original, html -> {
                    if (first) {
                        var illustrated = ThemeIllustrations.embedInBody(html);
                        if (!illustrated.labels().isEmpty()) session.onPartialResponse("\n候选页面已嵌入主题插画："
                                + String.join("、", illustrated.labels()) + "。\n");
                        html = images.conceal(NativeVectorAssets.embed(illustrated.html(), mapper, session::onPartialResponse, images));
                    }
                    return photo.prepare(html, first, session);
                });
                if (session.isRunning()) next.accept(original.equals(output) ? response
                        : response.toBuilder().aiMessage(AiMessage.from(output)).build());
            } catch (IOException error) {
                session.fail("未能准备页面素材，请重试");
            }
        });
    }

    @FunctionalInterface
    private interface HtmlEdit { String apply(String html) throws IOException; }

    private String editIndex(String output, HtmlEdit edit) throws IOException {
        if (mode != CodeGenTypeEnum.MULTI_FILE) return edit.apply(output);
        Map<String, String> files;
        try {
            files = parseMultiFile(output);
        } catch (IllegalArgumentException invalid) {
            return output; // 不完整的候选仍可进入质检修复；保存时沿用原有文件校验。
        }
        String html = files.get("index.html");
        if (html == null) return output;
        String edited = edit.apply(html);
        if (html.equals(edited)) return output;
        files.put("index.html", edited);
        return mapper.writeValueAsString(files);
    }

    @Override
    public void stop() { stream.dispose(); if (workflow != null) workflow.stop(); }

    @Override
    public void save(String output) throws IOException {
        if (mode == CodeGenTypeEnum.MULTI_FILE) {
            var files = parseMultiFile(output);
            files.computeIfPresent("index.html", (key, html) -> images.embed(photo.embed(html)));
            fileManager.saveMultiFile(userId, appId, generationId, files);
        } else {
            fileManager.saveHtml(userId, appId, generationId, images.embed(photo.embed(output)));
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
