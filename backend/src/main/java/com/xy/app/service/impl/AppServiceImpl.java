package com.xy.app.service.impl;

import com.xy.app.mapper.AppMapper;
import com.xy.app.model.dto.AppAddRequest;
import com.xy.app.model.entity.App;
import com.xy.app.model.enums.CodeGenTypeEnum;
import com.xy.app.model.vo.AppVO;
import com.xy.app.service.AppService;
import com.xy.chat.mapper.ChatHistoryMapper;
import com.xy.chat.model.entity.ChatHistory;
import com.xy.exception.BusinessException;
import com.xy.manager.GeneratedFileManager;
import com.xy.user.model.entity.User;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AppServiceImpl implements AppService {

    private static final Logger log = LoggerFactory.getLogger(AppServiceImpl.class);
    private final AppMapper appMapper;
    private final ChatHistoryMapper chatHistoryMapper;
    private final GeneratedFileManager fileManager;

    public AppServiceImpl(AppMapper appMapper, ChatHistoryMapper chatHistoryMapper, GeneratedFileManager fileManager) {
        this.appMapper = appMapper;
        this.chatHistoryMapper = chatHistoryMapper;
        this.fileManager = fileManager;
    }

    @Override
    public long createApp(AppAddRequest request, User loginUser) {
        String prompt = request.initPrompt();
        int nameLength = Math.min(12, prompt.codePointCount(0, prompt.length()));
        CodeGenTypeEnum codeGenType = request.codeGenType() == null
                ? CodeGenTypeEnum.MULTI_FILE : request.codeGenType();

        App app = new App();
        app.setUserId(loginUser.getId());
        app.setAppName(prompt.substring(0, prompt.offsetByCodePoints(0, nameLength)));
        app.setInitPrompt(prompt);
        app.setCodeGenType(codeGenType.getValue());
        if (appMapper.insert(app) != 1) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, 50001, "创建应用失败，请稍后重试");
        }
        return app.getId();
    }

    @Override
    public AppVO getAppById(long id, User loginUser) {
        return getAppVO(getOwnedApp(id, loginUser));
    }

    @Override
    public App getOwnedApp(long id, User loginUser) {
        App app = appMapper.selectOneById(id);
        if (app == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, 40400, "应用不存在");
        }
        if (!app.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, 40300, "无权访问该应用");
        }
        return app;
    }

    @Override
    public byte[] downloadSource(long id, User loginUser) {
        App app = getOwnedApp(id, loginUser);
        Long versionId = app.getGeneratedVersion();
        if (versionId == null) {
            throw new BusinessException(HttpStatus.CONFLICT, 50001, "应用还没有成功生成的版本");
        }
        ChatHistory version = chatHistoryMapper.selectOneById(versionId);
        if (version == null || !app.getId().equals(version.getAppId()) || !app.getUserId().equals(version.getUserId())
                || !"assistant".equals(version.getMessageRole()) || !"completed".equals(version.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, 50001, "应用没有可下载的成功版本");
        }
        if (!"html".equals(app.getCodeGenType()) && !"multi_file".equals(app.getCodeGenType())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 40000, "当前下载仅支持 html 或 multi_file 模式");
        }
        try {
            return fileManager.readSourceZip(app.getUserId(), id, versionId, "multi_file".equals(app.getCodeGenType()));
        } catch (NoSuchFileException error) {
            throw new BusinessException(HttpStatus.NOT_FOUND, 40400, "生成文件不存在，请重新生成");
        } catch (IOException error) {
            log.error("应用 {} 版本 {} 源码下载失败，异常类型 {}", id, versionId, error.getClass().getSimpleName());
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, 50001, "读取源码失败，请检查生成文件");
        }
    }

    private AppVO getAppVO(App app) {
        return new AppVO(Long.toString(app.getId()), Long.toString(app.getUserId()), app.getAppName(),
                app.getInitPrompt(), app.getCodeGenType(), app.getCreateTime(), app.getUpdateTime());
    }
}
