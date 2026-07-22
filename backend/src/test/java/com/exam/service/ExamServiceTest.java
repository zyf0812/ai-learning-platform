package com.exam.service;

import com.exam.model.Question;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExamServiceTest {

    @Test
    void parseKeywordsExtractsFromExplanation() throws Exception {
        Method method = ExamService.class.getDeclaredMethod("parseKeywords", String.class);
        method.setAccessible(true);
        
        String explanation = "解析内容【采分点】封装、继承、多态";
        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) method.invoke(null, explanation);
        
        assertEquals(3, result.size());
        assertEquals("封装", result.get(0));
        assertEquals("继承", result.get(1));
        assertEquals("多态", result.get(2));
    }

    @Test
    void parseKeywordsHandlesNoMark() throws Exception {
        Method method = ExamService.class.getDeclaredMethod("parseKeywords", String.class);
        method.setAccessible(true);
        
        String explanation = "解析内容没有采分点标记";
        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) method.invoke(null, explanation);
        
        assertTrue(result.isEmpty());
    }

    @Test
    void parseKeywordsHandlesEmptyExplanation() throws Exception {
        Method method = ExamService.class.getDeclaredMethod("parseKeywords", String.class);
        method.setAccessible(true);
        
        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) method.invoke(null, "");
        
        assertTrue(result.isEmpty());
    }

    @Test
    void parseKeywordsHandlesNullExplanation() throws Exception {
        Method method = ExamService.class.getDeclaredMethod("parseKeywords", String.class);
        method.setAccessible(true);
        
        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) method.invoke(null, (String) null);
        
        assertTrue(result.isEmpty());
    }

    @Test
    void parseKeywordsStopsAtNewLine() throws Exception {
        Method method = ExamService.class.getDeclaredMethod("parseKeywords", String.class);
        method.setAccessible(true);
        
        String explanation = "解析内容【采分点】关键词1、关键词2\n后续内容不应该被解析";
        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) method.invoke(null, explanation);
        
        assertEquals(2, result.size());
        assertFalse(result.contains("后续内容"));
    }

    @Test
    void parseKeywordsHandlesDifferentDelimiters() throws Exception {
        Method method = ExamService.class.getDeclaredMethod("parseKeywords", String.class);
        method.setAccessible(true);
        
        String explanation = "解析【采分点】关键词1,关键词2 关键词3，关键词4、关键词5";
        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) method.invoke(null, explanation);
        
        assertEquals(5, result.size());
    }

    @Test
    void keywordMatchExactContains() throws Exception {
        Method method = ExamService.class.getDeclaredMethod("keywordMatch", String.class, String.class);
        method.setAccessible(true);
        
        Boolean result = (Boolean) method.invoke(null, "Java中的封装特性", "封装");
        
        assertTrue(result);
    }

    @Test
    void keywordMatchNoMatch() throws Exception {
        Method method = ExamService.class.getDeclaredMethod("keywordMatch", String.class, String.class);
        method.setAccessible(true);
        
        Boolean result = (Boolean) method.invoke(null, "Java中的继承特性", "多态");
        
        assertFalse(result);
    }

    @Test
    void keywordMatchCharCoverage() throws Exception {
        Method method = ExamService.class.getDeclaredMethod("keywordMatch", String.class, String.class);
        method.setAccessible(true);
        
        Boolean result = (Boolean) method.invoke(null, "数据隐藏", "信息隐藏");
        
        assertTrue(result);
    }

    @Test
    void keywordMatchLongKeywordNoCoverage() throws Exception {
        Method method = ExamService.class.getDeclaredMethod("keywordMatch", String.class, String.class);
        method.setAccessible(true);
        
        Boolean result = (Boolean) method.invoke(null, "基础", "面向对象编程的基本特征");
        
        assertFalse(result);
    }

    @Test
    void keywordMatchEmptyText() throws Exception {
        Method method = ExamService.class.getDeclaredMethod("keywordMatch", String.class, String.class);
        method.setAccessible(true);
        
        Boolean result = (Boolean) method.invoke(null, "", "关键词");
        
        assertFalse(result);
    }

    @Test
    void keywordMatchEmptyKeyword() throws Exception {
        Method method = ExamService.class.getDeclaredMethod("keywordMatch", String.class, String.class);
        method.setAccessible(true);
        
        Boolean result = (Boolean) method.invoke(null, "文本内容", "");
        
        assertFalse(result);
    }

    @Test
    void keywordMatchCaseInsensitive() throws Exception {
        Method method = ExamService.class.getDeclaredMethod("keywordMatch", String.class, String.class);
        method.setAccessible(true);
        
        Boolean result = (Boolean) method.invoke(null, "java中的封装", "封装");
        
        assertTrue(result);
    }

    @Test
    void keywordMatchPartialCharCoverageBelowThreshold() throws Exception {
        Method method = ExamService.class.getDeclaredMethod("keywordMatch", String.class, String.class);
        method.setAccessible(true);
        
        Boolean result = (Boolean) method.invoke(null, "封装", "面向对象");
        
        assertFalse(result);
    }

    @Test
    void keywordMatchCharCoverageExactlyHalf() throws Exception {
        Method method = ExamService.class.getDeclaredMethod("keywordMatch", String.class, String.class);
        method.setAccessible(true);
        
        Boolean result = (Boolean) method.invoke(null, "数据", "数据结构");
        
        assertTrue(result);
    }
}