package com.xy.user.mapper;

import com.mybatisflex.core.BaseMapper;
import com.xy.user.model.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
