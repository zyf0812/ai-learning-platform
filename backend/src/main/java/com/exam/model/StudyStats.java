package com.exam.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class StudyStats {
    private String id;
    private String userId;
    private String documentId;
    private Integer studyTime;
    private Integer examCount;
    private Double avgScore;
    private LocalDateTime updatedAt;
}
