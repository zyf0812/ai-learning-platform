package com.exam.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class Conversation {
    private String id;
    private String title;
    private String documentId;
    private String userId;
    private LocalDateTime createdAt;
}
