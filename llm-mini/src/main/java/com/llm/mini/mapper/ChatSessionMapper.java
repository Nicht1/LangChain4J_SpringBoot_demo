package com.llm.mini.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llm.mini.pojo.ChatSession;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSession> {
}
