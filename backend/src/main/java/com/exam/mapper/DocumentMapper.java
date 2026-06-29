package com.exam.mapper;

import com.exam.model.Document;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface DocumentMapper {
    @Select("SELECT * FROM \"Document\" WHERE \"userId\" = #{userId} ORDER BY \"createdAt\" DESC")
    List<Document> findByUserId(String userId);

    @Select("SELECT * FROM \"Document\" WHERE id = #{id}")
    Document findById(String id);

    @Insert("INSERT INTO \"Document\" (id, title, \"originalFilename\", \"fileType\", content, \"userId\", \"isQuestionBank\", \"createdAt\") " +
            "VALUES (#{id}, #{title}, #{originalFilename}, #{fileType}, #{content}, #{userId}, #{isQuestionBank}, NOW())")
    void insert(Document doc);

    @Delete("DELETE FROM \"Document\" WHERE id = #{id}")
    void deleteById(String id);
}
