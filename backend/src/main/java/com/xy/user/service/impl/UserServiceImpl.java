package com.xy.user.service.impl;

import com.xy.exception.BusinessException;
import com.xy.user.mapper.UserMapper;
import com.xy.user.model.dto.UserRegisterRequest;
import com.xy.user.model.entity.User;
import com.xy.user.service.UserService;
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
}
