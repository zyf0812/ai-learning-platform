package com.exam.dto;

public class SendMessageRequest {
    private String question;
    private String refDoc;

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String getRefDoc() { return refDoc; }
    public void setRefDoc(String refDoc) { this.refDoc = refDoc; }
}
