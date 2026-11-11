package com.xy.generation.service.impl;

import com.xy.generation.file.GeneratedFileStore;
import com.xy.generation.model.vo.GenerationEventVO;
import com.xy.generation.service.GenerationPersistence;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.model.output.FinishReason;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

// 一次模型调用的状态和 SSE 生命周期；不承担权限判断或数据库事务。
final class GenerationSession implements StreamingChatResponseHandler {

    private static final Logger log = LoggerFactory.getLogger(GenerationSession.class);
    private final long appId;
    private final long userId;
    private final long generationId;
    private final SseEmitter emitter;
    private final GenerationPersistence persistence;
    private final GeneratedFileStore fileStore;
    private final Runnable release;
    private final StringBuilder content = new StringBuilder();
    private StreamingHandle handle;
    private boolean finished;

    GenerationSession(long appId, long userId, long generationId, SseEmitter emitter,
            GenerationPersistence persistence, GeneratedFileStore fileStore, Runnable release) {
        this.appId = appId;
        this.userId = userId;
        this.generationId = generationId;
        this.emitter = emitter;
        this.persistence = persistence;
        this.fileStore = fileStore;
        this.release = release;
        emitter.onTimeout(() -> fail("生成超时，请重试"));
        emitter.onError(error -> fail("生成连接中断，请重试"));
        emitter.onCompletion(() -> fail("生成连接已结束，请重试"));
    }

    synchronized boolean start() {
        try {
            send("started", "running", null, null);
            return true;
        } catch (IOException | IllegalStateException error) {
            fail("生成连接中断，请重试");
            return false;
        }
    }

    synchronized boolean isRunning() {
        return !finished;
    }

    @Override
    public synchronized void onPartialResponse(PartialResponse response, PartialResponseContext context) {
        handle = context.streamingHandle();
        if (finished) {
            stopModel();
            return;
        }
        onPartialResponse(response.text());
    }

    @Override
    public synchronized void onPartialThinking(PartialThinking thinking, PartialThinkingContext context) {
        handle = context.streamingHandle();
        if (finished) stopModel();
    }

    @Override
    public synchronized void onPartialResponse(String text) {
        if (finished) return;
        content.append(text);
        try {
            send("delta", "running", text, null);
        } catch (IOException | IllegalStateException error) {
            fail("生成连接中断，请重试");
        }
    }

    @Override
    public synchronized void onCompleteResponse(ChatResponse response) {
        if (finished) return;
        if (response.finishReason() != FinishReason.STOP) {
            fail("模型未正常完成输出，请重试");
            return;
        }
        try {
            String html = response.aiMessage().text();
            fileStore.saveHtml(userId, appId, generationId, html);
            persistence.complete(appId, userId, generationId, html);
        } catch (IllegalArgumentException error) {
            fail("模型未返回完整的 HTML 文档，请重试");
            return;
        } catch (Exception error) {
            logFailure(error);
            fail("生成结果保存失败，请重试");
            return;
        }
        // 数据已经提交。即使最终通知无法送达，也不能把成功版本改成失败。
        finished = true;
        release.run();
        finishStream("completed", "completed", null);
    }

    @Override
    public synchronized void onError(Throwable error) {
        if (finished) return;
        logFailure(error);
        fail("模型调用失败，请检查模型配置或稍后重试");
    }

    private synchronized void fail(String message) {
        if (finished) return;
        finished = true;
        stopModel();
        try {
            persistence.fail(generationId, content.toString(), message);
        } catch (RuntimeException error) {
            logFailure(error);
            message = "生成失败，状态记录未保存，请稍后查询";
        } finally {
            release.run();
        }
        finishStream("failed", "failed", message);
    }

    private void stopModel() {
        if (handle == null) return;
        try {
            handle.cancel();
        } catch (RuntimeException error) {
            logFailure(error);
        }
    }

    private void send(String event, String status, String text, String message) throws IOException {
        emitter.send(SseEmitter.event().name(event).data(
                new GenerationEventVO(Long.toString(generationId), status, text, message), MediaType.APPLICATION_JSON));
    }

    private void finishStream(String event, String status, String message) {
        try {
            send(event, status, null, message);
        } catch (IOException | IllegalStateException error) {
            log.info("生成 {} 的最终通知未送达", generationId);
        } finally {
            emitter.complete();
        }
    }

    private void logFailure(Throwable error) {
        // 网关异常可能包含请求头或响应体，不将上游原文写入日志、消息记录或 SSE。
        log.error("生成 {} 失败，异常类型 {}", generationId, error.getClass().getSimpleName());
    }
}
