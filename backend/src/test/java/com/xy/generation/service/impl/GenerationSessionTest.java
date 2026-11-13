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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GenerationSessionTest {

    private final GenerationPersistence persistence = mock(GenerationPersistence.class);
    private final GeneratedFileManager files = mock(GeneratedFileManager.class);
    private final SseEmitter emitter = mock(SseEmitter.class);
    private final Runnable release = mock(Runnable.class);
    private final GenerationSession session = new GenerationSession(55, 42, 101, emitter, persistence, files,
            "html", new ObjectMapper(), release);

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
                persistence, files, "multi_file", mapper, release);
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
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void messageQueryRepairsAFailureAfterDatabaseRecoveryWithoutRestarting(boolean timedOut) {
        ChatHistoryMapper messages = mock(ChatHistoryMapper.class);
        AppMapper apps = mock(AppMapper.class);
        GenerationPersistence actualPersistence = new GenerationPersistence(messages, apps);
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
                actualPersistence, files, "html", new ObjectMapper(), release);
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
