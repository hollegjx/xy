package com.xy.app.service;

import com.xy.user.model.UserActor;

public interface PreviewService {

    String getPreview(long appId, UserActor loginUser);
}
