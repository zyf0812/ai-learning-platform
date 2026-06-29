package com.exam.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ExamAttempt {
    private String id;
    private String examId;
    private String userId;
    private String answers;
    private Integer score;
    private Integer total;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
}
