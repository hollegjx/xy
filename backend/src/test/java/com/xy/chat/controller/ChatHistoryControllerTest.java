package com.xy.chat.controller;

import com.mybatisflex.core.query.QueryWrapper;
import com.xy.app.mapper.AppMapper;
import com.xy.app.model.entity.App;
import com.xy.chat.mapper.ChatHistoryMapper;
import com.xy.chat.model.entity.ChatHistory;
import com.xy.generation.service.GenerationPersistence;
import com.xy.user.constant.UserConstant;
import com.xy.user.mapper.UserMapper;
import com.xy.user.model.entity.User;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "server.servlet.context-path=/api", "xy.ai.enabled=false",
        "spring.datasource.url=jdbc:mysql://unused.invalid:3306/xy_test",
        "spring.datasource.username=test", "spring.datasource.password=test",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "logging.level.com.xy.exception.GlobalExceptionHandler=OFF"
})
@AutoConfigureMockMvc
class ChatHistoryControllerTest {

    private static final long FIRST_ID = 9007199254740993L;
    @Autowired
    MockMvc mockMvc;
    @MockitoBean
    ChatHistoryMapper messages;
    @MockitoBean
    AppMapper apps;
    @MockitoBean
    UserMapper users;
    @MockitoBean
    GenerationPersistence persistence;

    @BeforeEach
    void prepare() {
        User user = new User();
        user.setId(42L);
        when(users.selectOneById(42L)).thenReturn(user);
        App app = new App();
        app.setId(55L);
        app.setUserId(42L);
        when(apps.selectOneById(55L)).thenReturn(app);
    }

    @Test
    void returnsRecentMessagesWithStatesAndPagesBackwardWithoutRepeatingTheCursor() throws Exception {
        when(messages.selectListByQuery(any(QueryWrapper.class))).thenReturn(
                IntStream.rangeClosed(0, 10).mapToObj(i -> message(11 - i)).toList(),
                List.of(message(1), message(0)), List.of());

        mockMvc.perform(query().session(session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.records.length()").value(10))
                .andExpect(jsonPath("$.data.records[0].id").value(Long.toString(FIRST_ID + 2)))
                .andExpect(jsonPath("$.data.records[9].id").value(Long.toString(FIRST_ID + 11)))
                .andExpect(jsonPath("$.data.records[9].status").value("running"))
                .andExpect(jsonPath("$.data.records[9].content").value(""))
                .andExpect(jsonPath("$.data.records[8].status").value("failed"))
                .andExpect(jsonPath("$.data.records[8].errorMessage").value("生成中断，请重试"))
                .andExpect(jsonPath("$.data.records[7].status").value("cancelled"))
                .andExpect(jsonPath("$.data.records[6].status").value("completed"))
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andExpect(jsonPath("$.data.nextCursor").value(Long.toString(FIRST_ID + 2)));

        mockMvc.perform(query().session(session()).param("beforeId", Long.toString(FIRST_ID + 2)).param("pageSize", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()").value(2))
                .andExpect(jsonPath("$.data.records[0].id").value(Long.toString(FIRST_ID)))
                .andExpect(jsonPath("$.data.records[0].messageRole").value("user"))
                .andExpect(jsonPath("$.data.records[0].content").value("摄影作品集"))
                .andExpect(jsonPath("$.data.records[1].id").value(Long.toString(FIRST_ID + 1)))
                .andExpect(jsonPath("$.data.hasMore").value(false))
                .andExpect(jsonPath("$.data.nextCursor").isEmpty());

        mockMvc.perform(query().session(session()).param("beforeId", Long.toString(FIRST_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records").isEmpty())
                .andExpect(jsonPath("$.data.hasMore").value(false))
                .andExpect(jsonPath("$.data.nextCursor").isEmpty());

        ArgumentCaptor<QueryWrapper> queries = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(messages, times(3)).selectListByQuery(queries.capture());
        List<String> sql = queries.getAllValues().stream().map(QueryWrapper::toSQL).toList();
        assertThat(sql).allSatisfy(query -> assertThat(query).contains("`appId` = 55", "`userId` = 42", "`id` DESC"));
        assertThat(sql.getFirst()).contains("LIMIT 11").doesNotContain("`id` <");
        assertThat(sql.get(1)).contains("`id` < " + (FIRST_ID + 2), "LIMIT 3");
        var order = inOrder(persistence, messages);
        order.verify(persistence).recoverInterruptedGenerations();
        order.verify(messages).selectListByQuery(any(QueryWrapper.class));
    }

    @Test
    void rejectsAnonymousOtherOwnersAndMissingAppsBeforeReadingOrRecoveringMessages() throws Exception {
        mockMvc.perform(query()).andExpect(status().isUnauthorized());
        App otherApp = new App();
        otherApp.setId(55L);
        otherApp.setUserId(84L);
        when(apps.selectOneById(55L)).thenReturn(otherApp);
        for (String role : List.of("user", "admin")) {
            User user = new User();
            user.setId(42L);
            user.setUserRole(role);
            when(users.selectOneById(42L)).thenReturn(user);
            mockMvc.perform(query().session(session()).param("userId", "84"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(40300));
        }
        when(apps.selectOneById(55L)).thenReturn(null);
        mockMvc.perform(query().session(session()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(40400));
        verifyNoInteractions(messages, persistence);
    }

    @Test
    void rejectsInvalidPagingParametersBeforeAccessingMessages() throws Exception {
        for (MockHttpServletRequestBuilder request : List.of(query().param("pageSize", "0"),
                query().param("pageSize", "21"), query().param("beforeId", "0"),
                get("/api/apps/0/messages").contextPath("/api"))) {
            mockMvc.perform(request.session(session()))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40000));
        }
        verifyNoInteractions(apps, messages, persistence);
    }

    private ChatHistory message(int offset) {
        ChatHistory message = new ChatHistory();
        message.setId(FIRST_ID + offset);
        message.setAppId(55L);
        message.setUserId(42L);
        message.setMessageRole(offset == 0 ? "user" : "assistant");
        message.setStatus(switch (offset) {
            case 11 -> "running";
            case 10 -> "failed";
            case 9 -> "cancelled";
            default -> "completed";
        });
        message.setContent(offset == 11 ? "" : "摄影作品集");
        message.setErrorMessage(offset == 10 ? "生成中断，请重试" : null);
        message.setCreateTime(LocalDateTime.of(2026, 11, 11, 10, 0));
        message.setUpdateTime(message.getCreateTime());
        return message;
    }

    private MockHttpServletRequestBuilder query() {
        return get("/api/apps/55/messages").contextPath("/api");
    }

    private MockHttpSession session() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserConstant.LOGIN_USER_ID, 42L);
        return session;
    }
}
