package com.llm.tool;

import com.llm.service.UserService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class UserTool implements LlmTool {

    @Autowired
    private UserService userService;

    @Tool("""
        修改指定用户的余额。
        参数说明：
        - money: 要设置的新余额金额
        - userId: 要修改余额的用户ID（必须提供，通常从系统消息中的 userId 获取）
        """)
    public String updateUserMoney(
            @P("具体金额或余额，例如: 500, 10000") BigDecimal money,
            @ToolMemoryId Long userId
    ) {

        if (userId == null) {
            return "❌ 错误：缺少用户ID参数";
        }

        if (money == null || money.compareTo(BigDecimal.ZERO) < 0) {
            return "❌ 错误：金额无效";
        }

        try {
            BigDecimal newBalance = userService.updateMoney(money, userId);
            return "✅ 成功！用户 " + userId + " 的余额已修改为 " + newBalance + " 元";
        } catch (Exception e) {
            System.err.println("❌ [UserTool] 修改余额失败: " + e.getMessage());
            e.printStackTrace();
            return "❌ 修改余额失败: " + e.getMessage();
        }
    }
}
