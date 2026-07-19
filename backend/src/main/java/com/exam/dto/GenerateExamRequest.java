package com.exam.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

import java.util.List;
import java.util.Map;

public class GenerateExamRequest {
    @NotBlank(message = "试卷标题不能为空")
    private String title;

    @NotEmpty(message = "请选择至少一个文档")
    private List<String> documentIds;

    private List<String> types;
    @Positive(message = "题目数量必须大于0")
    @Max(value = 200, message = "单次最多生成200题")
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
