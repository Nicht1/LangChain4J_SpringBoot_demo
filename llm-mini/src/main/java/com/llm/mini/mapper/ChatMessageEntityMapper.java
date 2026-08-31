package com.llm.mini.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llm.mini.pojo.ChatMessageEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatMessageEntityMapper extends BaseMapper<ChatMessageEntity> {
}
