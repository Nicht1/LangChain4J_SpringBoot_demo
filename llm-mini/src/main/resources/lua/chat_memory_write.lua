-- 会话记忆写入脚本：整窗口覆盖 + 滑动过期，一次网络往返完成，全程原子。
--
-- KEYS[1]     记忆键，形如 llm:chat:memory:<userId>:<agentId>:<sessionId>
-- ARGV[1]     窗口上限 maxMessages（LTRIM 保留的条数）
-- ARGV[2]     TTL 秒数；<=0 表示不设过期
-- ARGV[3..N]  窗口内消息的 JSON，按时间正序（ARGV[3]=最旧，ARGV[N]=最新）
--
-- 返回：写入后的列表长度（仅供日志观察，调用方不依赖它做业务判断）
--
-- 方向约定：头=最旧、尾=最新。读取侧用 LRANGE key 0 -1 拿回来就是时间正序，
-- 两端保持一致，任何一端都不要反过来，否则历史顺序会静默错乱。
--
-- 为什么是 DEL + RPUSH 的"整窗口覆盖"，而不是只追加新增的那条：
--   想只追加，就得先算出"哪条是新的"，而两条路都不干净——
--     * 按 LLEN 推：窗口写满后 LLEN 恒等于上限，无法判断窗口是否滑动过，会漏写最新消息；
--     * 按内容比对：用户连发两条相同内容时无法消歧（除非额外引入序号）。
--   整窗口覆盖的代价是每次最多多写 20 个小字符串（Redis 上不到 100 微秒），
--   相对"一次 LLM 调用要几秒"可忽略。用一点写放大换掉全部对位逻辑，是划算的。
--
-- TODO(可优化)：若将来单条消息体变得很大（例如工具返回大 JSON 被塞进历史），
--   瓶颈会变成"整窗口重写的大 value"，届时优先做的是限制单条消息长度，
--   而不是为此引入上面那种不可靠的对位逻辑。

-- 清掉旧快照。语义上等价于"整窗口替换"，因为整段脚本原子执行，读侧看不到中间态。
redis.call('DEL', KEYS[1])

-- 空窗口时不执行 RPUSH：unpack 在空区间上会返回 nil，直接传进 RPUSH 会报错。
-- Java 侧对空列表已提前 return，这里是防御性的第二道保险。
if #ARGV > 2 then
    redis.call('RPUSH', KEYS[1], unpack(ARGV, 3, #ARGV))
end

-- 只保留最新的 maxMessages 条：负索引 -N 表示"倒数第 N 个"。
-- 这是"滑动窗口"的落点——修剪交给 Redis 服务端做 O(1) 摊销裁剪，应用层不写任何修剪逻辑。
redis.call('LTRIM', KEYS[1], -tonumber(ARGV[1]), -1)

-- 滑动过期：每次写入都刷新 TTL。空闲会话到点自动回收，不会永久占着内存。
-- ttl <= 0 时跳过 EXPIRE，表示"永不过期"（谨慎使用，会导致 key 无限累积）。
local ttl = tonumber(ARGV[2])
if ttl > 0 then
    redis.call('EXPIRE', KEYS[1], ttl)
end

return redis.call('LLEN', KEYS[1])
