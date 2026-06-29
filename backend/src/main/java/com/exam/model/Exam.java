package com.exam.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class Exam {
    private String id;
    private String title;
    private String userId;
    private String questionTypes;
    private Integer questionCount;
    private LocalDateTime createdAt;
}
