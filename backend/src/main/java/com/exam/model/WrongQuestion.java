package com.exam.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class WrongQuestion {
    private String id;
    private String userId;
    private String questionId;
    private String examId;
    private String userAnswer;
    private Integer reviewCount;
    private Boolean mastered;
    private LocalDateTime createdAt;
}
