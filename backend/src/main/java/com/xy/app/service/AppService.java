package com.xy.app.service;

import com.xy.app.model.dto.AppAddRequest;
import com.xy.app.model.dto.AppQueryRequest;
import com.xy.app.model.dto.AppUpdateRequest;
import com.xy.app.model.entity.App;
import com.xy.app.model.vo.AppDeployVO;
import com.xy.app.model.vo.AppPageVO;
import com.xy.app.model.vo.AppVO;
import com.xy.user.model.entity.User;

public interface AppService {

    long createApp(AppAddRequest request, User loginUser);

    AppVO getAppById(long id, User loginUser);

    AppPageVO listMyApps(AppQueryRequest request, User loginUser);

    boolean updateApp(long id, AppUpdateRequest request, User loginUser);

    App getOwnedApp(long id, User loginUser);

    byte[] downloadSource(long id, User loginUser);

    AppDeployVO deployApp(long id, User loginUser);
}
