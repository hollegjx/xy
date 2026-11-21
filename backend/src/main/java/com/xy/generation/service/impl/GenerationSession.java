package com.xy.generation.service.impl;

import com.xy.chat.model.enums.MessageStatusEnum;
import com.xy.common.ErrorCode;
import com.xy.exception.BusinessException;
import com.xy.generation.model.vo.GenerationEventVO;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.model.output.FinishReason;
import java.io.IOException;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import dev.langchain4j.exception.UnsupportedFeatureException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

// 一次模型调用的状态和 SSE 生命周期；不承担权限判断或数据库事务。
final class GenerationSession implements StreamingChatResponseHandler {

    interface OutputHandler {
        default void generate(StreamingChatModel model, List<ChatMessage> messages, GenerationSession session) {
            model.chat(messages, session);
        }
        void save(String output) throws IOException;
        String invalidOutputMessage();
        default String completedMessage() { return null; }
        default void stop() { }
    }

    private static final Logger log = LoggerFactory.getLogger(GenerationSession.class);
    private final long appId;
    private final long userId;
    private final long generationId;
    private final SseEmitter emitter;
    private final GenerationPersistence persistence;
    private final OutputHandler outputHandler;
    private final Runnable release;
    private final ScheduledExecutorService heartbeatScheduler;
    private final StringBuilder content = new StringBuilder();
    // 同一流可混用带句柄的回调和兼容回调；SDK 为后者合成的无取消能力句柄不能覆盖前者。
    private final Map<Class<?>, StreamingHandle> handles = new HashMap<>();
    private ScheduledFuture<?> heartbeat;
    private boolean finished;

    GenerationSession(long appId, long userId, long generationId, SseEmitter emitter,
            GenerationPersistence persistence, OutputHandler outputHandler, Runnable release,
            ScheduledExecutorService heartbeatScheduler) {
        this.appId = appId;
        this.userId = userId;
        this.generationId = generationId;
        this.emitter = emitter;
        this.persistence = persistence;
        this.outputHandler = outputHandler;
        this.release = release;
        this.heartbeatScheduler = heartbeatScheduler;
        emitter.onTimeout(() -> fail("生成超时，请重试"));
        emitter.onError(error -> fail("生成连接中断，请重试"));
        emitter.onCompletion(() -> fail("生成连接已结束，请重试"));
    }

    synchronized boolean start() {
        if (finished) return false;
        try {
            send("started", MessageStatusEnum.RUNNING, null, null);
            heartbeat = heartbeatScheduler.scheduleAtFixedRate(this::sendHeartbeat, 15, 15, TimeUnit.SECONDS);
            return true;
        } catch (IOException | IllegalStateException error) {
            fail("生成连接中断，请重试");
            return false;
        }
    }

    synchronized boolean isRunning() {
        return !finished;
    }

    long getGenerationId() {
        return generationId;
    }

    void generate(StreamingChatModel model, List<ChatMessage> messages) {
        outputHandler.generate(model, messages, this);
    }

    synchronized void cancel() {
        if (finished) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "该生成不在运行中，请刷新消息状态");
        }
        // 与完成回调共用锁：取消先保存成功才能报告成功；保存失败时保留任务，允许重试。
        persistence.cancel(generationId, content.toString());
        finished = true;
        stopHeartbeat();
        stopModel();
        outputHandler.stop();
        release.run();
        finishStream("cancelled", MessageStatusEnum.CANCELLED, "生成已停止");
    }

    @Override
    public synchronized void onPartialResponse(PartialResponse response, PartialResponseContext context) {
        trackHandle(context.streamingHandle());
        if (finished) return;
        onPartialResponse(response.text());
    }

    synchronized void trackHandle(StreamingHandle current) {
        if (finished) cancelHandle(current);
        else handles.put(current.getClass(), current);
    }

    // 工作流的一次调用只结束当前节点；质检报告不进入 SSE，所有节点仍支持取消。
    StreamingChatResponseHandler modelStep(boolean showText, Consumer<ChatResponse> completed) {
        return modelStep(showText, ignored -> {}, completed);
    }

    StreamingChatResponseHandler modelStep(boolean showText, Consumer<PartialToolCall> onToolFragment,
            Consumer<ChatResponse> completed) {
        return modelStep(showText, onToolFragment, completed, this::onError);
    }

    StreamingChatResponseHandler modelStep(boolean showText, Consumer<PartialToolCall> onToolFragment,
            Consumer<ChatResponse> completed, Consumer<Throwable> failed) {
        return new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String text) {
                if (showText) GenerationSession.this.onPartialResponse(text);
            }
            @Override
            public void onPartialResponse(PartialResponse text, PartialResponseContext context) {
                if (showText) GenerationSession.this.onPartialResponse(text, context);
                else trackHandle(context.streamingHandle());
            }
            @Override
            public void onPartialThinking(PartialThinking text, PartialThinkingContext context) {
                GenerationSession.this.onPartialThinking(text, context);
            }
            @Override
            public void onPartialToolCall(PartialToolCall call) {
                synchronized (GenerationSession.this) {
                    if (!finished && call != null) onToolFragment.accept(call);
                }
            }
            @Override
            public void onPartialToolCall(PartialToolCall call, PartialToolCallContext context) {
                trackHandle(context.streamingHandle());
                onPartialToolCall(call);
            }
            @Override
            public void onError(Throwable error) { failed.accept(error); }
            @Override
            public void onCompleteResponse(ChatResponse response) {
                if (isRunning()) completed.accept(response);
            }
        };
    }

    @Override
    public synchronized void onPartialThinking(PartialThinking thinking, PartialThinkingContext context) {
        trackHandle(context.streamingHandle());
    }

    @Override
    public synchronized void onPartialToolCall(PartialToolCall call, PartialToolCallContext context) {
        trackHandle(context.streamingHandle());
    }

    @Override
    public synchronized void onPartialResponse(String text) {
        if (finished) return;
        content.append(text);
        try {
            send("delta", MessageStatusEnum.RUNNING, text, null);
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
            String output = response.aiMessage().text();
            outputHandler.save(output);
            persistence.complete(appId, userId, generationId, output);
        } catch (IllegalArgumentException error) {
            fail(outputHandler.invalidOutputMessage());
            return;
        } catch (Exception error) {
            logFailure(error);
            fail("生成结果保存失败，请重试");
            return;
        }
        // 数据已经提交。即使最终通知无法送达，也不能把成功版本改成失败。
        finished = true;
        stopHeartbeat();
        release.run();
        finishStream("completed", MessageStatusEnum.COMPLETED, outputHandler.completedMessage());
    }

    @Override
    public synchronized void onError(Throwable error) {
        if (finished) return;
        logFailure(error);
        fail("模型调用失败，请检查模型配置或稍后重试");
    }

    synchronized void onContextError(Throwable error) {
        if (finished) return;
        logFailure(error);
        fail("读取历史或成功源码失败，请检查应用生成记录和文件后重试");
    }

    synchronized void fail(String message) {
        if (finished) return;
        finished = true;
        stopHeartbeat();
        stopModel();
        outputHandler.stop();
        try {
            persistence.fail(generationId, content.toString(), message);
        } catch (RuntimeException error) {
            logFailure(error);
            message = "生成失败，状态待恢复，请稍后查询";
        } finally {
            release.run();
        }
        finishStream("failed", MessageStatusEnum.FAILED, message);
    }

    private synchronized void sendHeartbeat() {
        if (finished) return;
        try {
            emitter.send(SseEmitter.event().comment("keepalive"));
        } catch (IOException | IllegalStateException error) {
            fail("生成连接中断，请重试");
        }
    }

    private void stopHeartbeat() {
        if (heartbeat != null) heartbeat.cancel(false);
    }

    private void stopModel() {
        handles.values().forEach(this::cancelHandle);
        handles.clear();
    }

    private void cancelHandle(StreamingHandle handle) {
        try {
            handle.cancel();
        } catch (UnsupportedFeatureException legacyCallback) {
            // 无上下文分片没有可取消的上游句柄。
        } catch (RuntimeException error) {
            logFailure(error);
        }
    }

    private void send(String event, MessageStatusEnum status, String text, String message) throws IOException {
        emitter.send(SseEmitter.event().name(event).data(
                new GenerationEventVO(Long.toString(generationId), status.getValue(), text, message), MediaType.APPLICATION_JSON));
    }

    private void finishStream(String event, MessageStatusEnum status, String message) {
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
