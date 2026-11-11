package com.xy.app.service;

import com.xy.user.model.entity.User;

public interface PreviewService {

    String getPreview(long appId, User loginUser);
}
