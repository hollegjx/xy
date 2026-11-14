package com.xy.app.service.impl;

import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.xy.app.mapper.AppMapper;
import com.xy.app.model.dto.AppAddRequest;
import com.xy.app.model.dto.AppQueryRequest;
import com.xy.app.model.dto.AppUpdateRequest;
import com.xy.app.model.entity.App;
import com.xy.app.model.enums.CodeGenTypeEnum;
import com.xy.app.model.vo.AppDeployVO;
import com.xy.app.model.vo.AppPageVO;
import com.xy.app.model.vo.AppVO;
import com.xy.app.service.AppService;
import com.xy.chat.mapper.ChatHistoryMapper;
import com.xy.chat.model.entity.ChatHistory;
import com.xy.chat.model.enums.MessageRoleEnum;
import com.xy.chat.model.enums.MessageStatusEnum;
import com.xy.common.ErrorCode;
import com.xy.config.DeploymentConfiguration;
import com.xy.exception.BusinessException;
import com.xy.manager.GeneratedFileManager;
import com.xy.user.model.entity.User;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.time.LocalDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AppServiceImpl implements AppService {

    private static final Logger log = LoggerFactory.getLogger(AppServiceImpl.class);
    private final AppMapper appMapper;
    private final ChatHistoryMapper chatHistoryMapper;
    private final GeneratedFileManager fileManager;
    private final DeploymentConfiguration deployment;

    public AppServiceImpl(AppMapper appMapper, ChatHistoryMapper chatHistoryMapper, GeneratedFileManager fileManager,
            DeploymentConfiguration deployment) {
        this.appMapper = appMapper;
        this.chatHistoryMapper = chatHistoryMapper;
        this.fileManager = fileManager;
        this.deployment = deployment;
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
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "创建应用失败，请稍后重试");
        }
        return app.getId();
    }

    @Override
    public AppVO getAppById(long id, User loginUser) {
        return getAppVO(getOwnedApp(id, loginUser));
    }

    @Override
    public AppPageVO listMyApps(AppQueryRequest request, User loginUser) {
        QueryWrapper query = QueryWrapper.create()
                .eq(App::getUserId, loginUser.getId())
                .like(App::getAppName, request.appName(), !request.appName().isEmpty())
                .orderBy(App::getCreateTime, false)
                .orderBy(App::getId, false);
        Page<App> page = appMapper.paginate(request.current(), request.pageSize(), query);
        return new AppPageVO(page.getRecords().stream().map(this::getAppVO).toList(),
                page.getTotalRow(), page.getPageNumber(), page.getPageSize());
    }

    @Override
    public boolean updateApp(long id, AppUpdateRequest request, User loginUser) {
        App app = getOwnedApp(id, loginUser);
        if (app.getAppName().equals(request.appName())) {
            return true;
        }
        if (appMapper.updateAppName(id, loginUser.getId(), request.appName()) != 1) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "应用名称保存失败，请重试");
        }
        return true;
    }

    @Override
    public boolean deleteApp(long id, User loginUser) {
        App app = getOwnedApp(id, loginUser);
        if (app.getDeployKey() != null) {
            try {
                // 先关闭公开入口；移除失败时保留应用，创建者可以再次删除。
                fileManager.removePublishedHtml(deployment.directory(), app.getDeployKey());
            } catch (IOException error) {
                log.error("应用 {} 发布页面移除失败，异常类型 {}", id, error.getClass().getSimpleName());
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "移除发布页面失败，应用尚未删除，请重试");
            }
        }
        if (appMapper.markDeleted(id, loginUser.getId()) != 1) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "应用删除失败，请重试");
        }
        return true;
    }

    @Override
    public App getOwnedApp(long id, User loginUser) {
        App app = appMapper.selectOneById(id);
        if (app == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        }
        if (!app.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权访问该应用");
        }
        return app;
    }

    @Override
    public byte[] downloadSource(long id, User loginUser) {
        App app = getOwnedApp(id, loginUser);
        long versionId = getSuccessfulVersion(app);
        try {
            return fileManager.readSourceZip(app.getUserId(), id, versionId, "multi_file".equals(app.getCodeGenType()));
        } catch (NoSuchFileException error) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "生成文件不存在，请重新生成");
        } catch (IOException error) {
            log.error("应用 {} 版本 {} 源码下载失败，异常类型 {}", id, versionId, error.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "读取源码失败，请检查生成文件");
        }
    }

    @Override
    public AppDeployVO deployApp(long id, User loginUser) {
        App app = getOwnedApp(id, loginUser);
        long versionId = getSuccessfulVersion(app);
        deployment.requireConfigured();
        String key = app.getDeployKey();
        try {
            String html = CodeGenTypeEnum.MULTI_FILE.getValue().equals(app.getCodeGenType())
                    ? fileManager.readMultiFilePreview(app.getUserId(), id, versionId)
                    : fileManager.readHtml(app.getUserId(), id, versionId);
            if (key == null) {
                key = UUID.randomUUID().toString().replace("-", "");
                if (appMapper.assignDeployKey(id, app.getUserId(), key) != 1) {
                    throw new BusinessException(ErrorCode.SYSTEM_ERROR, "发布地址分配失败，请重试");
                }
            }
            fileManager.publishHtml(deployment.directory(), key, html);
            LocalDateTime deployedAt = LocalDateTime.now().withNano(0);
            if (appMapper.updateDeployment(id, app.getUserId(), key, versionId, deployedAt) != 1) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "发布记录保存失败，请重试发布");
            }
            return new AppDeployVO(deployment.siteUrl(key), Long.toString(versionId), deployedAt);
        } catch (NoSuchFileException error) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "生成文件不存在，请重新生成");
        } catch (IOException error) {
            log.error("应用 {} 版本 {} 发布文件失败，异常类型 {}", id, versionId, error.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "发布文件失败，请检查发布目录");
        }
    }

    private long getSuccessfulVersion(App app) {
        Long versionId = app.getGeneratedVersion();
        if (versionId == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "应用还没有成功生成的版本");
        }
        ChatHistory version = chatHistoryMapper.selectOneById(versionId);
        if (version == null || !app.getId().equals(version.getAppId()) || !app.getUserId().equals(version.getUserId())
                || !MessageRoleEnum.ASSISTANT.getValue().equals(version.getMessageRole())
                || !MessageStatusEnum.COMPLETED.getValue().equals(version.getStatus())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "应用没有可交付的成功版本");
        }
        if (!"html".equals(app.getCodeGenType()) && !"multi_file".equals(app.getCodeGenType())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "当前交付仅支持 html 或 multi_file 模式");
        }
        return versionId;
    }

    private AppVO getAppVO(App app) {
        return new AppVO(Long.toString(app.getId()), Long.toString(app.getUserId()), app.getAppName(),
                app.getInitPrompt(), app.getCodeGenType(), app.getCreateTime(), app.getUpdateTime(),
                app.getPublishedVersion() == null ? null : new AppDeployVO(deployment.siteUrl(app.getDeployKey()),
                        Long.toString(app.getPublishedVersion()), app.getDeployedAt()));
    }
}
