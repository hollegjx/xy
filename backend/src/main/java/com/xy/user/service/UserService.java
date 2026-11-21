package com.xy.user.service;

import com.xy.user.model.dto.UserAddRequest;
import com.xy.user.model.dto.UserLoginRequest;
import com.xy.user.model.dto.UserQueryRequest;
import com.xy.user.model.dto.UserRegisterRequest;
import com.xy.user.model.dto.UserUpdateRequest;
import com.xy.user.model.entity.User;
import com.xy.user.model.vo.UserPageVO;
import com.xy.user.model.vo.UserVO;
import jakarta.servlet.http.HttpServletRequest;

public interface UserService {

    long addUser(UserAddRequest request, User loginUser);

    UserPageVO listUsers(UserQueryRequest request, User loginUser);

    boolean updateUser(long id, UserUpdateRequest request, User loginUser);

    boolean deleteUser(long id, User loginUser);

    long register(UserRegisterRequest request);

    UserVO login(UserLoginRequest request, HttpServletRequest servletRequest);

    boolean logout(HttpServletRequest request);

    User getLoginUser(HttpServletRequest request);

    UserVO getUserVO(User user);
}
