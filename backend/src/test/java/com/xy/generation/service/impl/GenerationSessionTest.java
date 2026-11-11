package com.xy.generation.service.impl;

import com.xy.generation.file.GeneratedFileStore;
import com.xy.generation.service.GenerationPersistence;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.model.output.FinishReason;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class GenerationSessionTest {

    private final GenerationPersistence persistence = mock(GenerationPersistence.class);
    private final GeneratedFileStore files = mock(GeneratedFileStore.class);
    private final SseEmitter emitter = mock(SseEmitter.class);
    private final Runnable release = mock(Runnable.class);
    private final GenerationSession session = new GenerationSession(55, 42, 101, emitter, persistence, files, release);

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
}
