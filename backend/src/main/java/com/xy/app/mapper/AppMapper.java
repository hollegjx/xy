package com.xy.app.mapper;

import com.mybatisflex.core.BaseMapper;
import com.xy.app.model.entity.App;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AppMapper extends BaseMapper<App> {

    @Update("UPDATE app SET generatedVersion = #{generationId} "
            + "WHERE id = #{appId} AND userId = #{userId} AND isDelete = 0")
    int updateGeneratedVersion(@Param("appId") long appId, @Param("userId") long userId,
            @Param("generationId") long generationId);
}
