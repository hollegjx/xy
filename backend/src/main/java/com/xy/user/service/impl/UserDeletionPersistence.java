package com.xy.user.service.impl;

import com.mybatisflex.core.query.QueryWrapper;
import com.xy.app.mapper.AppMapper;
import com.xy.app.model.entity.App;
import com.xy.common.ErrorCode;
import com.xy.exception.BusinessException;
import com.xy.user.mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserDeletionPersistence {

    private final UserMapper userMapper;
    private final AppMapper appMapper;

    public UserDeletionPersistence(UserMapper userMapper, AppMapper appMapper) {
        this.userMapper = userMapper;
        this.appMapper = appMapper;
    }

    // 调用前已完成管理员校验和公开入口清理；事务只包含数据库逻辑删除。
    @Transactional
    public void deleteUserAndApps(long userId) {
        appMapper.deleteByQuery(QueryWrapper.create().eq(App::getUserId, userId));
        if (userMapper.deleteById(userId) != 1) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "用户删除失败，请重试");
        }
    }
}
