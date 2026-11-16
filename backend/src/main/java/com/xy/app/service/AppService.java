package com.xy.app.service;

import com.xy.app.model.dto.AppAddRequest;
import com.xy.app.model.dto.AppAdminQueryRequest;
import com.xy.app.model.dto.AppFeaturedRequest;
import com.xy.app.model.dto.AppQueryRequest;
import com.xy.app.model.dto.AppUpdateRequest;
import com.xy.app.model.entity.App;
import com.xy.app.model.vo.AppDeployVO;
import com.xy.app.model.vo.AppAdminPageVO;
import com.xy.app.model.vo.AppPageVO;
import com.xy.app.model.vo.AppVO;
import com.xy.user.model.entity.User;

public interface AppService {

    long createApp(AppAddRequest request, User loginUser);

    AppVO getAppById(long id, User loginUser);

    AppPageVO listMyApps(AppQueryRequest request, User loginUser);

    AppAdminPageVO listAppsForAdmin(AppAdminQueryRequest request, User loginUser);

    boolean updateApp(long id, AppUpdateRequest request, User loginUser);

    boolean updateAppForAdmin(long id, AppUpdateRequest request, User loginUser);

    boolean updateFeaturedForAdmin(long id, AppFeaturedRequest request, User loginUser);

    boolean deleteApp(long id, User loginUser);

    boolean deleteAppForAdmin(long id, User loginUser);

    /** 用户删除的内部清理步骤，调用方须先完成管理员及目标普通用户校验。 */
    void removeUserDeployments(long userId);

    App getOwnedApp(long id, User loginUser);

    byte[] downloadSource(long id, User loginUser);

    AppDeployVO deployApp(long id, User loginUser);
}
