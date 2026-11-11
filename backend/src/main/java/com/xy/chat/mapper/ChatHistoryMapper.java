package com.xy.chat.mapper;

import com.mybatisflex.core.BaseMapper;
import com.xy.chat.model.entity.ChatHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ChatHistoryMapper extends BaseMapper<ChatHistory> {

    @Update("UPDATE chat_history SET status = #{status}, content = #{content}, errorMessage = #{errorMessage} "
            + "WHERE id = #{id} AND messageRole = 'assistant' AND status = 'running' AND isDelete = 0")
    int finishGeneration(@Param("id") long id, @Param("status") String status,
            @Param("content") String content, @Param("errorMessage") String errorMessage);

    @Update("UPDATE chat_history SET status = 'failed', errorMessage = '服务已重启，本次生成中断，请重试' "
            + "WHERE messageRole = 'assistant' AND status = 'running' AND isDelete = 0")
    int failInterruptedGenerations();
}
