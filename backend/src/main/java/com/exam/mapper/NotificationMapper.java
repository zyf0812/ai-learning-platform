package com.exam.mapper;

import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;

@Mapper
public interface NotificationMapper {
    @Insert("INSERT INTO \"Notification\" (id, \"fromUserId\", title, content, \"isBroadcast\", \"createdAt\") " +
            "VALUES (#{id}, #{fromUserId}, #{title}, #{content}, true, NOW())")
    void insertBroadcast(@Param("id") String id, @Param("fromUserId") String fromUserId,
                         @Param("title") String title, @Param("content") String content);

    @Insert("INSERT INTO \"Notification\" (id, \"fromUserId\", \"toUserId\", title, content, \"createdAt\") " +
            "VALUES (#{id}, #{fromUserId}, #{toUserId}, #{title}, #{content}, NOW())")
    void insertDirect(@Param("id") String id, @Param("fromUserId") String fromUserId,
                       @Param("toUserId") String toUserId, @Param("title") String title, @Param("content") String content);

    @Select("SELECT * FROM \"Notification\" WHERE \"toUserId\" = #{userId} OR \"isBroadcast\" = true ORDER BY \"createdAt\" DESC LIMIT 50")
    List<Map<String, Object>> findForUser(String userId);

    @Update("UPDATE \"Notification\" SET \"isRead\" = true WHERE id = #{id}")
    void markRead(String id);

    @Select("SELECT COUNT(*) FROM \"Notification\" WHERE (\"toUserId\" = #{userId} OR \"isBroadcast\" = true) AND \"isRead\" = false")
    int countUnread(String userId);
}
