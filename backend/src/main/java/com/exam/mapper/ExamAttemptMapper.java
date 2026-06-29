package com.exam.mapper;

import com.exam.model.ExamAttempt;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface ExamAttemptMapper {
    @Insert("INSERT INTO \"ExamAttempt\" (id, \"examId\", \"userId\", answers, score, total, \"startedAt\", \"completedAt\") " +
            "VALUES (#{id}, #{examId}, #{userId}, CAST(#{answers} AS json), #{score}, #{total}, NOW(), NOW())")
    void insert(ExamAttempt a);

    @Select("SELECT * FROM \"ExamAttempt\" WHERE \"examId\" = #{examId} AND \"userId\" = #{userId} ORDER BY \"completedAt\" DESC")
    List<ExamAttempt> findByExamAndUser(@Param("examId") String examId, @Param("userId") String userId);

    @Select("SELECT * FROM \"ExamAttempt\" WHERE id = #{id}")
    ExamAttempt findById(String id);

    @Select("SELECT * FROM \"ExamAttempt\" WHERE \"userId\" = #{userId}")
    List<ExamAttempt> findByUserId(String userId);
}
