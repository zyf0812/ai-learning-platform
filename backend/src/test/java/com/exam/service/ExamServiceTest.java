package com.exam.service;

import com.exam.mapper.*;
import com.exam.model.Exam;
import com.exam.model.Question;
import com.exam.model.WrongQuestion;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExamServiceTest {

    @Mock private ExamMapper examMapper;
    @Mock private QuestionMapper questionMapper;
    @Mock private DocumentMapper docMapper;
    @Mock private DeepSeekService deepSeek;
    @Mock private RAGService ragService;
    @Mock private WrongQuestionMapper wqMapper;
    @Mock private ExamAttemptMapper attemptMapper;
    @Mock private TaskQueueService taskQueue;

    private ExamService examService;
    private ObjectMapper json;

    @BeforeEach
    void setUp() {
        json = new ObjectMapper();
        examService = new ExamService(
            examMapper, questionMapper, docMapper, deepSeek, ragService,
            wqMapper, attemptMapper, taskQueue);
    }

    // ==================== 权限校验测试 ====================

    @Test
    void getExamWithWrongUserThrows() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("owner123");

        when(examMapper.findById("e1")).thenReturn(exam);

        var ex = assertThrows(RuntimeException.class,
            () -> examService.get("e1", "intruder"));
        assertEquals("无权访问该考试", ex.getMessage());
    }

    @Test
    void getExamWithCorrectUserSucceeds() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("user123");
        exam.setTitle("Test Exam");
        exam.setQuestionTypes("choice,fill");
        exam.setQuestionCount(2);

        when(examMapper.findById("e1")).thenReturn(exam);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of());

        var result = examService.get("e1", "user123");
        assertNotNull(result);
        assertEquals("e1", result.get("id"));
        assertEquals("Test Exam", result.get("title"));
    }

    @Test
    void getNonexistentExamReturnsNull() {
        when(examMapper.findById("missing")).thenReturn(null);
        assertNull(examService.get("missing", "anyuser"));
    }

    @Test
    void deleteExamWithWrongUserThrows() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("owner");

        when(examMapper.findById("e1")).thenReturn(exam);

        var ex = assertThrows(RuntimeException.class,
            () -> examService.delete("e1", "wronguser"));
        assertEquals("无权删除该考试", ex.getMessage());
        verify(examMapper, never()).deleteById(anyString());
    }

    @Test
    void deleteNonexistentExamThrows() {
        when(examMapper.findById("missing")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> examService.delete("missing", "anyuser"));
        assertEquals("考试不存在", ex.getMessage());
    }

    @Test
    void deleteExamWithCorrectUserSucceeds() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("user123");

        when(examMapper.findById("e1")).thenReturn(exam);
        doNothing().when(examMapper).deleteById("e1");

        assertDoesNotThrow(() -> examService.delete("e1", "user123"));
        verify(examMapper).deleteById("e1");
    }

    @Test
    void listExamsByUserId() {
        Exam e1 = new Exam();
        e1.setId("e1");
        Exam e2 = new Exam();
        e2.setId("e2");
        when(examMapper.findByUserId("u1")).thenReturn(List.of(e1, e2));

        var list = examService.list("u1");
        assertEquals(2, list.size());
    }

    // ==================== 判分逻辑测试（精确匹配题型） ====================

    @Test
    void submitChoiceQuestionCorrectAnswerScores() {
        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setAnswer("B");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        var answers = Map.of("q1", "B");
        var result = examService.submit("e1", "u1", answers);

        assertEquals(1, result.get("score"));
        assertEquals(1, result.get("total"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertTrue((Boolean) graded.get("q1").get("correct"));
    }

    @Test
    void submitChoiceQuestionWrongAnswerRecordsWrongQuestion() {
        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setAnswer("A");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        var answers = Map.of("q1", "C");
        var result = examService.submit("e1", "u1", answers);

        assertEquals(0, result.get("score"));
        ArgumentCaptor<WrongQuestion> wqCaptor = ArgumentCaptor.forClass(WrongQuestion.class);
        verify(wqMapper).insert(wqCaptor.capture());
        assertEquals("u1_q1", wqCaptor.getValue().getId());
        assertEquals("C", wqCaptor.getValue().getUserAnswer());
        assertFalse(wqCaptor.getValue().getMastered());
        assertEquals(0, wqCaptor.getValue().getReviewCount());
    }

    @Test
    void submitFillBlankExactMatchCorrect() {
        Question q = new Question();
        q.setId("q1");
        q.setType("fill");
        q.setAnswer("Spring");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        var result = examService.submit("e1", "u1", Map.of("q1", "Spring"));
        assertEquals(1, result.get("score"));
    }

    @Test
    void submitTrueFalseExactMatchCorrect() {
        Question q = new Question();
        q.setId("q1");
        q.setType("truefalse");
        q.setAnswer("正确");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        var result = examService.submit("e1", "u1", Map.of("q1", "正确"));
        assertEquals(1, result.get("score"));
    }

    @Test
    void submitEmptyAnswerTreatedAsWrong() {
        Question q = new Question();
        q.setId("q1");
        q.setType("fill");
        q.setAnswer("Java");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        var result = examService.submit("e1", "u1", Map.of());
        assertEquals(0, result.get("score"));
    }

    // ==================== 简答题（含采分点）测试 ====================

    @Test
    void submitShortAnswerPerfectKeywordMatch() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("封装继承多态");
        q.setExplanation("面向对象三大特性【采分点】封装、继承、多态");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        var userAnswer = "面向对象的三大特性是封装、继承和多态";
        var result = examService.submit("e1", "u1", Map.of("q1", userAnswer));

        assertEquals(1, result.get("score"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertTrue((Boolean) graded.get("q1").get("correct"));
        assertEquals(1.0, graded.get("q1").get("hitRatio"));
        assertFalse((Boolean) graded.get("q1").get("isFuzzyMatch"));
    }

    @Test
    void submitShortAnswerPartialFuzzyMatchPasses() {
        // 60% 阈值：命中 2/3 = 66.7% 应通过
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("参考");
        q.setExplanation("解析...【采分点】封装、继承、多态、抽象");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        // 命中 3/4 = 75%，应通过（>60% 阈值）
        var userAnswer = "面向对象包括封装、继承、多态三个特性";
        var result = examService.submit("e1", "u1", Map.of("q1", userAnswer));

        assertEquals(1, result.get("score"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertTrue((Boolean) graded.get("q1").get("correct"));
        assertTrue((Boolean) graded.get("q1").get("isFuzzyMatch"));
        double ratio = (Double) graded.get("q1").get("hitRatio");
        assertTrue(ratio >= 0.6 && ratio < 1.0,
            "Expected fuzzy ratio between 0.6 and 1.0, got: " + ratio);
    }

    @Test
    void submitShortAnswerBelowThresholdFails() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("参考");
        q.setExplanation("解析...【采分点】封装、继承、多态、抽象");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        // 仅命中 1/4 = 25%，应失败
        var userAnswer = "Java有封装这个特性";
        var result = examService.submit("e1", "u1", Map.of("q1", userAnswer));

        assertEquals(0, result.get("score"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertFalse((Boolean) graded.get("q1").get("correct"));
    }

    @Test
    void submitShortAnswerNoKeywordsFallsBackToExactMatch() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("标准答案文本");
        q.setExplanation("普通解析，没有采分点标记");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        // 精确匹配应通过
        var result1 = examService.submit("e1", "u1", Map.of("q1", "标准答案文本"));
        assertEquals(1, result1.get("score"));

        // 不完全匹配应失败
        var result2 = examService.submit("e1", "u1", Map.of("q1", "差不多的答案"));
        assertEquals(0, result2.get("score"));
    }

    @Test
    void submitShortAnswerEmptyExplanationFallsBackToExact() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("OK");
        q.setExplanation("");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        assertEquals(1, examService.submit("e1", "u1", Map.of("q1", "OK")).get("score"));
        assertEquals(0, examService.submit("e1", "u1", Map.of("q1", "not ok")).get("score"));
    }

    // ==================== 通过反射测试私有工具方法 ====================

    private List<String> invokeParseKeywords(String explanation) throws Exception {
        Method m = ExamService.class.getDeclaredMethod("parseKeywords", String.class);
        m.setAccessible(true);
        return (List<String>) m.invoke(examService, explanation);
    }

    private boolean invokeKeywordMatch(String text, String keyword) throws Exception {
        Method m = ExamService.class.getDeclaredMethod("keywordMatch", String.class, String.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(examService, text, keyword);
    }

    @Test
    void parseKeywordsExtractsAfterMarker() throws Exception {
        var result = invokeParseKeywords("解析内容【采分点】关键词1、关键词2、关键词3");
        assertEquals(List.of("关键词1", "关键词2", "关键词3"), result);
    }

    @Test
    void parseKeywordsStopsAtNewline() throws Exception {
        var result = invokeParseKeywords("【采分点】A、B、C\n这是后续内容不应被解析");
        assertEquals(List.of("A", "B", "C"), result);
    }

    @Test
    void parseKeywordsSupportsMultipleSeparators() throws Exception {
        var result = invokeParseKeywords("【采分点】封装,继承 多态，抽象");
        assertEquals(List.of("封装", "继承", "多态", "抽象"), result);
    }

    @Test
    void parseKeywordsNoMarkerReturnsEmpty() throws Exception {
        assertTrue(invokeParseKeywords("普通解析没有采分点").isEmpty());
        assertTrue(invokeParseKeywords("").isEmpty());
        assertTrue(invokeParseKeywords(null).isEmpty());
    }

    @Test
    void keywordMatchSubstringContainsWorks() throws Exception {
        assertTrue(invokeKeywordMatch("面向对象编程有封装的特性", "封装"));
    }

    @Test
    void keywordMatchCharacterCoverageAboveHalfWorks() throws Exception {
        // "数据隐藏" vs "信息隐藏"：共享"隐藏" 2/4 = 50%，刚好满足
        assertTrue(invokeKeywordMatch("信息隐藏是重要原则", "数据隐藏"));
    }

    @Test
    void keywordMatchBelowCoverageFails() throws Exception {
        // 长关键词（>6）不做覆盖率匹配，只做子串
        assertFalse(invokeKeywordMatch("完全不相关的内容", "封装"));
    }

    @Test
    void keywordMatchLongKeywordOnlySubstring() throws Exception {
        // 关键词长度 >6 只做子串匹配，不做覆盖率
        String longKw = "八种基本数据类型";
        assertFalse(invokeKeywordMatch("基本类型很多", longKw));
        assertTrue(invokeKeywordMatch("Java有八种基本数据类型", longKw));
    }

    // ==================== 任务状态查询 ====================

    @Test
    void getTaskStatusDelegatesToTaskQueue() {
        when(taskQueue.getStatus("exam:task:", "task123")).thenReturn("DONE:exam456");
        assertEquals("DONE:exam456", examService.getTaskStatus("task123"));
    }

    // ==================== 混合题型批量判分 ====================

    @Test
    void submitMixedQuestionTypesAllCorrect() {
        Question q1 = new Question();
        q1.setId("q1"); q1.setType("choice"); q1.setAnswer("A");

        Question q2 = new Question();
        q2.setId("q2"); q2.setType("fill"); q2.setAnswer("Java");

        Question q3 = new Question();
        q3.setId("q3"); q3.setType("truefalse"); q3.setAnswer("错误");

        Question q4 = new Question();
        q4.setId("q4"); q4.setType("shortanswer"); q4.setAnswer("ref");
        q4.setExplanation("解析【采分点】封装、多态");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q1, q2, q3, q4));
        doNothing().when(attemptMapper).insert(any());

        var answers = new HashMap<String, String>();
        answers.put("q1", "A");
        answers.put("q2", "Java");
        answers.put("q3", "错误");
        answers.put("q4", "封装和多态很重要");

        var result = examService.submit("e1", "u1", answers);
        assertEquals(4, result.get("score"));
        assertEquals(4, result.get("total"));
    }

    // ==================== 答案空白字符处理 ====================

    @Test
    void submitAnswerWhitespaceTrimmed() {
        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setAnswer("B");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        // 前后有空格应被 trim 后匹配
        assertEquals(1, examService.submit("e1", "u1", Map.of("q1", "  B  ")).get("score"));
    }
}
