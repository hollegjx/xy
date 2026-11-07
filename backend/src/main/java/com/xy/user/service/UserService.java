package com.xy.user.service;

import com.xy.user.model.dto.UserLoginRequest;
import com.xy.user.model.dto.UserRegisterRequest;
import com.xy.user.model.vo.UserVO;
import jakarta.servlet.http.HttpServletRequest;

public interface UserService {

    long register(UserRegisterRequest request);

    UserVO login(UserLoginRequest request, HttpServletRequest servletRequest);
}
