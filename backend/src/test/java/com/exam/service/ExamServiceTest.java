package com.exam.service;

import com.exam.mapper.*;
import com.exam.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

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

    private ExamService examService;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        examService = new ExamService(
            examMapper, questionMapper, docMapper, deepSeek,
            ragService, wqMapper, attemptMapper, taskQueue
        );
    }

    // ==================== parseKeywords (via reflection) ====================

    private List<String> invokeParseKeywords(String explanation) throws Exception {
        Method m = ExamService.class.getDeclaredMethod("parseKeywords", String.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        var result = (List<String>) m.invoke(examService, explanation);
        return result;
    }

    @Test
    void parseKeywords_extractsMarkedKeywords() throws Exception {
        String exp = "这是解析内容。【采分点】封装、继承、多态\n继续其他内容";
        var result = invokeParseKeywords(exp);
        assertEquals(List.of("封装", "继承", "多态"), result);
    }

    @Test
    void parseKeywords_supportsCommaAndSpaceSeparators() throws Exception {
        String exp = "解析。【采分点】Spring, IOC ，AOP、DI\n";
        var result = invokeParseKeywords(exp);
        assertEquals(List.of("Spring", "IOC", "AOP", "DI"), result);
    }

    @Test
    void parseKeywords_emptyWhenNoMarker() throws Exception {
        assertEquals(List.of(), invokeParseKeywords("普通解析无采分点"));
    }

    @Test
    void parseKeywords_nullOrBlankReturnsEmpty() throws Exception {
        assertEquals(List.of(), invokeParseKeywords(null));
        assertEquals(List.of(), invokeParseKeywords(""));
        assertEquals(List.of(), invokeParseKeywords("   "));
    }

    @Test
    void parseKeywords_stopsAtNewline() throws Exception {
        String exp = "【采分点】关键词1、关键词2\n这个换行后不被解析";
        var result = invokeParseKeywords(exp);
        assertEquals(List.of("关键词1", "关键词2"), result);
    }

    @Test
    void parseKeywords_trimsEmptyParts() throws Exception {
        String exp = "【采分点】、， 关键词1  、 、关键词2、";
        var result = invokeParseKeywords(exp);
        assertEquals(List.of("关键词1", "关键词2"), result);
    }

    // ==================== keywordMatch (via reflection) ====================

    private boolean invokeKeywordMatch(String text, String keyword) throws Exception {
        Method m = ExamService.class.getDeclaredMethod("keywordMatch", String.class, String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(examService, text, keyword);
    }

    @Test
    void keywordMatch_exactSubstringReturnsTrue() throws Exception {
        assertTrue(invokeKeywordMatch("java 是面向对象语言", "面向对象"));
        assertTrue(invokeKeywordMatch("spring boot 框架", "spring"));
    }

    @Test
    void keywordMatch_noMatchReturnsFalse() throws Exception {
        assertFalse(invokeKeywordMatch("完全无关内容", "数据库"));
    }

    @Test
    void keywordMatch_coverageModeForShortKeywords() throws Exception {
        // 数据隐藏 vs 信息隐藏 -> 共享"隐藏"两字 -> coverage 2/4 = 0.5 刚好达标
        assertTrue(invokeKeywordMatch("数据隐藏的意义", "信息隐藏"));
    }

    @Test
    void keywordMatch_longKeywordSkipsCoverageMode() throws Exception {
        // 关键词长度 > 6 时不走覆盖率分支
        assertFalse(invokeKeywordMatch("这是测试文本", "一二三四五六七八九十"));
    }

    @Test
    void keywordMatch_coverageBelowThresholdFails() throws Exception {
        // 只有1个字符匹配/4个 -> 0.25 < 0.5
        assertFalse(invokeKeywordMatch("封装技术很重要", "算法"));
    }

    // ==================== submit: choice / fill / truefalse 精确匹配 ====================

    @Test
    void submit_choiceQuestionCorrectScoresPoint() {
        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setAnswer("B");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1", Map.of("q1", "B"));

        assertEquals(1, result.get("score"));
        assertEquals(1, result.get("total"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertTrue((Boolean) graded.get("q1").get("correct"));
    }

    @Test
    void submit_choiceQuestionWrongNoScoreAndMarksWrong() {
        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setAnswer("A");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1", Map.of("q1", "C"));

        assertEquals(0, result.get("score"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertFalse((Boolean) graded.get("q1").get("correct"));
        verify(wqMapper).insert(any(WrongQuestion.class));
    }

    @Test
    void submit_fillAndTruefalseExactMatch() {
        Question fill = new Question();
        fill.setId("qf");
        fill.setType("fill");
        fill.setAnswer("Spring");

        Question tf = new Question();
        tf.setId("qt");
        tf.setType("truefalse");
        tf.setAnswer("正确");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(fill, tf));

        var result = examService.submit("e1", "u1", Map.of(
            "qf", "Spring",
            "qt", "正确"
        ));

        assertEquals(2, result.get("score"));
    }

    @Test
    void submit_missingAnswerTreatedAsEmpty() {
        Question q = new Question();
        q.setId("q1");
        q.setType("fill");
        q.setAnswer("Java");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        // 用户完全没提交这道题的答案
        var result = examService.submit("e1", "u1", Map.of());

        assertEquals(0, result.get("score"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertEquals("", graded.get("q1").get("userAnswer"));
    }

    // ==================== submit: shortanswer 模糊匹配 ====================

    @Test
    void submit_shortanswerWithScoringPointsFuzzyPass() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("面向对象三大特性");
        q.setExplanation("OO基础。【采分点】封装、继承、多态");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        // 用户只命中 2/3 = 0.667 >= 0.6 -> 通过 (isFuzzy)
        var result = examService.submit("e1", "u1",
            Map.of("q1", "封装和继承是重要特性"));

        assertEquals(1, result.get("score"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Map<String, Object>>) result.get("answers");
        var g = graded.get("q1");
        assertTrue((Boolean) g.get("correct"));
        assertTrue((Boolean) g.get("isFuzzyMatch"));
        double hitRatio = (Double) g.get("hitRatio");
        assertTrue(hitRatio > 0.59 && hitRatio < 0.67);
    }

    @Test
    void submit_shortanswerBelowThresholdFails() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("无关");
        q.setExplanation("解析。【采分点】封装、继承、多态、抽象、接口");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        // 只命中 1/5 = 0.2 < 0.6 -> 失败
        var result = examService.submit("e1", "u1", Map.of("q1", "提到了封装而已"));

        assertEquals(0, result.get("score"));
    }

    @Test
    void submit_shortanswerNoMarkerFallbackExactMatch() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("Spring Boot");
        q.setExplanation("无采分点标记的普通解析");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var correct = examService.submit("e1", "u1", Map.of("q1", "Spring Boot"));
        assertEquals(1, correct.get("score"));

        var wrong = examService.submit("e1", "u1", Map.of("q1", "不一样"));
        assertEquals(0, wrong.get("score"));
    }

    @Test
    void submit_shortanswerAllKeywordsPerfect() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("参考");
        q.setExplanation("解析。【采分点】A、B、C");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1", Map.of("q1", "包含A和B还有C全中"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertTrue((Boolean) graded.get("q1").get("correct"));
        assertFalse((Boolean) graded.get("q1").get("isFuzzyMatch"));
        assertEquals(1.0, graded.get("q1").get("hitRatio"));
    }

    // ==================== 错题记录 ====================

    @Test
    void submit_wrongQuestionInsertedWithCorrectCompositeId() {
        Question q = new Question();
        q.setId("q99");
        q.setType("choice");
        q.setAnswer("A");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        examService.submit("e1", "u42", Map.of("q99", "B"));

        verify(wqMapper).insert(argThat(wq ->
            "u42_q99".equals(wq.getId())
            && "u42".equals(wq.getUserId())
            && "q99".equals(wq.getQuestionId())
            && "e1".equals(wq.getExamId())
            && "B".equals(wq.getUserAnswer())
            && !wq.getMastered()
            && wq.getReviewCount() == 0
        ));
    }

    @Test
    void submit_wrongQuestionDuplicateIgnored() {
        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setAnswer("A");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        // 第二次插入重复主键，抛出异常 -> 被 catch 忽略
        doThrow(new RuntimeException("duplicate key"))
            .when(wqMapper).insert(any(WrongQuestion.class));

        // 不应该冒泡
        assertDoesNotThrow(() -> examService.submit("e1", "u1", Map.of("q1", "B")));
    }

    // ==================== 权限检查 ====================

    @Test
    void get_wrongUserIdThrowsAccessDenied() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("owner");
        when(examMapper.findById("e1")).thenReturn(exam);

        var ex = assertThrows(RuntimeException.class,
            () -> examService.get("e1", "other-user"));
        assertTrue(ex.getMessage().contains("无权"));
    }

    @Test
    void get_existingExamReturnsWithQuestions() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("u1");
        exam.setTitle("期中考试");
        exam.setQuestionTypes("choice,fill");
        exam.setQuestionCount(10);
        Question q = new Question();
        q.setId("q1");

        when(examMapper.findById("e1")).thenReturn(exam);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.get("e1", "u1");
        assertEquals("期中考试", result.get("title"));
        assertEquals(10, result.get("questionCount"));
    }

    @Test
    void get_missingExamReturnsNull() {
        when(examMapper.findById("missing")).thenReturn(null);
        assertNull(examService.get("missing", "u1"));
    }

    @Test
    void delete_nonexistentThrows() {
        when(examMapper.findById("gone")).thenReturn(null);
        assertThrows(RuntimeException.class, () -> examService.delete("gone", "u1"));
    }

    @Test
    void delete_wrongUserThrows() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("owner");
        when(examMapper.findById("e1")).thenReturn(exam);

        assertThrows(RuntimeException.class, () -> examService.delete("e1", "hacker"));
        verify(examMapper, never()).deleteById(anyString());
    }

    @Test
    void delete_ownerSucceeds() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("owner");
        when(examMapper.findById("e1")).thenReturn(exam);

        assertDoesNotThrow(() -> examService.delete("e1", "owner"));
        verify(examMapper).deleteById("e1");
    }
}
