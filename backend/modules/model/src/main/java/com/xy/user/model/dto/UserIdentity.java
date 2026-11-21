package com.xy.user.model.dto;

import com.xy.user.model.UserActor;
import java.io.Serializable;

/** 供其他服务判断归属和权限；不包含账号资料或密码散列。 */
public final class UserIdentity implements UserActor, Serializable {
    private static final long serialVersionUID = 1L;
    private Long id;
    private String userRole;

    public UserIdentity() {}

    public UserIdentity(Long id, String userRole) {
        this.id = id;
        this.userRole = userRole;
    }

    @Override public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    @Override public String getUserRole() { return userRole; }
    public void setUserRole(String userRole) { this.userRole = userRole; }
}
