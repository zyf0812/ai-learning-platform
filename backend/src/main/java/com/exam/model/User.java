package com.exam.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class User {
    private String id;
    private String username;
    private String password;
    private String role;
    private String status;
    private String superviseCode;
    private LocalDateTime createdAt;
}
