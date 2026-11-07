package com.xy.user.service.impl;

import com.mybatisflex.core.query.QueryWrapper;
import com.xy.exception.BusinessException;
import com.xy.user.constant.UserConstant;
import com.xy.user.mapper.UserMapper;
import com.xy.user.model.dto.UserLoginRequest;
import com.xy.user.model.dto.UserRegisterRequest;
import com.xy.user.model.entity.User;
import com.xy.user.model.vo.UserVO;
import com.xy.user.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public UserServiceImpl(UserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public long register(UserRegisterRequest request) {
        if (!request.userPassword().equals(request.checkPassword())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 40000, "两次输入的密码不一致");
        }

        User user = new User();
        user.setUserAccount(request.userAccount());
        user.setUserPassword(passwordEncoder.encode(request.userPassword()));
        user.setUserName(request.userAccount());
        user.setUserRole("user");

        try {
            if (userMapper.insert(user) != 1) {
                throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, 50001, "注册失败，请稍后重试");
            }
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(HttpStatus.CONFLICT, 40000, "账号已存在");
        }
        return user.getId();
    }

    @Override
    public UserVO login(UserLoginRequest request, HttpServletRequest servletRequest) {
        QueryWrapper query = QueryWrapper.create().eq(User::getUserAccount, request.userAccount());
        User user = userMapper.selectOneByQuery(query);
        if (user == null || !passwordEncoder.matches(request.userPassword(), user.getUserPassword())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 40000, "账号或密码错误");
        }

        HttpSession session = servletRequest.getSession();
        // 登录成功后更换会话标识，避免沿用登录前可能已暴露的 Session ID。
        servletRequest.changeSessionId();
        session.setAttribute(UserConstant.LOGIN_USER_ID, user.getId());
        return getUserVO(user);
    }

    @Override
    public User getLoginUser(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object loginUserId = session == null ? null : session.getAttribute(UserConstant.LOGIN_USER_ID);
        if (!(loginUserId instanceof Long userId)) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, 40100, "请先登录");
        }

        User user = userMapper.selectOneById(userId);
        if (user == null) {
            session.removeAttribute(UserConstant.LOGIN_USER_ID);
            throw new BusinessException(HttpStatus.UNAUTHORIZED, 40100, "请先登录");
        }
        return user;
    }

    @Override
    public UserVO getUserVO(User user) {
        return new UserVO(Long.toString(user.getId()), user.getUserAccount(), user.getUserName(), user.getUserRole());
    }
}
