package com.xy.generation.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xy.app.mapper.AppMapper;
import com.xy.app.service.AppService;
import com.xy.chat.mapper.ChatHistoryMapper;
import com.xy.chat.model.dto.ChatHistoryQueryRequest;
import com.xy.chat.model.entity.ChatHistory;
import com.xy.chat.service.impl.ChatHistoryServiceImpl;
import com.xy.manager.GeneratedFileManager;
import com.xy.user.model.entity.User;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.model.output.FinishReason;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GenerationSessionTest {

    private final GenerationPersistence persistence = mock(GenerationPersistence.class);
    private final GeneratedFileManager files = mock(GeneratedFileManager.class);
    private final OpenversePhotoSearch photos = mock(OpenversePhotoSearch.class);
    private final SseEmitter emitter = mock(SseEmitter.class);
    private final Runnable release = mock(Runnable.class);
    private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    private final ScheduledFuture<?> heartbeatTask = mock(ScheduledFuture.class);
    private final GenerationSession session = new GenerationSession(55, 42, 101, emitter, persistence,
            new NativeGenerationOutputHandler(42, 55, 101, "html", files, new ObjectMapper(), false, photos, mock(NativeCodeStream.class)), release, scheduler);

    @Test
    void startsHeartbeatsAfterStartedAndSendsOnlyCommentsWithoutChangingMessageContent() throws Exception {
        verifyNoInteractions(scheduler);
        Runnable heartbeat = startHeartbeat();
        var order = inOrder(emitter, scheduler);
        order.verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        order.verify(scheduler).scheduleAtFixedRate(any(Runnable.class), eq(15L), eq(15L), eq(TimeUnit.SECONDS));
        heartbeat.run();
        heartbeat.run();
        ArgumentCaptor<SseEmitter.SseEventBuilder> events = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, times(3)).send(events.capture());
        for (var event : events.getAllValues().subList(1, 3)) {
            assertThat(event.build()).singleElement().extracting(part -> part.getData()).isEqualTo(":keepalive\n\n");
        }
        verifyNoInteractions(persistence);
        session.cancel();
        verify(persistence).cancel(101, "");
    }

    @ParameterizedTest
    @ValueSource(strings = {"completed", "failed", "cancelled", "timeout"})
    void terminalStatesCancelHeartbeatsAndIgnoreAlreadyQueuedTicks(String outcome) {
        Runnable heartbeat = startHeartbeat();
        switch (outcome) {
            case "completed" -> session.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("html"))
                    .finishReason(FinishReason.STOP).build());
            case "failed" -> session.onError(new IllegalStateException("model failed"));
            case "cancelled" -> session.cancel();
            case "timeout" -> {
                ArgumentCaptor<Runnable> timeout = ArgumentCaptor.forClass(Runnable.class);
                verify(emitter).onTimeout(timeout.capture());
                timeout.getValue().run();
            }
            default -> throw new AssertionError(outcome);
        }
        assertThat(session.isRunning()).isFalse();
        verify(heartbeatTask).cancel(false);
        clearInvocations(emitter);
        heartbeat.run();
        verifyNoInteractions(emitter);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void heartbeatSendFailureUsesConnectionInterruptionHandling(boolean closedEmitter) throws Exception {
        Runnable heartbeat = startHeartbeat();
        StreamingHandle handle = mock(StreamingHandle.class);
        session.onPartialResponse(new PartialResponse("partial"), new PartialResponseContext(handle));
        doThrow(closedEmitter ? new IllegalStateException("closed") : new IOException("disconnected"))
                .when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        heartbeat.run();
        assertThat(session.isRunning()).isFalse();
        verify(persistence).fail(101, "partial", "生成连接中断，请重试");
        verify(handle).cancel();
        verify(heartbeatTask).cancel(false);
        verify(release).run();
        verify(emitter).complete();
    }

    @Test
    void failedStartedSendNeverSchedulesHeartbeats() throws Exception {
        doThrow(new IOException("disconnected")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(session.start()).isFalse();
        verifyNoInteractions(scheduler);
        verify(persistence).fail(101, "", "生成连接中断，请重试");
    }

    private Runnable startHeartbeat() {
        doReturn(heartbeatTask).when(scheduler)
                .scheduleAtFixedRate(any(Runnable.class), eq(15L), eq(15L), eq(TimeUnit.SECONDS));
        assertThat(session.start()).isTrue();
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleAtFixedRate(task.capture(), eq(15L), eq(15L), eq(TimeUnit.SECONDS));
        return task.getValue();
    }

    @Test
    void timeoutStopsTheModelAndLateCompletionCannotOverwriteFailure() {
        StreamingHandle handle = mock(StreamingHandle.class);
        session.onPartialResponse(new PartialResponse("partial"), new PartialResponseContext(handle));
        ArgumentCaptor<Runnable> timeout = ArgumentCaptor.forClass(Runnable.class);
        verify(emitter).onTimeout(timeout.capture());
        timeout.getValue().run();
        session.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("html"))
                .finishReason(FinishReason.STOP).build());
        verify(handle).cancel();
        verify(persistence).fail(101, "partial", "生成超时，请重试");
        verifyNoInteractions(files);
        verify(persistence, never()).complete(eq(55L), eq(42L), eq(101L), any());
        verify(release).run();
    }

    @Test
    void fileWriteFailureNeverUpdatesTheSuccessfulVersion() throws Exception {
        doThrow(new IOException("disk unavailable")).when(files).saveHtml(42, 55, 101, "html");
        session.onPartialResponse("html");
        session.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("html"))
                .finishReason(FinishReason.STOP).build());
        verify(persistence).fail(101, "html", "生成结果保存失败，请重试");
        verify(persistence, never()).complete(eq(55L), eq(42L), eq(101L), any());
        verify(release).run();
    }

    @Test
    void multiFileWriteFailureNeverUpdatesTheSuccessfulVersion() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, String> output = Map.of("index.html", "html", "style.css", "css", "script.js", "js");
        String json = mapper.writeValueAsString(output);
        GenerationSession multiFile = new GenerationSession(55, 42, 101, mock(SseEmitter.class),
                persistence, new NativeGenerationOutputHandler(42, 55, 101, "multi_file", files, mapper, false, photos, mock(NativeCodeStream.class)), release, scheduler);
        doThrow(new IOException("disk unavailable")).when(files).saveMultiFile(42, 55, 101, output);
        multiFile.onPartialResponse(json);
        multiFile.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(json))
                .finishReason(FinishReason.STOP).build());
        verify(persistence).fail(101, json, "生成结果保存失败，请重试");
        verify(persistence, never()).complete(eq(55L), eq(42L), eq(101L), any());
        verify(release).run();
    }

    @Test
    void cancellationBeforeStartPreventsStartingAndStopsALateModelHandle() {
        session.cancel();
        assertThat(session.start()).isFalse();
        assertThat(session.isRunning()).isFalse();
        StreamingHandle handle = mock(StreamingHandle.class);
        session.onPartialResponse(new PartialResponse("late output"), new PartialResponseContext(handle));
        session.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("html"))
                .finishReason(FinishReason.STOP).build());
        verify(persistence).cancel(101, "");
        verify(handle).cancel();
        verify(release).run();
        verifyNoInteractions(files);
        verify(persistence, never()).complete(eq(55L), eq(42L), eq(101L), any());
        verify(persistence, never()).fail(eq(101L), any(), any());
        verifyNoInteractions(scheduler);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void messageQueryRepairsAFailureAfterDatabaseRecoveryWithoutRestarting(boolean timedOut) {
        ChatHistoryMapper messages = mock(ChatHistoryMapper.class);
        AppMapper apps = mock(AppMapper.class);
        GenerationPersistence actualPersistence = new GenerationPersistence(messages, apps, event -> {});
        // 首次恢复早已执行，必须验证同一进程内发生的新失败仍可补写。
        actualPersistence.recoverInterruptedGenerations();
        ChatHistory stored = new ChatHistory();
        stored.setId(101L);
        stored.setMessageRole("assistant");
        stored.setStatus("running");
        stored.setContent("");
        String reason = timedOut ? "生成超时，请重试" : "模型调用失败，请检查模型配置或稍后重试";
        when(messages.finishGeneration(101, "failed", "partial", reason))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"))
                .thenThrow(new DataAccessResourceFailureException("database still unavailable"))
                .thenAnswer(invocation -> {
                    stored.setStatus("failed");
                    stored.setContent("partial");
                    stored.setErrorMessage(reason);
                    return 1;
                });
        when(messages.selectListByQuery(any())).thenReturn(List.of(stored));
        SseEmitter recoveringEmitter = mock(SseEmitter.class);
        GenerationSession recoveringSession = new GenerationSession(55, 42, 101, recoveringEmitter,
                actualPersistence, new NativeGenerationOutputHandler(42, 55, 101, "html", files, new ObjectMapper(), false, photos, mock(NativeCodeStream.class)), release, scheduler);
        StreamingHandle handle = mock(StreamingHandle.class);
        recoveringSession.onPartialResponse(new PartialResponse("partial"), new PartialResponseContext(handle));
        if (timedOut) {
            ArgumentCaptor<Runnable> timeout = ArgumentCaptor.forClass(Runnable.class);
            verify(recoveringEmitter).onTimeout(timeout.capture());
            timeout.getValue().run();
        } else {
            recoveringSession.onError(new IllegalStateException("model failure"));
        }
        assertThat(stored.getStatus()).isEqualTo("running");
        assertThat(recoveringSession.isRunning()).isFalse();
        verify(handle).cancel();
        verify(release).run();

        ChatHistoryServiceImpl history = new ChatHistoryServiceImpl(messages, mock(AppService.class), actualPersistence);
        User user = new User();
        user.setId(42L);
        ChatHistoryQueryRequest query = new ChatHistoryQueryRequest(null, 10);
        assertThatThrownBy(() -> history.listMessages(55, query, user))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verify(messages, never()).selectListByQuery(any());
        var recovered = history.listMessages(55, query, user).records().getFirst();
        assertThat(recovered.status()).isEqualTo("failed");
        assertThat(recovered.content()).isEqualTo("partial");
        assertThat(recovered.errorMessage()).isEqualTo(reason);
        verify(messages).failInterruptedGenerations();
        verifyNoInteractions(apps, files);
    }
}
