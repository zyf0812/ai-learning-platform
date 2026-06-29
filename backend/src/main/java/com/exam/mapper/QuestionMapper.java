package com.exam.mapper;

import com.exam.model.Question;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface QuestionMapper {
    @Select("SELECT * FROM \"Question\" WHERE \"examId\" = #{examId}")
    List<Question> findByExamId(String examId);

    @Insert("INSERT INTO \"Question\" (id, \"examId\", type, content, options, answer, explanation, \"createdAt\") " +
            "VALUES (#{id}, #{examId}, #{type}, #{content}, #{options}, #{answer}, #{explanation}, NOW())")
    void insert(Question q);

    @Select("SELECT * FROM \"Question\" WHERE id IN (${ids})")
    List<Question> findByIds(@Param("ids") String ids);

    @Select("SELECT * FROM \"Question\" WHERE id = #{id}")
    Question findById(String id);
}
