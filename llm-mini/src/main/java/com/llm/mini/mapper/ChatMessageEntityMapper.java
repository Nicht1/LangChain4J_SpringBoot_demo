package com.llm.mini.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llm.mini.pojo.ChatMessageEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 聊天消息 Mapper。
 * <p>
 * 自定义 SQL 全部围绕"最近 N 条"设计，统一按主键 {@code id} 倒序 + {@code LIMIT}
 * 命中 {@code (session_id, id)} 索引，避免 {@code ORDER BY created_time} 的全表排序。
 * 改用主键排序还有一个好处：同批写入的多条消息 id 严格递增，顺序不会因为
 * 时间戳精度（DATETIME 秒级）相同而错乱。
 */
@Mapper
public interface ChatMessageEntityMapper extends BaseMapper<ChatMessageEntity> {

    /**
     * 取会话最近 N 条消息（按 id 倒序，调用方自行反转成时间正序）。
     *
     * @param sessionId 会话键（此处为复合 memoryId）
     * @param limit     条数上限，与记忆窗口大小一致
     */
    @Select("SELECT * FROM chat_message_entity WHERE session_id = #{sessionId} ORDER BY id DESC LIMIT #{limit}")
    List<ChatMessageEntity> selectRecent(@Param("sessionId") String sessionId, @Param("limit") int limit);

    /** 取会话最近 N 条消息的 message_id（修剪时用于保留关联的工具调用记录） */
    @Select("SELECT message_id FROM chat_message_entity WHERE session_id = #{sessionId} ORDER BY id DESC LIMIT #{limit}")
    List<String> selectRecentMessageIds(@Param("sessionId") String sessionId, @Param("limit") int limit);

    /**
     * 取"倒数第 offset+1 新"的那条消息 id，作为修剪边界。
     * 例：offset = maxMessages - 1 时返回第 maxMessages 新的 id，
     * 于是 {@code DELETE ... WHERE id < 该值} 恰好保留最近 maxMessages 条。
     *
     * @param offset 0 表示最新一条
     * @return 边界 id；记录数不足 offset+1 条时返回 null
     */
    @Select("SELECT id FROM chat_message_entity WHERE session_id = #{sessionId} ORDER BY id DESC LIMIT 1 OFFSET #{offset}")
    Long selectBoundaryId(@Param("sessionId") String sessionId, @Param("offset") int offset);

    /** 一次性批量删除边界之前的所有消息（摊销式修剪，不做逐条删除） */
    @Delete("DELETE FROM chat_message_entity WHERE session_id = #{sessionId} AND id < #{boundaryId}")
    int deleteOlderThan(@Param("sessionId") String sessionId, @Param("boundaryId") Long boundaryId);

    /** 删除会话全部消息（清空记忆 / 快照重建时使用） */
    @Delete("DELETE FROM chat_message_entity WHERE session_id = #{sessionId}")
    int deleteBySessionId(@Param("sessionId") String sessionId);
}
