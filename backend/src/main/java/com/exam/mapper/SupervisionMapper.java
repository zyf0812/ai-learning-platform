package com.exam.mapper;

import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;

@Mapper
public interface SupervisionMapper {
    @Update("UPDATE \"User\" SET \"superviseCode\" = #{code} WHERE id = #{userId}")
    void updateSuperviseCode(@Param("userId") String userId, @Param("code") String code);

    @Select("SELECT id, username FROM \"User\" WHERE \"superviseCode\" = #{code}")
    Map<String, Object> findAdminByCode(String code);

    @Insert("INSERT INTO \"Supervision\" (id, \"adminUserId\", \"userId\", status) VALUES (#{id}, #{adminUserId}, #{userId}, #{status})")
    void insert(@Param("id") String id, @Param("adminUserId") String adminUserId, @Param("userId") String userId, @Param("status") String status);

    @Select("SELECT s.*, u.username FROM \"Supervision\" s JOIN \"User\" u ON s.\"userId\" = u.id WHERE s.\"adminUserId\" = #{adminUserId} ORDER BY s.\"createdAt\" DESC")
    List<Map<String, Object>> findByAdmin(String adminUserId);

    @Select("SELECT * FROM \"Supervision\" WHERE \"userId\" = #{userId} AND \"adminUserId\" = #{adminUserId}")
    Map<String, Object> findByUserAndAdmin(@Param("userId") String userId, @Param("adminUserId") String adminUserId);

    @Update("UPDATE \"Supervision\" SET status = #{status} WHERE id = #{id}")
    void updateStatus(@Param("id") String id, @Param("status") String status);

    @Delete("DELETE FROM \"Supervision\" WHERE id = #{id}")
    void delete(String id);
}
