-- ============================================================
-- 动态智能体配置表（llm-mini 模块）
-- 在 MySQL llm 库中执行：mysql -uroot -p llm < llm_agent.sql
-- ============================================================

-- 新建表（含 owner_id 多租户归属字段）
CREATE TABLE IF NOT EXISTS llm_agent (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    owner_id       BIGINT       NOT NULL DEFAULT 123 COMMENT '归属用户 ID（多租户隔离）',
    agent_name     VARCHAR(128) NOT NULL COMMENT '智能体名称',
    system_message TEXT         NULL COMMENT '系统提示词（人设），聊天时动态注入',
    description    VARCHAR(512) NULL COMMENT '描述',
    created_time   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_time   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0=正常 1=删除',
    PRIMARY KEY (id),
    KEY idx_owner (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='动态智能体配置表';

-- 若 llm_agent 已存在但没有 owner_id 列（上一版无归属版本），执行迁移：
-- ALTER TABLE llm_agent ADD COLUMN owner_id BIGINT NOT NULL DEFAULT 123 COMMENT '归属用户 ID' AFTER id;
-- ALTER TABLE llm_agent ADD INDEX idx_owner (owner_id);

-- 可选：插入一个默认智能体（owner_id=123，id=1，与 MessageRequestVO.agentId 默认值对应）
INSERT INTO llm_agent (owner_id, agent_name, system_message, description)
VALUES (123, '默认助手',
'你是一个专业的AI助手, 专门帮助用户解答问题.
请用中文回答, 保持回答准确, 专业, 友好.
如果遇到不确定的问题, 请诚实地告知用户.
你是牛子大大模型
注意: 你可以使用工具来获取实时信息, 比如时间, 计算等.
当用户询问时间, 天气等信息时, 请务必使用相应的工具来获取准确信息.
当用户的问题与知识库文档相关时, 请基于检索到的文档内容回答; 与文档无关时按正常方式回答.',
'默认的通用智能体');
