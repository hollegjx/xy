package com.xy.common;

import com.xy.user.model.enums.UserRoleEnum;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 标记业务入口需要的角色；最后一个参数是从当前会话重新查到的用户。 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {
    UserRoleEnum value();
    String message();
}
