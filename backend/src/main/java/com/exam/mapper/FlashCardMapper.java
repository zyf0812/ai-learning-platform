package com.exam.mapper;

import com.exam.model.FlashCard;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface FlashCardMapper {
    @Select("SELECT f.* FROM \"FlashCard\" f JOIN \"KnowledgePoint\" k ON f.\"knowledgePointId\" = k.id JOIN \"Document\" d ON k.\"documentId\" = d.id WHERE d.\"userId\" = #{userId} AND f.\"nextReview\" <= NOW() ORDER BY f.\"nextReview\" LIMIT #{limit}")
    List<FlashCard> findDueCards(@Param("userId") String userId, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM \"FlashCard\" f JOIN \"KnowledgePoint\" k ON f.\"knowledgePointId\" = k.id JOIN \"Document\" d ON k.\"documentId\" = d.id WHERE d.\"userId\" = #{userId}")
    int countByUserId(String userId);

    @Select("SELECT COUNT(*) FROM \"FlashCard\" f JOIN \"KnowledgePoint\" k ON f.\"knowledgePointId\" = k.id JOIN \"Document\" d ON k.\"documentId\" = d.id WHERE d.\"userId\" = #{userId} AND f.\"nextReview\" <= NOW()")
    int countDue(String userId);

    @Update("UPDATE \"FlashCard\" SET \"easeFactor\"=#{easeFactor}, \"interval\"=#{interval}, \"repetitions\"=#{repetitions}, \"nextReview\"=#{nextReview} WHERE id=#{id}")
    void updateReview(FlashCard card);

    @Select("SELECT * FROM \"FlashCard\" WHERE id = #{id}")
    FlashCard findById(String id);

    @Insert("INSERT INTO \"FlashCard\" (id, \"knowledgePointId\", front, back, \"createdAt\") VALUES (#{id}, #{knowledgePointId}, #{front}, #{back}, NOW())")
    void insert(FlashCard card);
}
