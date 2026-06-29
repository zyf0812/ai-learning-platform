package com.exam.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ChatMessage {
    private String id;
    private String conversationId;
    private String role;
    private String content;
    private LocalDateTime createdAt;
}
