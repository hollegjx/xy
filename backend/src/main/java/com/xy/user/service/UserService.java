package com.xy.user.service;

import com.xy.user.model.dto.UserRegisterRequest;

public interface UserService {

    long register(UserRegisterRequest request);
}
