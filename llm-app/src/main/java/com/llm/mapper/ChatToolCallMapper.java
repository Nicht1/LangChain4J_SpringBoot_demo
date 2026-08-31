package com.llm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llm.pojo.ChatToolCall;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatToolCallMapper extends BaseMapper<ChatToolCall> {
}
