package com.exam.mapper;

import com.exam.model.WrongQuestion;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface WrongQuestionMapper {
    @Select("SELECT * FROM \"WrongQuestion\" WHERE \"userId\" = #{userId} ORDER BY \"createdAt\" DESC")
    List<WrongQuestion> findByUserId(String userId);

    @Update("UPDATE \"WrongQuestion\" SET mastered = #{mastered} WHERE id = #{id}")
    void updateMastered(@Param("id") String id, @Param("mastered") boolean mastered);

    @Insert("INSERT INTO \"WrongQuestion\" (id, \"userId\", \"questionId\", \"examId\", \"userAnswer\", \"reviewCount\", mastered, \"createdAt\") " +
            "VALUES (#{id}, #{userId}, #{questionId}, #{examId}, #{userAnswer}, #{reviewCount}, #{mastered}, NOW())")
    void insert(WrongQuestion wq);
}
