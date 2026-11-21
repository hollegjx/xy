package com.xy.user.model;

/** 应用判断归属和权限只需要身份和角色。 */
public interface UserActor {
    Long getId();
    String getUserRole();
}
