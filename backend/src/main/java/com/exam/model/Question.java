package com.exam.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class Question {
    private String id;
    private String examId;
    private String type;
    private String content;
    private String options;
    private String answer;
    private String explanation;
    private LocalDateTime createdAt;
}
