package com.xy.app.service.impl;

import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.xy.app.mapper.AppMapper;
import com.xy.app.model.dto.AppAddRequest;
import com.xy.app.model.dto.AppAdminQueryRequest;
import com.xy.app.model.dto.AppFeaturedRequest;
import com.xy.app.model.dto.AppQueryRequest;
import com.xy.app.model.dto.AppUpdateRequest;
import com.xy.app.model.entity.App;
import com.xy.app.model.enums.CodeGenTypeEnum;
import com.xy.app.model.vo.AppDeployVO;
import com.xy.app.model.vo.AppAdminPageVO;
import com.xy.app.model.vo.AppAdminVO;
import com.xy.app.model.vo.AppFeaturedSlice;
import com.xy.app.model.vo.AppPageVO;
import com.xy.app.model.vo.AppVO;
import com.xy.app.service.AppService;
import com.xy.chat.mapper.ChatHistoryMapper;
import com.xy.chat.model.entity.ChatHistory;
import com.xy.chat.model.enums.MessageRoleEnum;
import com.xy.chat.model.enums.MessageStatusEnum;
import com.xy.client.ScreenshotService;
import com.xy.common.ErrorCode;
import com.xy.common.RequireRole;
import com.xy.config.DeploymentConfiguration;
import com.xy.exception.BusinessException;
import com.xy.manager.GeneratedFileManager;
import com.xy.manager.PromptInputGuard;
import com.xy.user.model.entity.User;
import com.xy.user.model.enums.UserRoleEnum;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
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
    private final GenerationModeRouter modeRouter;
    private final Optional<ScreenshotService> screenshots;
    private final FeaturedAppPages featuredPages;

    public AppServiceImpl(AppMapper appMapper, ChatHistoryMapper chatHistoryMapper, GeneratedFileManager fileManager,
            DeploymentConfiguration deployment, GenerationModeRouter modeRouter, Optional<ScreenshotService> screenshots,
            FeaturedAppPages featuredPages) {
        this.appMapper = appMapper;
        this.chatHistoryMapper = chatHistoryMapper;
        this.fileManager = fileManager;
        this.deployment = deployment;
        this.modeRouter = modeRouter;
        this.screenshots = screenshots;
        this.featuredPages = featuredPages;
    }

    @Override
    public long createApp(AppAddRequest request, User loginUser) {
        String prompt = request.initPrompt();
        if (!request.requestsAutomaticMode()) PromptInputGuard.check(prompt);
        int nameLength = Math.min(12, prompt.codePointCount(0, prompt.length()));
        CodeGenTypeEnum codeGenType = modeRouter.select(request, loginUser.getId());

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
    @RequireRole(value = UserRoleEnum.ADMIN, message = "仅管理员可查询全平台应用")
    public AppAdminPageVO listAppsForAdmin(AppAdminQueryRequest request, User loginUser) {
        QueryWrapper query = QueryWrapper.create()
                .like(App::getAppName, request.appName(), !request.appName().isEmpty())
                .eq(App::getUserId, request.userId(), request.userId() != null)
                .orderBy(App::getCreateTime, false)
                .orderBy(App::getId, false);
        Page<App> page = appMapper.paginate(request.current(), request.pageSize(), query);
        return new AppAdminPageVO(page.getRecords().stream().map(this::getAppAdminVO).toList(),
                page.getTotalRow(), page.getPageNumber(), page.getPageSize());
    }

    @Override
    public AppFeaturedSlice listFeaturedApps(int current, int pageSize) {
        if (deployment.publicOrigin().isEmpty()) {
            return new AppFeaturedSlice(List.of(), false);
        }
        String revision = current <= FeaturedAppPages.CACHED_PAGES ? featuredPages.currentRevision() : null;
        return featuredPages.read(current, pageSize, revision, deployment);
    }

    @Override
    public void invalidateFeaturedApps() {
        featuredPages.changeRevision();
    }

    @Override
    public byte[] getFeaturedCover(long id, long version) {
        App app = appMapper.selectOneById(id);
        if (app == null || !Boolean.TRUE.equals(app.getFeatured()) || !Long.valueOf(version).equals(app.getPublishedVersion())
                || deployment.siteUrl(app.getDeployKey()) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "封面不存在");
        }
        try {
            return fileManager.readCover(app.getUserId(), id, version);
        } catch (NoSuchFileException missing) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "封面不存在");
        } catch (IOException error) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "封面读取失败");
        }
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
        invalidateFeaturedApps();
        return true;
    }

    @Override
    @RequireRole(value = UserRoleEnum.ADMIN, message = "仅管理员可通过管理接口修改应用名称")
    public boolean updateAppForAdmin(long id, AppUpdateRequest request, User loginUser) {
        App app = appMapper.selectOneById(id);
        if (app == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        }
        if (app.getAppName().equals(request.appName())) {
            return true;
        }
        if (appMapper.updateAppName(id, app.getUserId(), request.appName()) != 1) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "应用名称保存失败，请重试");
        }
        invalidateFeaturedApps();
        return true;
    }

    @Override
    public boolean deleteApp(long id, User loginUser) {
        return deleteAuthorizedApp(getOwnedApp(id, loginUser));
    }

    @Override
    @RequireRole(value = UserRoleEnum.ADMIN, message = "仅管理员可设置应用精选状态")
    public boolean updateFeaturedForAdmin(long id, AppFeaturedRequest request, User loginUser) {
        App app = appMapper.selectOneById(id);
        if (app == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        }
        if (request.featured() && app.getPublishedVersion() == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "仅已发布应用可设为精选");
        }
        if (Boolean.TRUE.equals(app.getFeatured()) == request.featured()) {
            return true;
        }
        if (appMapper.updateFeatured(id, app.getUserId(), request.featured()) != 1) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "精选状态保存失败，请重试");
        }
        invalidateFeaturedApps();
        return true;
    }

    @Override
    @RequireRole(value = UserRoleEnum.ADMIN, message = "仅管理员可通过管理接口删除应用")
    public boolean deleteAppForAdmin(long id, User loginUser) {
        App app = appMapper.selectOneById(id);
        if (app == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        }
        return deleteAuthorizedApp(app);
    }

    private boolean deleteAuthorizedApp(App app) {
        if (app.getDeployKey() != null) {
            try {
                // 调用入口已完成权限检查；先下线公开作品，再标记删除。
                fileManager.removePublishedHtml(deployment.directory(), app.getDeployKey());
            } catch (IOException error) {
                log.error("应用 {} 发布页面移除失败，异常类型 {}", app.getId(), error.getClass().getSimpleName());
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "移除发布页面失败，应用尚未删除，请重试");
            }
        }
        if (appMapper.markDeleted(app.getId(), app.getUserId()) != 1) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "应用删除失败，请重试");
        }
        invalidateFeaturedApps();
        return true;
    }

    @Override
    public void removeUserDeployments(long userId) {
        var apps = appMapper.selectListByQuery(QueryWrapper.create().eq(App::getUserId, userId));
        for (App app : apps) {
            if (app.getDeployKey() == null) continue;
            try {
                fileManager.removePublishedHtml(deployment.directory(), app.getDeployKey());
            } catch (IOException error) {
                log.error("用户 {} 应用 {} 发布页面移除失败，异常类型 {}", userId, app.getId(),
                        error.getClass().getSimpleName());
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "移除用户作品失败，账号尚未删除，请重试");
            }
        }
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
    public GeneratedFileManager.SourceArchive downloadSource(long id, User loginUser) {
        App app = getOwnedApp(id, loginUser);
        long versionId = getSuccessfulVersion(app);
        try {
            if (CodeGenTypeEnum.VUE_PROJECT.getValue().equals(app.getCodeGenType())) {
                return fileManager.readVueSourceZip(app.getUserId(), id, versionId);
            }
            return fileManager.readSourceZip(app.getUserId(), id, versionId,
                    CodeGenTypeEnum.MULTI_FILE.getValue().equals(app.getCodeGenType()));
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
        String key = app.getDeployKey();
        try {
            String html = preparePublicationHtml(app, versionId);
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
            invalidateFeaturedApps();
            screenshots.ifPresent(service -> capturePublishedCover(service, app.getUserId(), id, versionId, html));
            return new AppDeployVO(deployment.siteUrl(key), Long.toString(versionId), deployedAt);
        } catch (IOException error) {
            log.error("应用 {} 版本 {} 发布文件失败，异常类型 {}", id, versionId, error.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "发布文件失败，请检查发布目录");
        }
    }

    private void capturePublishedCover(ScreenshotService service, long owner, long appId, long version, String snapshot) {
        // 地址可复用；后台任务只读本次快照，只写本次版本的封面。
        Thread.startVirtualThread(() -> {
            try {
                byte[] jpeg = service.render(snapshot);
                if (jpeg != null) {
                    fileManager.saveCover(owner, appId, version, jpeg);
                    invalidateFeaturedApps(); // 刷新截图完成前已缓存的文字卡片
                }
            } catch (IOException | RuntimeException error) {
                log.warn("应用 {} 版本 {} 已发布，封面未生成：{}", appId, version, error.getClass().getSimpleName());
            }
        });
    }

    private String preparePublicationHtml(App app, long versionId) throws IOException {
        CodeGenTypeEnum mode = CodeGenTypeEnum.fromValue(app.getCodeGenType());
        try {
            return switch (mode) {
                case VUE_PROJECT -> {
                    String html = fileManager.readVuePreview(app.getUserId(), app.getId(), versionId);
                    if (html.isBlank()) {
                        throw new BusinessException(ErrorCode.SYSTEM_ERROR, "该版本的 Vue 构建产物为空，请重新生成");
                    }
                    deployment.requireConfigured();
                    yield html;
                }
                case HTML, MULTI_FILE -> {
                    deployment.requireConfigured();
                    yield mode == CodeGenTypeEnum.HTML ? fileManager.readHtml(app.getUserId(), app.getId(), versionId)
                            : fileManager.readMultiFilePreview(app.getUserId(), app.getId(), versionId);
                }
            };
        } catch (NoSuchFileException error) {
            if (mode == CodeGenTypeEnum.VUE_PROJECT) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "该版本尚无构建产物，请启用 Vue 构建后重新生成；源码仍可下载");
            }
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "生成文件不存在，请重新生成");
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
        if (!CodeGenTypeEnum.HTML.getValue().equals(app.getCodeGenType())
                && !CodeGenTypeEnum.MULTI_FILE.getValue().equals(app.getCodeGenType())
                && !CodeGenTypeEnum.VUE_PROJECT.getValue().equals(app.getCodeGenType())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "当前交付仅支持 html、multi_file 或 vue_project 模式");
        }
        return versionId;
    }

    private AppAdminVO getAppAdminVO(App app) {
        return new AppAdminVO(Long.toString(app.getId()), Long.toString(app.getUserId()), app.getAppName(),
                app.getCodeGenType(), Boolean.TRUE.equals(app.getFeatured()), app.getCreateTime(), app.getUpdateTime(),
                app.getPublishedVersion() == null ? null : new AppDeployVO(deployment.siteUrl(app.getDeployKey()),
                        Long.toString(app.getPublishedVersion()), app.getDeployedAt()));
    }

    private AppVO getAppVO(App app) {
        return new AppVO(Long.toString(app.getId()), Long.toString(app.getUserId()), app.getAppName(),
                app.getInitPrompt(), app.getCodeGenType(), app.getCreateTime(), app.getUpdateTime(),
                app.getPublishedVersion() == null ? null : new AppDeployVO(deployment.siteUrl(app.getDeployKey()),
                        Long.toString(app.getPublishedVersion()), app.getDeployedAt()));
    }
}
