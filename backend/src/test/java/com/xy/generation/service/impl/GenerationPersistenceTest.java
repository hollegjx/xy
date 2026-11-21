package com.xy.generation.service.impl;

import com.mybatisflex.core.query.QueryWrapper;
import com.xy.app.mapper.AppMapper;
import com.xy.chat.mapper.ChatHistoryMapper;
import com.xy.chat.model.entity.ChatHistory;
import com.xy.exception.BusinessException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GenerationPersistenceTest {

    private final ChatHistoryMapper messages = mock(ChatHistoryMapper.class);
    private final AppMapper apps = mock(AppMapper.class);
    private final GenerationPersistence persistence = new GenerationPersistence(messages, apps, event -> {});

    @Test
    void restoresSuccessfulPromptsInOrderFromABoundedOwnedHistoryWindow() {
        when(messages.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of(
                history(180, "assistant", "completed", "latest source"),
                history(170, "user", "completed", "改为蓝色"),
                history(160, "assistant", "cancelled", "cancelled partial source"),
                history(140, "user", "completed", "取消的需求"),
                history(130, "assistant", "failed", "failed partial source"),
                history(115, "user", "completed", "失败的需求"),
                history(110, "assistant", "completed", "old source"),
                history(100, "user", "completed", "摄影作品集"),
                history(90, "assistant", "completed", "outside window")));

        assertThat(persistence.loadSuccessfulPrompts(55, 42, 180)).containsExactly("摄影作品集", "改为蓝色");
        ArgumentCaptor<QueryWrapper> query = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(messages).selectListByQuery(query.capture());
        assertThat(query.getValue().toSQL())
                .contains("`appId` = 55", "`userId` = 42", "`id` <= 180", "`id` DESC", "LIMIT 20");
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "other_version", "failed", "cancelled", "user"})
    void rejectsUnavailableOrUnsuccessfulBaselineRecords(String invalid) {
        ChatHistory version = history(invalid.equals("other_version") ? 179 : 180,
                invalid.equals("user") ? "user" : "assistant",
                List.of("failed", "cancelled").contains(invalid) ? invalid : "completed", "source");
        when(messages.selectListByQuery(any(QueryWrapper.class)))
                .thenReturn(invalid.equals("missing") ? List.of() : List.of(version));

        assertThatThrownBy(() -> persistence.loadSuccessfulPrompts(55, 42, 180))
                .isInstanceOf(BusinessException.class).hasMessage("应用成功版本记录不可用");
    }

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
        when(messages.finishGeneration(103, "cancelled", "partial", null)).thenReturn(1);
        persistence.cancel(103, "partial");
        verify(messages).finishGeneration(103, "cancelled", "partial", null);
        verifyNoInteractions(apps);
        when(messages.finishGeneration(102, "completed", "html", null)).thenReturn(1);
        when(apps.updateGeneratedVersion(55, 42, 102)).thenReturn(1);
        persistence.complete(55, 42, 102, "html");
        var order = inOrder(messages, apps);
        order.verify(messages).finishGeneration(102, "completed", "html", null);
        order.verify(apps).updateGeneratedVersion(55, 42, 102);
    }

    @Test
    void recoversOnceAndMakesConcurrentRequestsWaitBeforeStartingNewGenerations() throws Exception {
        CountDownLatch recovering = new CountDownLatch(1);
        CountDownLatch releaseRecovery = new CountDownLatch(1);
        CountDownLatch secondRequest = new CountDownLatch(1);
        when(messages.failInterruptedGenerations()).thenAnswer(invocation -> {
            recovering.countDown();
            assertThat(releaseRecovery.await(5, TimeUnit.SECONDS)).isTrue();
            return 1;
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(persistence::recoverInterruptedGenerations);
            try {
                assertThat(recovering.await(5, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> {
                    secondRequest.countDown();
                    persistence.recoverInterruptedGenerations();
                });
                assertThat(secondRequest.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> second.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                releaseRecovery.countDown();
                first.get(5, TimeUnit.SECONDS);
                second.get(5, TimeUnit.SECONDS);
                persistence.recoverInterruptedGenerations();
                verify(messages).failInterruptedGenerations();
                verifyNoInteractions(apps);
            } finally {
                releaseRecovery.countDown();
            }
        }
    }

    @Test
    void retriesRecoveryAfterFailureBeforeRememberingItAsComplete() {
        when(messages.failInterruptedGenerations()).thenThrow(new IllegalStateException("暂时不可用")).thenReturn(1);
        assertThatThrownBy(persistence::recoverInterruptedGenerations).isInstanceOf(IllegalStateException.class);
        persistence.recoverInterruptedGenerations();
        persistence.recoverInterruptedGenerations();
        verify(messages, times(2)).failInterruptedGenerations();
        verifyNoInteractions(apps);
    }

    @ParameterizedTest
    @ValueSource(strings = {"completed", "failed", "cancelled"})
    void stopsRetryingOnceTheDatabaseConfirmsAnExistingTerminalState(String status) {
        persistence.recoverInterruptedGenerations();
        when(messages.finishGeneration(101, "failed", "partial", "failure"))
                .thenThrow(new DataAccessResourceFailureException("write outcome unknown")).thenReturn(0);
        ChatHistory stored = new ChatHistory();
        stored.setStatus(status);
        when(messages.selectOneById(101L)).thenReturn(stored);

        assertThatThrownBy(() -> persistence.fail(101, "partial", "failure"))
                .isInstanceOf(DataAccessResourceFailureException.class);
        persistence.recoverInterruptedGenerations();
        persistence.recoverInterruptedGenerations();

        verify(messages, times(2)).finishGeneration(101, "failed", "partial", "failure");
        verify(messages).selectOneById(101L);
        verify(messages).failInterruptedGenerations();
        assertThat(stored.getStatus()).isEqualTo(status);
        verifyNoInteractions(apps);
    }

    private ChatHistory history(long id, String role, String status, String content) {
        ChatHistory message = new ChatHistory();
        message.setId(id);
        message.setAppId(55L);
        message.setUserId(42L);
        message.setMessageRole(role);
        message.setStatus(status);
        message.setContent(content);
        return message;
    }
}
