package com.exam.mapper;

import com.exam.model.*;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface ChatMapper {
    @Select("SELECT * FROM \"Conversation\" WHERE \"userId\" = #{userId} ORDER BY \"createdAt\" DESC")
    List<Conversation> findByUserId(String userId);

    @Select("SELECT * FROM \"Conversation\" WHERE id = #{id}")
    Conversation findById(String id);

    @Insert("INSERT INTO \"Conversation\" (id, title, \"userId\", \"createdAt\") VALUES (#{id}, #{title}, #{userId}, NOW())")
    void insertConv(Conversation c);

    @Select("SELECT * FROM \"ChatMessage\" WHERE \"conversationId\" = #{convId} ORDER BY \"createdAt\" DESC LIMIT #{limit} OFFSET #{offset}")
    List<ChatMessage> findMessagesPaged(@Param("convId") String convId, @Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM \"ChatMessage\" WHERE \"conversationId\" = #{convId}")
    int countMessages(String convId);

    @Select("SELECT * FROM \"ChatMessage\" WHERE \"conversationId\" = #{convId} ORDER BY \"createdAt\" DESC")
    List<ChatMessage> findMessages(String convId);

    @Insert("INSERT INTO \"ChatMessage\" (id, \"conversationId\", role, content, \"createdAt\") VALUES (#{id}, #{conversationId}, #{role}, #{content}, NOW())")
    void insertMessage(ChatMessage m);

    @Delete("DELETE FROM \"Conversation\" WHERE id = #{id}")
    void deleteConv(String id);
}
