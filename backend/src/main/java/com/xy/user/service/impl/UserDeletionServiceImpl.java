package com.xy.user.service.impl;

import com.xy.app.service.AppService;
import com.xy.common.ErrorCode;
import com.xy.common.RequireRole;
import com.xy.exception.BusinessException;
import com.xy.user.mapper.UserMapper;
import com.xy.user.model.UserActor;
import com.xy.user.model.entity.User;
import com.xy.user.model.enums.UserRoleEnum;
import com.xy.user.service.UserDeletionService;
import org.springframework.stereotype.Service;

@Service
public class UserDeletionServiceImpl implements UserDeletionService {
    private final UserMapper userMapper;
    private final AppService appService;
    private final UserDeletionPersistence deletionPersistence;

    public UserDeletionServiceImpl(UserMapper userMapper, AppService appService, UserDeletionPersistence deletionPersistence) {
        this.userMapper = userMapper;
        this.appService = appService;
        this.deletionPersistence = deletionPersistence;
    }

    @Override
    @RequireRole(value = UserRoleEnum.ADMIN, message = "仅管理员可删除用户")
    public boolean deleteUser(long id, UserActor loginUser) {
        User user = userMapper.selectOneById(id);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "用户不存在或已删除");
        }
        if (!UserRoleEnum.USER.getValue().equals(user.getUserRole())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "仅支持删除普通用户，管理员账号不能删除");
        }
        appService.removeUserDeployments(id);
        deletionPersistence.deleteUserAndApps(id);
        appService.invalidateFeaturedApps();
        return true;
    }
}
