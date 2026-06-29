package com.exam.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class FlashCard {
    private String id;
    private String knowledgePointId;
    private String front;
    private String back;
    private Double easeFactor;
    private Integer interval;
    private Integer repetitions;
    private LocalDateTime nextReview;
    private LocalDateTime createdAt;
}
