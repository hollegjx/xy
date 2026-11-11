package com.xy.generation.service.impl;

import com.xy.app.model.vo.AppVO;
import com.xy.app.service.AppService;
import com.xy.exception.BusinessException;
import com.xy.generation.file.GeneratedFileStore;
import com.xy.generation.model.dto.GenerationRequest;
import com.xy.generation.service.GenerationPersistence;
import com.xy.generation.service.GenerationService;
import com.xy.user.model.entity.User;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class GenerationServiceImpl implements GenerationService {

    private final AppService appService;
    private final GenerationPersistence persistence;
    private final GeneratedFileStore fileStore;
    private final ObjectProvider<StreamingChatModel> modelProvider;
    private final Executor executor;
    private final String systemPrompt;
    private final long timeoutMillis;
    // 当前部署为单实例；保证较早的生成不会晚于后一次生成覆盖成功版本。
    private final Set<Long> runningApps = ConcurrentHashMap.newKeySet();

    public GenerationServiceImpl(AppService appService, GenerationPersistence persistence,
            GeneratedFileStore fileStore, ObjectProvider<StreamingChatModel> modelProvider,
            @Qualifier("generationExecutor") Executor executor,
            @Value("classpath:prompts/single-html.txt") Resource prompt,
            @Value("${xy.ai.timeout-seconds:180}") long timeoutSeconds) throws IOException {
        this.appService = appService;
        this.persistence = persistence;
        this.fileStore = fileStore;
        this.modelProvider = modelProvider;
        this.executor = executor;
        this.systemPrompt = prompt.getContentAsString(StandardCharsets.UTF_8);
        this.timeoutMillis = Math.multiplyExact(timeoutSeconds, 1000);
    }

    @Override
    public SseEmitter generate(long appId, GenerationRequest request, User loginUser) {
        AppVO app = appService.getAppById(appId, loginUser);
        if (!"html".equals(app.codeGenType())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 40000, "当前生成接口仅支持单 HTML 模式");
        }
        StreamingChatModel model = modelProvider.getIfAvailable();
        if (model == null) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, 50001, "请先配置并启用模型服务");
        }
        if (!runningApps.add(appId)) {
            throw new BusinessException(HttpStatus.CONFLICT, 50001, "该应用正在生成，请等待本次结束");
        }
        long generationId;
        try {
            generationId = persistence.start(appId, loginUser.getId(), request.prompt());
        } catch (RuntimeException error) {
            runningApps.remove(appId);
            throw error;
        }
        SseEmitter emitter = new SseEmitter(timeoutMillis);
        GenerationSession session = new GenerationSession(appId, loginUser.getId(), generationId,
                emitter, persistence, fileStore, () -> runningApps.remove(appId));
        if (session.start()) {
            try {
                executor.execute(() -> {
                    if (!session.isRunning()) return;
                    try {
                        model.chat(List.of(SystemMessage.from(systemPrompt), UserMessage.from(request.prompt())), session);
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
}
