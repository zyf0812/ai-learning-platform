package com.exam.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class Document {
    private String id;
    private String title;
    private String originalFilename;
    private String fileType;
    private String content;
    private String userId;
    private Boolean isQuestionBank;
    private LocalDateTime createdAt;
}
