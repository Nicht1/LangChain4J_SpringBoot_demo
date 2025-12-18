package com.llm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llm.pojo.ChatMessageEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatMessageEntityMapper extends BaseMapper<ChatMessageEntity> {
}
