package com.xy.app.service;

import com.xy.app.model.dto.AppAddRequest;
import com.xy.user.model.entity.User;

public interface AppService {

    long createApp(AppAddRequest request, User loginUser);
}
