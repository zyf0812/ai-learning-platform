package com.exam.mapper;

import com.exam.model.*;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface ExamMapper {
    @Select("SELECT * FROM \"Exam\" WHERE \"userId\" = #{userId} ORDER BY \"createdAt\" DESC")
    List<Exam> findByUserId(String userId);

    @Select("SELECT * FROM \"Exam\" WHERE id = #{id}")
    Exam findById(String id);

    @Insert("INSERT INTO \"Exam\" (id, title, \"userId\", \"questionTypes\", \"questionCount\", \"createdAt\") " +
            "VALUES (#{id}, #{title}, #{userId}, #{questionTypes}, #{questionCount}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(Exam exam);

    @Delete("DELETE FROM \"Exam\" WHERE id = #{id}")
    void deleteById(String id);
}
