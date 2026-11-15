package com.xy.user.mapper;

import com.mybatisflex.core.BaseMapper;
import com.xy.user.model.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    @Update("UPDATE user SET userName = #{userName} WHERE id = #{userId} AND isDelete = 0")
    int updateUserName(@Param("userId") long userId, @Param("userName") String userName);
}
