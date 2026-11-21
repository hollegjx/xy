package com.xy.user.controller;

import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.xy.app.controller.AppController;
import com.xy.app.mapper.AppMapper;
import com.xy.app.model.entity.App;
import com.xy.app.model.enums.CodeGenTypeEnum;
import com.xy.app.service.impl.GenerationModeRouter;
import com.xy.app.service.impl.AppServiceImpl;
import com.xy.app.service.impl.FeaturedAppPages;
import com.xy.chat.mapper.ChatHistoryMapper;
import com.xy.common.UserConstant;
import com.xy.common.SessionUserResolver;
import com.xy.config.DeploymentConfiguration;
import com.xy.exception.GlobalExceptionHandler;
import com.xy.manager.GeneratedFileManager;
import com.xy.user.mapper.UserMapper;
import com.xy.user.model.entity.User;
import com.xy.user.model.enums.UserRoleEnum;
import com.xy.user.service.UserDeletionService;
import com.xy.user.service.UserService;
import com.xy.user.service.impl.UserDeletionPersistence;
import com.xy.user.service.impl.UserDeletionServiceImpl;
import com.xy.user.service.impl.RoleAuthorizationAspect;
import com.xy.user.service.impl.DatabaseUserIdentityLookup;
import com.xy.user.service.impl.UserServiceImpl;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserDeleteControllerTest {

    private static final String HTML = "<!DOCTYPE html><html><head></head><body>用户作品</body></html>";
    @TempDir
    Path directory;
    private final UserMapper users = mock(UserMapper.class);
    private final AppMapper apps = mock(AppMapper.class);
    private final ChatHistoryMapper messages = mock(ChatHistoryMapper.class);
    private final List<App> ownedApps = new ArrayList<>();
    private MockMvc mockMvc;
    private GeneratedFileManager files;
    private FeaturedAppPages featuredPages;
    private User admin;
    private User member;

    @BeforeEach
    void prepare() {
        files = new GeneratedFileManager(directory.resolve("generated"));
        var deployment = new DeploymentConfiguration(directory.resolve("published"), "https://sites.example.test", "http://localhost:5173");
        featuredPages = spy(new FeaturedAppPages(apps, files));
        var appService = new AppServiceImpl(apps, messages, files, deployment,
                mock(GenerationModeRouter.class), Optional.empty(), featuredPages);
        var accounts = new AspectJProxyFactory(new UserServiceImpl(users, mock(PasswordEncoder.class)));
        accounts.addAspect(RoleAuthorizationAspect.class);
        UserService userService = accounts.getProxy();
        var removals = new AspectJProxyFactory(new UserDeletionServiceImpl(users, appService, new UserDeletionPersistence(users, apps)));
        removals.addAspect(RoleAuthorizationAspect.class);
        UserDeletionService deletion = removals.getProxy();
        var sessionUsers = new SessionUserResolver<>(new DatabaseUserIdentityLookup(users)::findById);
        // 只替换数据访问；使用真实业务类和临时文件，不连接数据库，也不测试事务框架本身。
        mockMvc = MockMvcBuilders.standaloneSetup(new UserController(userService), new UserDeletionController(deletion, sessionUsers),
                        new AppController(appService, sessionUsers))
                .setControllerAdvice(new GlobalExceptionHandler()).build();

        admin = user(42L, UserRoleEnum.ADMIN);
        member = user(84L, UserRoleEnum.USER);
        when(users.selectOneById(42L)).thenReturn(admin);
        when(users.selectOneById(84L)).thenAnswer(call -> member.getIsDelete() == 0 ? member : null);
        when(users.selectOneByQuery(any(QueryWrapper.class)))
                .thenAnswer(call -> member.getIsDelete() == 0 ? member : null);
        when(users.paginate(eq(1), eq(10), any(QueryWrapper.class))).thenAnswer(call -> {
            List<User> records = member.getIsDelete() == 0 ? List.of(admin, member) : List.of(admin);
            return new Page<>(records, 1, 10, records.size());
        });

        ownedApps.add(app(55L, "a".repeat(32)));
        ownedApps.add(app(56L, null));
        ownedApps.add(app(57L, "b".repeat(32)));
        when(apps.selectListByQuery(any(QueryWrapper.class))).thenReturn(ownedApps);
        when(apps.selectOneById(anyLong())).thenAnswer(call -> ownedApps.stream()
                .filter(app -> app.getId().equals(call.getArgument(0)) && app.getIsDelete() == 0)
                .findFirst().orElse(null));
        when(apps.deleteByQuery(any(QueryWrapper.class))).thenAnswer(call -> {
            for (App app : ownedApps) {
                if (app.getDeployKey() != null) assertThat(publishedIndex(app.getDeployKey())).doesNotExist();
                app.setIsDelete(1);
            }
            return ownedApps.size();
        });
        when(users.deleteById(84L)).thenAnswer(call -> {
            assertThat(ownedApps).allSatisfy(app -> assertThat(app.getIsDelete()).isEqualTo(1));
            member.setIsDelete(1);
            return 1;
        });
    }

    @Test
    void deletesTheAccountAndItsAppsAfterRemovingOnlyItsPublicPages() throws Exception {
        files.saveHtml(84, 55, 101, HTML);
        files.publishHtml(directory.resolve("published"), "a".repeat(32), HTML);
        files.publishHtml(directory.resolve("published"), "b".repeat(32), HTML);
        files.publishHtml(directory.resolve("published"), "c".repeat(32), HTML);
        MockHttpSession memberSession = session(84L);

        mockMvc.perform(remove(84L).session(session(42L)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(true));

        assertThat(member.getIsDelete()).isEqualTo(1);
        assertThat(ownedApps).allSatisfy(app -> assertThat(app.getIsDelete()).isEqualTo(1));
        assertThat(publishedIndex("a".repeat(32))).doesNotExist();
        assertThat(publishedIndex("b".repeat(32))).doesNotExist();
        assertThat(Files.readString(publishedIndex("c".repeat(32)))).isEqualTo(HTML);
        assertThat(Files.readString(directory.resolve("generated/84/55/101/index.html"))).isEqualTo(HTML);
        assertThat(admin.getIsDelete()).isZero();

        ArgumentCaptor<QueryWrapper> selection = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(apps).selectListByQuery(selection.capture());
        assertThat(selection.getValue().toSQL()).contains("`userId` = 84");
        ArgumentCaptor<QueryWrapper> deletion = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(apps).deleteByQuery(deletion.capture());
        assertThat(deletion.getValue().toSQL()).contains("`userId` = 84");
        verify(users).deleteById(84L);
        verifyNoInteractions(messages);

        mockMvc.perform(get("/api/users").contextPath("/api").session(session(42L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value("42"));
        mockMvc.perform(get("/api/users/me").contextPath("/api").session(memberSession))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(40100));
        assertThat(memberSession.getAttribute(UserConstant.LOGIN_USER_ID)).isNull();
        mockMvc.perform(post("/api/users/login").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userAccount\":\"xy_member\",\"userPassword\":\"Demo123456!\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40000));
        for (App app : ownedApps) {
            mockMvc.perform(get("/api/apps/{id}", app.getId()).contextPath("/api").session(session(42L)))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(40400));
        }
    }

    @Test
    void removingTheOwnerDropsTheirFeaturedCardAfterTheAppsAreDeleted() throws Exception {
        App featured = ownedApps.getFirst();
        featured.setAppName("待下线作品");
        featured.setFeatured(true);
        featured.setPublishedVersion(101L);
        when(apps.selectListByQuery(any(QueryWrapper.class))).thenAnswer(call -> {
            QueryWrapper query = call.getArgument(0);
            if (!query.toSQL().contains("`featured`")) return ownedApps;
            return featured.getIsDelete() == 0 ? List.of(featured) : List.of();
        });
        when(apps.deleteByQuery(any(QueryWrapper.class))).thenAnswer(call -> {
            // 模拟另一名访客在删除写入之前刷新：首页此时仍能读到旧记录。
            mockMvc.perform(get("/api/apps/featured").contextPath("/api"))
                    .andExpect(jsonPath("$.data[0].appName").value("待下线作品"));
            ownedApps.forEach(app -> app.setIsDelete(1));
            return ownedApps.size();
        });
        mockMvc.perform(get("/api/apps/featured").contextPath("/api"))
                .andExpect(jsonPath("$.data[0].appName").value("待下线作品"));
        mockMvc.perform(remove(84L).session(session(42L))).andExpect(status().isOk());
        var removal = inOrder(users, featuredPages);
        removal.verify(users).deleteById(84L);
        removal.verify(featuredPages).changeRevision();
        mockMvc.perform(get("/api/apps/featured").contextPath("/api")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void deletesAnAccountWithoutAppsAndRejectsRepeatedDeletionOrInvalidIds() throws Exception {
        ownedApps.clear();
        mockMvc.perform(remove(84L).session(session(42L)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(true));
        assertThat(directory.resolve("published")).doesNotExist();
        mockMvc.perform(remove(84L).session(session(42L)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(40400));
        mockMvc.perform(remove(0L).session(session(42L)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40000));
        verify(users).deleteById(84L);
    }

    @Test
    void rejectsAnonymousAndOrdinaryUsersBeforeAccessingApps() throws Exception {
        mockMvc.perform(remove(84L))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(40100));
        verifyNoInteractions(users);
        mockMvc.perform(remove(84L).session(session(84L)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(40300));
        verifyNoInteractions(apps);
        verify(users, never()).deleteById(anyLong());
    }

    @Test
    void protectsTheCurrentAdminAndOtherAdminAccounts() throws Exception {
        member.setUserRole(UserRoleEnum.ADMIN.getValue());
        for (long id : new long[]{42L, 84L}) {
            mockMvc.perform(remove(id).session(session(42L)))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(50001));
        }
        verifyNoInteractions(apps);
        verify(users, never()).deleteById(anyLong());
    }

    @Test
    void keepsTheAccountAndAppsWhenPublicRemovalFailsAndAllowsRetry() throws Exception {
        files.saveHtml(84, 55, 101, HTML);
        files.publishHtml(directory.resolve("published"), "a".repeat(32), HTML);
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Files.writeString(outside.resolve("index.html"), HTML);
        Path linkedDirectory = publishedIndex("b".repeat(32)).getParent();
        Files.createSymbolicLink(linkedDirectory, outside);

        mockMvc.perform(remove(84L).session(session(42L)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(50000))
                .andExpect(jsonPath("$.message").value("移除用户作品失败，账号尚未删除，请重试"));
        assertThat(member.getIsDelete()).isZero();
        assertThat(ownedApps).allSatisfy(app -> assertThat(app.getIsDelete()).isZero());
        assertThat(publishedIndex("a".repeat(32))).doesNotExist();
        assertThat(Files.readString(outside.resolve("index.html"))).isEqualTo(HTML);
        verify(users, never()).deleteById(anyLong());
        verify(apps, never()).deleteByQuery(any(QueryWrapper.class));

        Files.delete(linkedDirectory);
        mockMvc.perform(remove(84L).session(session(42L)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(true));
        assertThat(member.getIsDelete()).isEqualTo(1);
        assertThat(Files.readString(outside.resolve("index.html"))).isEqualTo(HTML);
    }

    private User user(long id, UserRoleEnum role) {
        User user = new User();
        user.setId(id);
        user.setUserAccount(id == 84L ? "xy_member" : "xy_admin");
        user.setUserName("测试用户");
        user.setUserRole(role.getValue());
        user.setIsDelete(0);
        return user;
    }

    private App app(long id, String deployKey) {
        App app = new App();
        app.setId(id);
        app.setUserId(84L);
        app.setCodeGenType(CodeGenTypeEnum.HTML.getValue());
        app.setDeployKey(deployKey);
        app.setIsDelete(0);
        return app;
    }

    private Path publishedIndex(String deployKey) {
        return directory.resolve("published").resolve(deployKey).resolve("index.html");
    }

    private MockHttpServletRequestBuilder remove(long id) {
        return delete("/api/users/{id}", id).contextPath("/api");
    }

    private MockHttpSession session(long userId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserConstant.LOGIN_USER_ID, userId);
        return session;
    }
}
