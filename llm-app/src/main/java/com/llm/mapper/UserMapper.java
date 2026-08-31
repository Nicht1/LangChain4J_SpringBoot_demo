package com.llm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llm.pojo.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
