package com.exam.mapper;

import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface DocumentChunkMapper {
    @Delete("DELETE FROM \"DocumentChunk\" WHERE \"documentId\" = #{documentId}")
    void deleteByDocumentId(String documentId);

    @Insert("INSERT INTO \"DocumentChunk\" (id, \"documentId\", \"chunkIndex\", content, embedding) " +
            "VALUES (#{id}, #{documentId}, #{chunkIndex}, #{content}, CAST(#{embedding} AS vector))")
    void insert(@Param("id") String id, @Param("documentId") String documentId,
                @Param("chunkIndex") int chunkIndex, @Param("content") String content,
                @Param("embedding") String embedding);

    @Select("SELECT content FROM \"DocumentChunk\" WHERE \"documentId\" = #{documentId} " +
            "ORDER BY embedding <=> CAST(#{queryVec} AS vector) LIMIT #{limit}")
    List<String> searchByDocument(@Param("documentId") String documentId,
                                   @Param("queryVec") String queryVec, @Param("limit") int limit);

    @Select("SELECT content FROM \"DocumentChunk\" WHERE \"documentId\" = #{documentId} " +
            "ORDER BY \"chunkIndex\" LIMIT #{limit}")
    List<String> getChunksByIndex(@Param("documentId") String documentId, @Param("limit") int limit);

    @Select("SELECT content FROM \"DocumentChunk\" WHERE \"documentId\" = #{documentId} " +
            "ORDER BY \"chunkIndex\"")
    List<String> getAllChunks(@Param("documentId") String documentId);

    @Select("SELECT content FROM \"DocumentChunk\" WHERE \"documentId\" = #{documentId} " +
            "AND \"chunkIndex\" % #{step} = 0 ORDER BY \"chunkIndex\"")
    List<String> getChunksUniform(@Param("documentId") String documentId, @Param("step") int step);

    @Select("SELECT content FROM \"DocumentChunk\" WHERE \"documentId\" = #{documentId} " +
            "ORDER BY RANDOM() LIMIT #{limit}")
    List<String> getChunksRandom(@Param("documentId") String documentId, @Param("limit") int limit);

    @Select("SELECT content FROM \"DocumentChunk\" " +
            "ORDER BY embedding <=> CAST(#{queryVec} AS vector) LIMIT #{limit}")
    List<String> searchAll(@Param("queryVec") String queryVec, @Param("limit") int limit);
}
