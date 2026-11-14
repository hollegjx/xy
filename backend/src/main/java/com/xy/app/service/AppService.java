package com.xy.app.service;

import com.xy.app.model.dto.AppAddRequest;
import com.xy.app.model.entity.App;
import com.xy.app.model.vo.AppDeployVO;
import com.xy.app.model.vo.AppVO;
import com.xy.user.model.entity.User;

public interface AppService {

    long createApp(AppAddRequest request, User loginUser);

    AppVO getAppById(long id, User loginUser);

    App getOwnedApp(long id, User loginUser);

    byte[] downloadSource(long id, User loginUser);

    AppDeployVO deployApp(long id, User loginUser);
}
