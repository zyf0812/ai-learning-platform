package com.exam.service;

import com.exam.mapper.*;
import com.exam.model.Question;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ExamServiceTest {

    @Mock
    private ExamMapper examMapper;

    @Mock
    private QuestionMapper questionMapper;

    @Mock
    private DocumentMapper docMapper;

    @Mock
    private DeepSeekService deepSeek;

    @Mock
    private RAGService ragService;

    @Mock
    private WrongQuestionMapper wqMapper;

    @Mock
    private ExamAttemptMapper attemptMapper;

    @Mock
    private TaskQueueService taskQueue;

    @Test
    void parseKeywordsExtractsFromExplanation() throws Exception {
        ExamService service = new ExamService(examMapper, questionMapper, docMapper,
            deepSeek, ragService, wqMapper, attemptMapper, taskQueue);

        Method parseKeywords = ExamService.class.getDeclaredMethod("parseKeywords", String.class);
        parseKeywords.setAccessible(true);

        String explanation = "解析内容【采分点】封装、继承、多态\n其他内容";
        @SuppressWarnings("unchecked")
        List<String> keywords = (List<String>) parseKeywords.invoke(service, explanation);

        assertEquals(3, keywords.size());
        assertEquals("封装", keywords.get(0));
        assertEquals("继承", keywords.get(1));
        assertEquals("多态", keywords.get(2));
    }

    @Test
    void parseKeywordsReturnsEmptyWhenNoMarker() throws Exception {
        ExamService service = new ExamService(examMapper, questionMapper, docMapper,
            deepSeek, ragService, wqMapper, attemptMapper, taskQueue);

        Method parseKeywords = ExamService.class.getDeclaredMethod("parseKeywords", String.class);
        parseKeywords.setAccessible(true);

        String explanation = "普通解析内容，没有采分点标记";
        @SuppressWarnings("unchecked")
        List<String> keywords = (List<String>) parseKeywords.invoke(service, explanation);

        assertTrue(keywords.isEmpty());
    }

    @Test
    void parseKeywordsReturnsEmptyForNull() throws Exception {
        ExamService service = new ExamService(examMapper, questionMapper, docMapper,
            deepSeek, ragService, wqMapper, attemptMapper, taskQueue);

        Method parseKeywords = ExamService.class.getDeclaredMethod("parseKeywords", String.class);
        parseKeywords.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> keywords = (List<String>) parseKeywords.invoke(service, (String) null);

        assertTrue(keywords.isEmpty());
    }

    @Test
    void keywordMatchExactSubstring() throws Exception {
        ExamService service = new ExamService(examMapper, questionMapper, docMapper,
            deepSeek, ragService, wqMapper, attemptMapper, taskQueue);

        Method keywordMatch = ExamService.class.getDeclaredMethod("keywordMatch", String.class, String.class);
        keywordMatch.setAccessible(true);

        boolean result = (boolean) keywordMatch.invoke(service, "Java中的封装特性", "封装");
        assertTrue(result);
    }

    @Test
    void keywordMatchCharCoverage() throws Exception {
        ExamService service = new ExamService(examMapper, questionMapper, docMapper,
            deepSeek, ragService, wqMapper, attemptMapper, taskQueue);

        Method keywordMatch = ExamService.class.getDeclaredMethod("keywordMatch", String.class, String.class);
        keywordMatch.setAccessible(true);

        boolean result = (boolean) keywordMatch.invoke(service, "数据隐藏机制", "封装");
        assertFalse(result);
    }

    @Test
    void keywordMatchSynonymCoverage() throws Exception {
        ExamService service = new ExamService(examMapper, questionMapper, docMapper,
            deepSeek, ragService, wqMapper, attemptMapper, taskQueue);

        Method keywordMatch = ExamService.class.getDeclaredMethod("keywordMatch", String.class, String.class);
        keywordMatch.setAccessible(true);

        boolean result = (boolean) keywordMatch.invoke(service, "信息隐藏", "数据隐藏");
        assertTrue(result);
    }

    @Test
    void keywordMatchLongKeywordIgnored() throws Exception {
        ExamService service = new ExamService(examMapper, questionMapper, docMapper,
            deepSeek, ragService, wqMapper, attemptMapper, taskQueue);

        Method keywordMatch = ExamService.class.getDeclaredMethod("keywordMatch", String.class, String.class);
        keywordMatch.setAccessible(true);

        boolean result = (boolean) keywordMatch.invoke(service, "test", "this is a very long keyword that should be ignored");
        assertFalse(result);
    }

    @Test
    void parseKeywordsWithCommaDelimiter() throws Exception {
        ExamService service = new ExamService(examMapper, questionMapper, docMapper,
            deepSeek, ragService, wqMapper, attemptMapper, taskQueue);

        Method parseKeywords = ExamService.class.getDeclaredMethod("parseKeywords", String.class);
        parseKeywords.setAccessible(true);

        String explanation = "解析【采分点】关键词1，关键词2，关键词3";
        @SuppressWarnings("unchecked")
        List<String> keywords = (List<String>) parseKeywords.invoke(service, explanation);

        assertEquals(3, keywords.size());
    }

    @Test
    void parseKeywordsWithSpaceDelimiter() throws Exception {
        ExamService service = new ExamService(examMapper, questionMapper, docMapper,
            deepSeek, ragService, wqMapper, attemptMapper, taskQueue);

        Method parseKeywords = ExamService.class.getDeclaredMethod("parseKeywords", String.class);
        parseKeywords.setAccessible(true);

        String explanation = "解析【采分点】关键词1 关键词2 关键词3";
        @SuppressWarnings("unchecked")
        List<String> keywords = (List<String>) parseKeywords.invoke(service, explanation);

        assertEquals(3, keywords.size());
    }

    @Test
    void keywordMatchEmptyTextReturnsFalse() throws Exception {
        ExamService service = new ExamService(examMapper, questionMapper, docMapper,
            deepSeek, ragService, wqMapper, attemptMapper, taskQueue);

        Method keywordMatch = ExamService.class.getDeclaredMethod("keywordMatch", String.class, String.class);
        keywordMatch.setAccessible(true);

        boolean result = (boolean) keywordMatch.invoke(service, "", "关键词");
        assertFalse(result);
    }
}