package com.xy.app.service.impl;

import com.xy.app.model.entity.App;
import com.xy.app.service.AppService;
import com.xy.app.service.PreviewService;
import com.xy.chat.mapper.ChatHistoryMapper;
import com.xy.chat.model.entity.ChatHistory;
import com.xy.common.ErrorCode;
import com.xy.exception.BusinessException;
import com.xy.manager.GeneratedFileManager;
import com.xy.user.model.entity.User;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PreviewServiceImpl implements PreviewService {

    private static final Logger log = LoggerFactory.getLogger(PreviewServiceImpl.class);
    private final AppService appService;
    private final ChatHistoryMapper chatHistoryMapper;
    private final GeneratedFileManager fileManager;

    public PreviewServiceImpl(AppService appService, ChatHistoryMapper chatHistoryMapper, GeneratedFileManager fileManager) {
        this.appService = appService;
        this.chatHistoryMapper = chatHistoryMapper;
        this.fileManager = fileManager;
    }

    @Override
    public String getPreview(long appId, User loginUser) {
        App app = appService.getOwnedApp(appId, loginUser);
        Long versionId = app.getGeneratedVersion();
        if (versionId == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "应用还没有成功生成的版本");
        }
        ChatHistory version = chatHistoryMapper.selectOneById(versionId);
        if (version == null || !app.getId().equals(version.getAppId()) || !app.getUserId().equals(version.getUserId())
                || !"assistant".equals(version.getMessageRole()) || !"completed".equals(version.getStatus())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "应用没有可预览的成功版本");
        }
        if (!"html".equals(app.getCodeGenType()) && !"multi_file".equals(app.getCodeGenType())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "当前预览仅支持 html 或 multi_file 模式");
        }
        try {
            return "multi_file".equals(app.getCodeGenType())
                    ? fileManager.readMultiFilePreview(app.getUserId(), appId, versionId)
                    : fileManager.readHtml(app.getUserId(), appId, versionId);
        } catch (NoSuchFileException error) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "生成文件不存在，请重新生成");
        } catch (IOException | IllegalArgumentException error) {
            log.error("应用 {} 版本 {} 预览读取失败，异常类型 {}", appId, versionId, error.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "读取预览失败，请检查生成文件");
        }
    }
}
