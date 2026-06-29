package com.exam.mapper;

import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;

@Mapper
public interface GroupMapper {
    @Insert("INSERT INTO \"StudyGroup\" (id, name, code, \"adminUserId\", \"createdAt\") VALUES (#{id}, #{name}, #{code}, #{adminUserId}, NOW())")
    void insertGroup(@Param("id") String id, @Param("name") String name, @Param("code") String code, @Param("adminUserId") String adminUserId);

    @Select("SELECT g.*, u.username as \"adminName\" FROM \"StudyGroup\" g JOIN \"User\" u ON g.\"adminUserId\" = u.id WHERE g.id = #{id}")
    Map<String, Object> findById(String id);

    @Select("SELECT g.*, u.username as \"adminName\" FROM \"StudyGroup\" g JOIN \"User\" u ON g.\"adminUserId\" = u.id WHERE g.code = #{code}")
    Map<String, Object> findByCode(String code);

    @Select("SELECT g.*, u.username as \"adminName\" FROM \"StudyGroup\" g JOIN \"User\" u ON g.\"adminUserId\" = u.id " +
            "WHERE g.\"adminUserId\" = #{userId} OR g.id IN (SELECT \"groupId\" FROM \"GroupMember\" WHERE \"userId\" = #{userId} AND status = 'approved')")
    List<Map<String, Object>> findByUserId(String userId);

    @Insert("INSERT INTO \"GroupMember\" (id, \"groupId\", \"userId\", status, \"createdAt\") VALUES (#{id}, #{groupId}, #{userId}, #{status}, NOW())")
    void insertMember(@Param("id") String id, @Param("groupId") String groupId, @Param("userId") String userId, @Param("status") String status);

    @Select("SELECT gm.*, u.username FROM \"GroupMember\" gm JOIN \"User\" u ON gm.\"userId\" = u.id WHERE gm.\"groupId\" = #{groupId}")
    List<Map<String, Object>> findMembers(String groupId);

    @Select("SELECT DISTINCT u.id, u.username FROM \"User\" u JOIN \"GroupMember\" gm ON u.id = gm.\"userId\" JOIN \"StudyGroup\" g ON gm.\"groupId\" = g.id WHERE g.\"adminUserId\" = #{adminId} AND gm.status = 'approved'")
    List<Map<String, Object>> findMembersByAdminId(String adminId);

    @Update("UPDATE \"GroupMember\" SET status = #{status} WHERE id = #{id}")
    void updateMemberStatus(@Param("id") String id, @Param("status") String status);

    @Select("SELECT * FROM \"GroupMember\" WHERE \"groupId\" = #{groupId} AND \"userId\" = #{userId}")
    Map<String, Object> findMember(@Param("groupId") String groupId, @Param("userId") String userId);

    @Delete("DELETE FROM \"GroupMember\" WHERE id = #{id}")
    void deleteMember(String id);

    @Delete("DELETE FROM \"StudyGroup\" WHERE id = #{id}")
    void deleteGroup(String id);
}
