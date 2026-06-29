package com.exam.mapper;

import com.exam.model.KnowledgePoint;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface KnowledgeMapper {
    @Select("SELECT * FROM \"KnowledgePoint\" WHERE \"documentId\" = #{documentId} ORDER BY \"order\"")
    List<KnowledgePoint> findByDocumentId(String documentId);

    @Delete("DELETE FROM \"KnowledgePoint\" WHERE \"documentId\" = #{documentId}")
    void deleteByDocumentId(String documentId);

    @Insert("INSERT INTO \"KnowledgePoint\" (id, \"documentId\", title, content, \"order\", \"createdAt\") " +
            "VALUES (#{id}, #{documentId}, #{title}, #{content}, #{order}, NOW())")
    void insert(KnowledgePoint kp);
}
