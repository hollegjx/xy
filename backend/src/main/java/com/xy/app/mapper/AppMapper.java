package com.xy.app.mapper;

import com.mybatisflex.core.BaseMapper;
import com.xy.app.model.entity.App;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AppMapper extends BaseMapper<App> {

    @Update("UPDATE app SET appName = #{appName} "
            + "WHERE id = #{appId} AND userId = #{userId} AND isDelete = 0")
    int updateAppName(@Param("appId") long appId, @Param("userId") long userId,
            @Param("appName") String appName);

    @Update("UPDATE app SET generatedVersion = #{generationId} "
            + "WHERE id = #{appId} AND userId = #{userId} AND isDelete = 0")
    int updateGeneratedVersion(@Param("appId") long appId, @Param("userId") long userId,
            @Param("generationId") long generationId);

    @Update("UPDATE app SET deployKey = #{deployKey} "
            + "WHERE id = #{appId} AND userId = #{userId} AND isDelete = 0 AND deployKey IS NULL")
    int assignDeployKey(@Param("appId") long appId, @Param("userId") long userId,
            @Param("deployKey") String deployKey);

    @Update("UPDATE app SET publishedVersion = #{versionId}, deployedAt = #{deployedAt} "
            + "WHERE id = #{appId} AND userId = #{userId} AND isDelete = 0 AND deployKey = #{deployKey}")
    int updateDeployment(@Param("appId") long appId, @Param("userId") long userId,
            @Param("deployKey") String deployKey, @Param("versionId") long versionId,
            @Param("deployedAt") LocalDateTime deployedAt);
}
