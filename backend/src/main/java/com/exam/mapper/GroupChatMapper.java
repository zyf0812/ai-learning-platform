package com.exam.mapper;

import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;

@Mapper
public interface GroupChatMapper {
    @Insert("INSERT INTO \"GroupMessage\" (id, \"groupId\", \"userId\", username, content, \"fileName\", \"fileUrl\", \"createdAt\") " +
            "VALUES (#{id}, #{groupId}, #{userId}, #{username}, #{content}, #{fileName}, #{fileUrl}, NOW())")
    void insertMessage(@Param("id") String id, @Param("groupId") String groupId, @Param("userId") String userId,
                       @Param("username") String username, @Param("content") String content,
                       @Param("fileName") String fileName, @Param("fileUrl") String fileUrl);

    @Select("SELECT * FROM \"GroupMessage\" WHERE \"groupId\" = #{groupId} ORDER BY \"createdAt\" DESC LIMIT 100")
    List<Map<String, Object>> findMessages(String groupId);
}
