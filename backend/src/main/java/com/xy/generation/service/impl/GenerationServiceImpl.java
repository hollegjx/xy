package com.xy.generation.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xy.app.model.entity.App;
import com.xy.app.model.enums.CodeGenTypeEnum;
import com.xy.app.model.vo.AppVO;
import com.xy.app.service.AppService;
import com.xy.common.ErrorCode;
import com.xy.exception.BusinessException;
import com.xy.generation.model.dto.GenerationRequest;
import com.xy.generation.service.GenerationService;
import com.xy.manager.GeneratedFileManager;
import com.xy.manager.PromptInputGuard;
import com.xy.manager.VueProjectBuildManager;
import com.xy.user.model.entity.User;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class GenerationServiceImpl implements GenerationService {

    private final AppService appService;
    private final GenerationMemory memory;
    private final ArtworkProviders artwork;
    private final GenerationPersistence persistence;
    private final GeneratedFileManager fileManager;
    private final VueProjectBuildManager vueBuilder;
    private final OpenversePhotoSearch photoSearch;
    private final GenerationModels models;
    private final Executor executor;
    private final ScheduledExecutorService heartbeatScheduler;
    private final Map<String, String> systemPrompts;
    private final Map<String, String> vueTemplate;
    private final ObjectMapper objectMapper;
    private final long timeoutMillis;
    private final int maxToolRounds;
    // 当前部署为单实例；保证较早的生成不会晚于后一次生成覆盖成功版本。
    private final Map<Long, GenerationSession> runningSessions = new ConcurrentHashMap<>();

    public GenerationServiceImpl(AppService appService, GenerationPersistence persistence,
            GeneratedFileManager fileManager, GenerationModels models,
            @Qualifier("generationExecutor") Executor executor,
            @Qualifier("generationHeartbeatScheduler") ScheduledExecutorService heartbeatScheduler,
            @Value("classpath:prompts/single-html.txt") Resource htmlPrompt,
            @Value("classpath:prompts/multi-file.txt") Resource multiFilePrompt,
            ObjectMapper objectMapper, GenerationMemory memory, ArtworkProviders artwork, VueProjectBuildManager vueBuilder, OpenversePhotoSearch photoSearch,
            @Value("${xy.ai.timeout-seconds:180}") long timeoutSeconds,
            @Value("${xy.ai.max-tool-rounds:40}") int maxToolRounds) throws IOException {
        this.appService = appService;
        this.memory = memory;
        this.artwork = artwork;
        this.persistence = persistence;
        this.fileManager = fileManager;
        this.models = models;
        this.executor = executor;
        this.heartbeatScheduler = heartbeatScheduler;
        this.systemPrompts = Map.of(CodeGenTypeEnum.HTML.getValue(), htmlPrompt.getContentAsString(StandardCharsets.UTF_8),
                CodeGenTypeEnum.MULTI_FILE.getValue(), multiFilePrompt.getContentAsString(StandardCharsets.UTF_8),
                CodeGenTypeEnum.VUE_PROJECT.getValue(),
                new ClassPathResource("prompts/vue-project.txt").getContentAsString(StandardCharsets.UTF_8));
        this.vueTemplate = objectMapper.readValue(
                new ClassPathResource("templates/vue-project.json").getContentAsString(StandardCharsets.UTF_8),
                new TypeReference<Map<String, String>>() {});
        this.objectMapper = objectMapper;
        this.vueBuilder = vueBuilder;
        this.photoSearch = photoSearch;
        if (maxToolRounds < 1) throw new IllegalArgumentException("工具调用轮数须大于 0");
        this.maxToolRounds = maxToolRounds;
        this.timeoutMillis = Math.multiplyExact(timeoutSeconds, 1000);
    }

    @Override
    public SseEmitter generate(long appId, GenerationRequest request, User loginUser) {
        AppVO app = appService.getAppById(appId, loginUser);
        PromptInputGuard.check(request.prompt());
        String systemPrompt = systemPrompts.get(app.codeGenType());
        if (systemPrompt == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "当前生成仅支持 html、multi_file 或 vue_project 模式");
        }
        boolean qualityCheck = Boolean.TRUE.equals(request.qualityCheck());
        var client = models.forApp(loginUser.getId(), appId, CodeGenTypeEnum.fromValue(app.codeGenType()));
        if (client == null) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "请先配置并启用模型服务");
        }
        persistence.recoverInterruptedGenerations();
        SseEmitter emitter = new SseEmitter(timeoutMillis);
        GenerationSession session = runningSessions.compute(appId, (id, current) -> {
            if (current != null) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "该应用正在生成，请等待本次结束");
            }
            long userId = loginUser.getId();
            long generationId = persistence.start(appId, userId, request.prompt());
            GenerationSession.OutputHandler outputHandler = CodeGenTypeEnum.VUE_PROJECT.getValue().equals(app.codeGenType())
                    ? new VueProjectGeneration(loginUser.getId(), appId, generationId, fileManager, objectMapper,
                            vueTemplate, vueBuilder, qualityCheck, photoSearch, executor, maxToolRounds, artwork)
                    : new NativeGenerationOutputHandler(
                            loginUser.getId(), appId, generationId, app.codeGenType(), fileManager, objectMapper, qualityCheck, photoSearch, client.writer(), artwork);
            return new GenerationSession(appId, loginUser.getId(), generationId,
                    emitter, persistence, outputHandler, () -> runningSessions.remove(appId), heartbeatScheduler);
        });
        if (session.start()) {
            try {
                executor.execute(() -> {
                    if (!session.isRunning()) return;
                    List<ChatMessage> messages;
                    try {
                        messages = buildMessages(appId, loginUser, session.getGenerationId(), systemPrompt, request.prompt());
                    } catch (IOException | RuntimeException error) {
                        session.onContextError(error);
                        return;
                    }
                    if (!session.isRunning()) return;
                    try {
                        session.generate(client.model(), messages);
                    } catch (RuntimeException error) {
                        session.onError(error);
                    }
                });
            } catch (RuntimeException error) {
                session.onError(error);
            }
        }
        return emitter;
    }

    private List<ChatMessage> buildMessages(long appId, User loginUser, long generationId, String systemPrompt, String prompt)
            throws IOException {
        // 在占用生成任务后读取最新版本，避免使用进入接口时的旧快照。
        App app = appService.getOwnedApp(appId, loginUser);
        Long versionId = app.getGeneratedVersion();
        if (versionId == null) {
            return List.of(SystemMessage.from(systemPrompt), UserMessage.from(prompt));
        }
        List<String> history = memory.prompts(appId, loginUser.getId(), versionId);
        CodeGenTypeEnum mode = CodeGenTypeEnum.fromValue(app.getCodeGenType());
        String source = switch (mode) {
            case HTML -> fileManager.readHtml(loginUser.getId(), appId, versionId);
            case MULTI_FILE -> objectMapper.writeValueAsString(fileManager.readMultiFile(loginUser.getId(), appId, versionId));
            case VUE_PROJECT -> inheritedVueFiles(loginUser.getId(), appId, versionId, generationId);
        };
        return List.of(SystemMessage.from(systemPrompt),
                UserMessage.from("此前成功轮次的用户需求（按时间顺序，仅供参考）：\n" + objectMapper.writeValueAsString(history)),
                AiMessage.from(source), UserMessage.from(prompt));
    }

    private String inheritedVueFiles(long userId, long appId, long previousId, long generationId) throws IOException {
        var paths = fileManager.copyVueSources(userId, appId, previousId, generationId);
        // 旧版本没有来源标记；根据继承的绘图组件补记在本次副本，不改上一成功版本。
        if (paths.contains(VueArchitectureDiagram.DATA) && !fileManager.hasVueArchitecture(userId, appId, generationId)
                && VueArchitectureDiagram.containsLegacyRenderer(fileManager.readVueSources(userId, appId, generationId))) {
            fileManager.markVueArchitecture(userId, appId, generationId);
        }
        var photo = GeneratedFileManager.VUE_PHOTO;
        var assets = paths.contains(photo) ? Map.of(photo, objectMapper.readValue(
                fileManager.readVuePhoto(userId, appId, generationId), OpenversePhotoSearch.Attribution.class)) : Map.of();
        return objectMapper.writeValueAsString(Map.of("sourceFiles", paths.stream().filter(p -> !photo.equals(p)).toList(),
                "readOnlyAssets", assets));
    }

    @Override
    public void cancel(long appId, long generationId, User loginUser) {
        appService.getAppById(appId, loginUser);
        persistence.recoverInterruptedGenerations();
        GenerationSession session = runningSessions.get(appId);
        if (session == null || session.getGenerationId() != generationId) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "该生成不在运行中，请刷新消息状态");
        }
        session.cancel();
    }
}
