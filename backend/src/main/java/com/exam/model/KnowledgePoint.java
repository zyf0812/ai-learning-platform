package com.exam.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class KnowledgePoint {
    private String id;
    private String documentId;
    private String title;
    private String content;
    private Integer order;
    private LocalDateTime createdAt;
}
