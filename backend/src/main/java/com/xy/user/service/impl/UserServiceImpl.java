package com.xy.user.service.impl;

import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.xy.app.service.AppService;
import com.xy.common.ErrorCode;
import com.xy.common.RequireRole;
import com.xy.common.UserConstant;
import com.xy.exception.BusinessException;
import com.xy.user.mapper.UserMapper;
import com.xy.user.model.dto.UserAddRequest;
import com.xy.user.model.dto.UserLoginRequest;
import com.xy.user.model.dto.UserQueryRequest;
import com.xy.user.model.dto.UserRegisterRequest;
import com.xy.user.model.dto.UserUpdateRequest;
import com.xy.user.model.entity.User;
import com.xy.user.model.enums.UserRoleEnum;
import com.xy.user.model.vo.UserPageVO;
import com.xy.user.model.vo.UserVO;
import com.xy.user.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final AppService appService;
    private final UserDeletionPersistence deletionPersistence;

    public UserServiceImpl(UserMapper userMapper, PasswordEncoder passwordEncoder, AppService appService,
            UserDeletionPersistence deletionPersistence) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.appService = appService;
        this.deletionPersistence = deletionPersistence;
    }

    @Override
    @RequireRole(value = UserRoleEnum.ADMIN, message = "仅管理员可新增用户")
    public long addUser(UserAddRequest request, User loginUser) {
        User user = new User();
        user.setUserAccount(request.userAccount());
        user.setUserPassword(passwordEncoder.encode(request.userPassword()));
        user.setUserName(request.userName() == null || request.userName().isBlank()
                ? request.userAccount() : request.userName());
        user.setUserRole(UserRoleEnum.USER.getValue());

        try {
            if (userMapper.insert(user) != 1) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "新增用户失败，请稍后重试");
            }
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "账号已存在");
        }
        return user.getId();
    }

    @Override
    @RequireRole(value = UserRoleEnum.ADMIN, message = "仅管理员可查询用户")
    public UserPageVO listUsers(UserQueryRequest request, User loginUser) {
        Page<User> page = userMapper.paginate(request.current(), request.pageSize(), getQueryWrapper(request));
        return new UserPageVO(page.getRecords().stream().map(this::getUserVO).toList(),
                page.getTotalRow(), page.getPageNumber(), page.getPageSize());
    }

    @Override
    @RequireRole(value = UserRoleEnum.ADMIN, message = "仅管理员可修改用户昵称")
    public boolean updateUser(long id, UserUpdateRequest request, User loginUser) {
        User user = userMapper.selectOneById(id);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "用户不存在或已删除");
        }
        if (request.userName().equals(user.getUserName())) {
            return true;
        }
        if (userMapper.updateUserName(id, request.userName()) != 1) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "用户昵称保存失败，请重试");
        }
        return true;
    }

    @Override
    @RequireRole(value = UserRoleEnum.ADMIN, message = "仅管理员可删除用户")
    public boolean deleteUser(long id, User loginUser) {
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

    private QueryWrapper getQueryWrapper(UserQueryRequest request) {
        return QueryWrapper.create()
                .like(User::getUserAccount, request.userAccount(), !request.userAccount().isEmpty())
                .orderBy(User::getCreateTime, false)
                .orderBy(User::getId, false);
    }

    @Override
    public long register(UserRegisterRequest request) {
        if (!request.userPassword().equals(request.checkPassword())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "两次输入的密码不一致");
        }

        User user = new User();
        user.setUserAccount(request.userAccount());
        user.setUserPassword(passwordEncoder.encode(request.userPassword()));
        user.setUserName(request.userAccount());
        user.setUserRole(UserRoleEnum.USER.getValue());

        try {
            if (userMapper.insert(user) != 1) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "注册失败，请稍后重试");
            }
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "账号已存在");
        }
        return user.getId();
    }

    @Override
    public UserVO login(UserLoginRequest request, HttpServletRequest servletRequest) {
        QueryWrapper query = QueryWrapper.create().eq(User::getUserAccount, request.userAccount());
        User user = userMapper.selectOneByQuery(query);
        if (user == null || !passwordEncoder.matches(request.userPassword(), user.getUserPassword())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号或密码错误");
        }

        HttpSession session = servletRequest.getSession();
        // 登录成功后更换会话标识，避免沿用登录前可能已暴露的 Session ID。
        servletRequest.changeSessionId();
        session.setAttribute(UserConstant.LOGIN_USER_ID, user.getId());
        return getUserVO(user);
    }

    @Override
    public boolean logout(HttpServletRequest request) {
        getLoginUser(request);
        request.getSession(false).invalidate();
        return true;
    }

    @Override
    public User getLoginUser(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object loginUserId = session == null ? null : session.getAttribute(UserConstant.LOGIN_USER_ID);
        if (!(loginUserId instanceof Long userId)) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "请先登录");
        }

        User user = userMapper.selectOneById(userId);
        if (user == null) {
            session.removeAttribute(UserConstant.LOGIN_USER_ID);
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "请先登录");
        }
        return user;
    }

    @Override
    public UserVO getUserVO(User user) {
        return new UserVO(Long.toString(user.getId()), user.getUserAccount(), user.getUserName(), user.getUserRole());
    }
}
