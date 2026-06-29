package com.exam.dto;

public class UpdateWrongQuestionRequest {
    private String id;
    private boolean mastered;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public boolean isMastered() { return mastered; }
    public void setMastered(boolean mastered) { this.mastered = mastered; }
}
