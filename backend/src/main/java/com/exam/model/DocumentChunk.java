package com.exam.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class DocumentChunk {
    private String id;
    private String documentId;
    private Integer chunkIndex;
    private String content;
    private LocalDateTime createdAt;
}
