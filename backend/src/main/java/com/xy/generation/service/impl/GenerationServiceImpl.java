package com.xy.generation.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xy.app.model.entity.App;
import com.xy.app.model.vo.AppVO;
import com.xy.app.service.AppService;
import com.xy.common.ErrorCode;
import com.xy.exception.BusinessException;
import com.xy.generation.model.dto.GenerationRequest;
import com.xy.generation.service.GenerationService;
import com.xy.manager.GeneratedFileManager;
import com.xy.user.model.entity.User;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class GenerationServiceImpl implements GenerationService {

    private final AppService appService;
    private final GenerationPersistence persistence;
    private final GeneratedFileManager fileManager;
    private final ObjectProvider<StreamingChatModel> modelProvider;
    private final Executor executor;
    private final Map<String, String> systemPrompts;
    private final ObjectMapper objectMapper;
    private final long timeoutMillis;
    // 当前部署为单实例；保证较早的生成不会晚于后一次生成覆盖成功版本。
    private final Map<Long, GenerationSession> runningSessions = new ConcurrentHashMap<>();

    public GenerationServiceImpl(AppService appService, GenerationPersistence persistence,
            GeneratedFileManager fileManager, ObjectProvider<StreamingChatModel> modelProvider,
            @Qualifier("generationExecutor") Executor executor,
            @Value("classpath:prompts/single-html.txt") Resource htmlPrompt,
            @Value("classpath:prompts/multi-file.txt") Resource multiFilePrompt,
            ObjectMapper objectMapper,
            @Value("${xy.ai.timeout-seconds:180}") long timeoutSeconds) throws IOException {
        this.appService = appService;
        this.persistence = persistence;
        this.fileManager = fileManager;
        this.modelProvider = modelProvider;
        this.executor = executor;
        this.systemPrompts = Map.of("html", htmlPrompt.getContentAsString(StandardCharsets.UTF_8),
                "multi_file", multiFilePrompt.getContentAsString(StandardCharsets.UTF_8));
        this.objectMapper = objectMapper;
        this.timeoutMillis = Math.multiplyExact(timeoutSeconds, 1000);
    }

    @Override
    public SseEmitter generate(long appId, GenerationRequest request, User loginUser) {
        AppVO app = appService.getAppById(appId, loginUser);
        String systemPrompt = systemPrompts.get(app.codeGenType());
        if (systemPrompt == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "当前生成仅支持 html 或 multi_file 模式");
        }
        StreamingChatModel model = modelProvider.getIfAvailable();
        if (model == null) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "请先配置并启用模型服务");
        }
        persistence.recoverInterruptedGenerations();
        SseEmitter emitter = new SseEmitter(timeoutMillis);
        GenerationSession session = runningSessions.compute(appId, (id, current) -> {
            if (current != null) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "该应用正在生成，请等待本次结束");
            }
            long generationId = persistence.start(appId, loginUser.getId(), request.prompt());
            return new GenerationSession(appId, loginUser.getId(), generationId,
                    emitter, persistence, fileManager, app.codeGenType(), objectMapper,
                    () -> runningSessions.remove(appId));
        });
        if (session.start()) {
            try {
                executor.execute(() -> {
                    if (!session.isRunning()) return;
                    List<ChatMessage> messages;
                    try {
                        messages = buildMessages(appId, loginUser, systemPrompt, request.prompt());
                    } catch (IOException | RuntimeException error) {
                        session.onContextError(error);
                        return;
                    }
                    if (!session.isRunning()) return;
                    try {
                        model.chat(messages, session);
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

    private List<ChatMessage> buildMessages(long appId, User loginUser, String systemPrompt, String prompt)
            throws IOException {
        // 在占用生成任务后读取最新版本，避免使用进入接口时的旧快照。
        App app = appService.getOwnedApp(appId, loginUser);
        Long versionId = app.getGeneratedVersion();
        if (versionId == null) {
            return List.of(SystemMessage.from(systemPrompt), UserMessage.from(prompt));
        }
        List<String> history = persistence.loadSuccessfulPrompts(appId, loginUser.getId(), versionId);
        String source = "multi_file".equals(app.getCodeGenType())
                ? objectMapper.writeValueAsString(fileManager.readMultiFile(loginUser.getId(), appId, versionId))
                : fileManager.readHtml(loginUser.getId(), appId, versionId);
        return List.of(SystemMessage.from(systemPrompt),
                UserMessage.from("此前成功轮次的用户需求（按时间顺序，仅供参考）：\n"
                        + objectMapper.writeValueAsString(history) + "\n下一条 AI 消息是当前网站的完整源码。"),
                AiMessage.from(source), UserMessage.from(prompt));
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
