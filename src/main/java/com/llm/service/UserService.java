package com.llm.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.llm.mapper.UserMapper;
import com.llm.pojo.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class UserService {

    private final UserMapper userMapper;

    @Autowired
    public UserService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public BigDecimal updateMoney(BigDecimal money, Long userId) {
        userMapper.update(new LambdaUpdateWrapper<User>().eq(User::getId, userId).set(User::getMoney, money));
        return money;
    }
}
