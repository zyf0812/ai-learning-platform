package com.exam.mapper;

import com.exam.model.User;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface UserMapper {
    @Select("SELECT * FROM \"User\" WHERE id = #{id}")
    User findById(String id);

    @Select("SELECT * FROM \"User\" WHERE username = #{username}")
    User findByUsername(String username);

    @Insert("INSERT INTO \"User\" (id, username, password, role, status, \"createdAt\") VALUES (#{id}, #{username}, #{password}, #{role}, #{status}, NOW())")
    void insert(User user);

    @Select("SELECT * FROM \"User\" WHERE role = #{role}")
    List<User> findByRole(String role);

    @Select("SELECT id, username, role, status, \"createdAt\" FROM \"User\"")
    List<User> findAll();

    @Update("UPDATE \"User\" SET status = #{status}, role = #{role} WHERE id = #{id}")
    void updateRoleStatus(@Param("id") String id, @Param("role") String role, @Param("status") String status);

    @Delete("DELETE FROM \"User\" WHERE id = #{id}")
    void deleteById(String id);

    @Select("SELECT * FROM \"User\" WHERE role = 'admin' AND id = #{id}")
    User findAdminById(String id);
}
