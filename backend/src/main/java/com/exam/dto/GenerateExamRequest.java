package com.exam.dto;

import java.util.List;
import java.util.Map;

public class GenerateExamRequest {
    private String title;
    private List<String> documentIds;
    private List<String> types;
    private int count;
    private Map<String, Integer> typeCounts;
    private boolean questionBankMode;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public List<String> getDocumentIds() { return documentIds; }
    public void setDocumentIds(List<String> documentIds) { this.documentIds = documentIds; }
    public List<String> getTypes() { return types; }
    public void setTypes(List<String> types) { this.types = types; }
    public int getCount() { return count; }
    public void setCount(int count) { this.count = count; }
    public Map<String, Integer> getTypeCounts() { return typeCounts; }
    public void setTypeCounts(Map<String, Integer> typeCounts) { this.typeCounts = typeCounts; }
    public boolean isQuestionBankMode() { return questionBankMode; }
    public void setQuestionBankMode(boolean questionBankMode) { this.questionBankMode = questionBankMode; }
}
