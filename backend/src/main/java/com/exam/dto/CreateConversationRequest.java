package com.exam.dto;

import jakarta.validation.constraints.NotBlank;

public class CreateConversationRequest {
    @NotBlank(message = "对话标题不能为空")
    private String title;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
}
