package com.xy.app.service;

import com.xy.manager.GeneratedFileManager.SourceArchive;

import com.xy.app.model.dto.AppAddRequest;
import com.xy.app.model.dto.AppAdminQueryRequest;
import com.xy.app.model.dto.AppFeaturedRequest;
import com.xy.app.model.dto.AppQueryRequest;
import com.xy.app.model.dto.AppUpdateRequest;
import com.xy.app.model.entity.App;
import com.xy.app.model.vo.AppDeployVO;
import com.xy.app.model.vo.AppAdminPageVO;
import com.xy.app.model.vo.AppFeaturedSlice;
import com.xy.app.model.vo.AppPageVO;
import com.xy.app.model.vo.AppVO;
import com.xy.user.model.UserActor;

public interface AppService {

    long createApp(AppAddRequest request, UserActor loginUser);

    AppVO getAppById(long id, UserActor loginUser);

    AppPageVO listMyApps(AppQueryRequest request, UserActor loginUser);

    AppAdminPageVO listAppsForAdmin(AppAdminQueryRequest request, UserActor loginUser);

    AppFeaturedSlice listFeaturedApps(int current, int pageSize);

    /** 删除用户及其应用的事务提交后，清除旧的公开精选列表。 */
    void invalidateFeaturedApps();

    byte[] getFeaturedCover(long id, long version);

    boolean updateApp(long id, AppUpdateRequest request, UserActor loginUser);

    boolean updateAppForAdmin(long id, AppUpdateRequest request, UserActor loginUser);

    boolean updateFeaturedForAdmin(long id, AppFeaturedRequest request, UserActor loginUser);

    boolean deleteApp(long id, UserActor loginUser);

    boolean deleteAppForAdmin(long id, UserActor loginUser);

    /** 用户删除的内部清理步骤，调用方须先完成管理员及目标普通用户校验。 */
    void removeUserDeployments(long userId);

    App getOwnedApp(long id, UserActor loginUser);

    SourceArchive downloadSource(long id, UserActor loginUser);

    AppDeployVO deployApp(long id, UserActor loginUser);
}
