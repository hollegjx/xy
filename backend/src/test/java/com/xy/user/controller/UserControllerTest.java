package com.xy.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xy.user.mapper.UserMapper;
import com.xy.user.model.dto.UserRegisterRequest;
import com.xy.user.model.entity.User;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "server.servlet.context-path=/api",
        "spring.datasource.url=jdbc:mysql://unused.invalid:3306/xy_test",
        "spring.datasource.username=test",
        "spring.datasource.password=test",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "logging.level.com.xy.exception.GlobalExceptionHandler=OFF"
})
@AutoConfigureMockMvc
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private UserMapper userMapper;

    @Test
    void registersOnlyOrdinaryUsersWithSaltedHashesAndReturnsOnlyTheId() throws Exception {
        AtomicLong nextId = new AtomicLong(40);
        when(userMapper.insert(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(nextId.incrementAndGet());
            return 1;
        });
        String password = "Demo123456!";

        for (int index = 1; index <= 2; index++) {
            String body = objectMapper.writeValueAsString(Map.of(
                    "userAccount", "xy_demo_" + index,
                    "userPassword", password,
                    "checkPassword", password,
                    "userRole", "admin"));
            mockMvc.perform(post("/api/users/register").contextPath("/api")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data").value(Long.toString(40L + index)))
                    .andExpect(jsonPath("$.message").value("ok"));
        }

        ArgumentCaptor<User> users = ArgumentCaptor.forClass(User.class);
        verify(userMapper, times(2)).insert(users.capture());
        for (User user : users.getAllValues()) {
            assertThat(user.getUserRole()).isEqualTo("user");
            assertThat(user.getUserName()).isEqualTo(user.getUserAccount());
            assertThat(user.getUserPassword()).isNotEqualTo(password);
            assertThat(passwordEncoder.matches(password, user.getUserPassword())).isTrue();
        }
        assertThat(users.getAllValues().get(0).getUserPassword())
                .isNotEqualTo(users.getAllValues().get(1).getUserPassword());
    }

    @ParameterizedTest
    @MethodSource("invalidRegistrations")
    void rejectsInvalidRegistrationBeforeSaving(String account, String password, String confirmation,
                                                String expectedMessage) throws Exception {
        String body = objectMapper.writeValueAsString(new UserRegisterRequest(account, password, confirmation));

        mockMvc.perform(post("/api/users/register").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(expectedMessage))
                .andExpect(jsonPath("$.data").doesNotExist());

        verifyNoInteractions(userMapper);
    }

    static Stream<Arguments> invalidRegistrations() {
        return Stream.of(
                Arguments.of(null, "demo", "demo", "账号不能为空"),
                Arguments.of(" ", "demo", "demo", "账号不能为空"),
                Arguments.of("xy_demo", null, "demo", "密码不能为空"),
                Arguments.of("xy_demo", " ", "demo", "密码不能为空"),
                Arguments.of("xy_demo", "demo", null, "确认密码不能为空"),
                Arguments.of("xy_demo", "demo", " ", "确认密码不能为空"),
                Arguments.of("xy_demo", "demo", "different", "两次输入的密码不一致"),
                Arguments.of("a".repeat(129), "demo", "demo", "账号长度不能超过 128 个字符"),
                Arguments.of("😀".repeat(129), "demo", "demo", "账号长度不能超过 128 个字符"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "😀"})
    void acceptsAnAccountAtTheStorageLimit(String character) throws Exception {
        when(userMapper.insert(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(42L);
            return 1;
        });
        String account = character.repeat(128);
        String body = objectMapper.writeValueAsString(new UserRegisterRequest(account, "demo", "demo"));

        mockMvc.perform(post("/api/users/register").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value("42"));

        ArgumentCaptor<User> users = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(users.capture());
        assertThat(users.getValue().getUserAccount()).isEqualTo(account);
        assertThat(users.getValue().getUserName()).isEqualTo(account);
    }

    @Test
    void reportsDuplicateAccountsWithoutExposingDatabaseDetails() throws Exception {
        when(userMapper.insert(any(User.class)))
                .thenThrow(new DuplicateKeyException("internal database constraint details"));
        String body = objectMapper.writeValueAsString(new UserRegisterRequest("xy_demo", "demo", "demo"));

        mockMvc.perform(post("/api/users/register").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("账号已存在"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void rejectsMissingOrMalformedRequestBodies() throws Exception {
        for (String body : new String[]{"", "null", "{"}) {
            mockMvc.perform(post("/api/users/register").contextPath("/api")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000))
                    .andExpect(jsonPath("$.message").value("请求体缺失或 JSON 格式错误"));
        }
        verifyNoInteractions(userMapper);
    }
}
