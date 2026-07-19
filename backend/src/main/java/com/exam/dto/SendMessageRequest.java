package com.exam.dto;

import jakarta.validation.constraints.NotBlank;

public class SendMessageRequest {
    @NotBlank(message = "问题内容不能为空")
    private String question;
    private String refDoc;

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String getRefDoc() { return refDoc; }
    public void setRefDoc(String refDoc) { this.refDoc = refDoc; }
}
