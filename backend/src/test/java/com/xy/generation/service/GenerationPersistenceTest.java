package com.xy.generation.service;

import com.xy.app.mapper.AppMapper;
import com.xy.chat.mapper.ChatHistoryMapper;
import com.xy.chat.model.entity.ChatHistory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GenerationPersistenceTest {

    private final ChatHistoryMapper messages = mock(ChatHistoryMapper.class);
    private final AppMapper apps = mock(AppMapper.class);
    private final GenerationPersistence persistence = new GenerationPersistence(messages, apps);

    @Test
    void savesTheUserPromptThenTheRunningAssistantMessage() {
        when(messages.insert(any(ChatHistory.class))).thenAnswer(invocation -> {
            ChatHistory message = invocation.getArgument(0);
            message.setId("assistant".equals(message.getMessageRole()) ? 101L : 100L);
            return 1;
        });
        assertThat(persistence.start(55, 42, "摄影作品集")).isEqualTo(101);
        ArgumentCaptor<ChatHistory> captor = ArgumentCaptor.forClass(ChatHistory.class);
        verify(messages, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(ChatHistory::getMessageRole).containsExactly("user", "assistant");
        assertThat(captor.getAllValues()).extracting(ChatHistory::getStatus).containsExactly("completed", "running");
        assertThat(captor.getAllValues()).allSatisfy(message -> {
            assertThat(message.getAppId()).isEqualTo(55);
            assertThat(message.getUserId()).isEqualTo(42);
        });
    }

    @Test
    void onlyCompletionUpdatesTheSuccessfulVersion() {
        when(messages.finishGeneration(101, "failed", "partial", "failure")).thenReturn(1);
        persistence.fail(101, "partial", "failure");
        verifyNoInteractions(apps);
        when(messages.finishGeneration(102, "completed", "html", null)).thenReturn(1);
        when(apps.updateGeneratedVersion(55, 42, 102)).thenReturn(1);
        persistence.complete(55, 42, 102, "html");
        var order = inOrder(messages, apps);
        order.verify(messages).finishGeneration(102, "completed", "html", null);
        order.verify(apps).updateGeneratedVersion(55, 42, 102);
    }
}
