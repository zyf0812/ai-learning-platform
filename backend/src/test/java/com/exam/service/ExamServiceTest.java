package com.exam.service;

import com.exam.mapper.*;
import com.exam.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        examService = new ExamService(examMapper, questionMapper, docMapper,
            deepSeek, ragService, wqMapper, attemptMapper, taskQueue);
    }

    // ========== 权限校验 ==========

    @Test
    void getByOtherUserThrowsAccessDenied() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("owner");
        exam.setTitle("Test");
        when(examMapper.findById("e1")).thenReturn(exam);

        var ex = assertThrows(RuntimeException.class,
            () -> examService.get("e1", "attacker"));
        assertTrue(ex.getMessage().contains("无权"));
    }

    @Test
    void getByOwnerReturnsDetails() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("owner");
        exam.setTitle("Test");
        exam.setQuestionTypes("choice,fill");
        exam.setQuestionCount(2);
        when(examMapper.findById("e1")).thenReturn(exam);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of());

        var result = examService.get("e1", "owner");
        assertEquals("e1", result.get("id"));
        assertEquals("Test", result.get("title"));
    }

    @Test
    void getMissingExamReturnsNull() {
        when(examMapper.findById("missing")).thenReturn(null);
        assertNull(examService.get("missing", "any"));
    }

    @Test
    void deleteByOtherUserThrows() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("owner");
        when(examMapper.findById("e1")).thenReturn(exam);

        var ex = assertThrows(RuntimeException.class,
            () -> examService.delete("e1", "attacker"));
        assertTrue(ex.getMessage().contains("无权"));
        verify(examMapper, never()).deleteById("e1");
    }

    @Test
    void deleteMissingExamThrows() {
        when(examMapper.findById("missing")).thenReturn(null);
        assertThrows(RuntimeException.class,
            () -> examService.delete("missing", "any"));
    }

    @Test
    void deleteByOwnerSucceeds() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("owner");
        when(examMapper.findById("e1")).thenReturn(exam);
        doNothing().when(examMapper).deleteById("e1");

        assertDoesNotThrow(() -> examService.delete("e1", "owner"));
        verify(examMapper).deleteById("e1");
    }

    // ========== 评分：选择题 ==========

    @Test
    void submitChoiceCorrectAnswerGetsPoint() {
        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setContent("Java之父？");
        q.setAnswer("B");
        q.setExplanation("");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        var answers = new HashMap<String, String>();
        answers.put("q1", "B");

        var result = examService.submit("e1", "u1", answers);
        assertEquals(1, result.get("score"));
        assertEquals(1, result.get("total"));

        @SuppressWarnings("unchecked")
        var graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        var gq1 = (Map<String, Object>) graded.get("q1");
        assertTrue((Boolean) gq1.get("correct"));
        assertEquals(1.0, gq1.get("hitRatio"));
    }

    @Test
    void submitChoiceWrongAnswerNoPoint() {
        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setAnswer("B");
        q.setExplanation("");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(wqMapper).insert(any(WrongQuestion.class));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        var answers = new HashMap<String, String>();
        answers.put("q1", "A");

        var result = examService.submit("e1", "u1", answers);
        assertEquals(0, result.get("score"));
        verify(wqMapper).insert(any(WrongQuestion.class));
    }

    // ========== 评分：填空题/判断题 ==========

    @Test
    void submitFillBlankExactMatch() {
        Question q = new Question();
        q.setId("q1");
        q.setType("fill");
        q.setAnswer("Spring");
        q.setExplanation("");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        var answers = new HashMap<String, String>();
        answers.put("q1", "Spring");

        var result = examService.submit("e1", "u1", answers);
        assertEquals(1, result.get("score"));
    }

    @Test
    void submitTrueFalseCorrect() {
        Question q = new Question();
        q.setId("q1");
        q.setType("truefalse");
        q.setAnswer("正确");
        q.setExplanation("");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        var answers = new HashMap<String, String>();
        answers.put("q1", "正确");

        assertEquals(1, examService.submit("e1", "u1", answers).get("score"));
    }

    // ========== 评分：简答题 - 采分点模糊匹配 ==========

    @Test
    void submitShortAnswerAllKeywordsHit_100Percent() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("面向对象三大特性");
        q.setExplanation("封装、继承、多态\n【采分点】封装、继承、多态");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        var answers = new HashMap<String, String>();
        answers.put("q1", "Java的面向对象包括封装、继承、多态三个特性。");

        var result = examService.submit("e1", "u1", answers);
        assertEquals(1, result.get("score"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Object>) ((Map<String, Object>) result.get("answers")).get("q1");
        assertEquals(1.0, graded.get("hitRatio"));
    }

    @Test
    void submitShortAnswerThresholdBoundary_60PercentPasses() {
        // 5个关键词命中3个 = 60% → 通过（阈值>=0.6）
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("多线程实现方法");
        q.setExplanation("四种方法\n【采分点】继承Thread、实现Runnable、实现Callable、线程池");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        var answers = new HashMap<String, String>();
        // 命中3个：继承Thread、实现Runnable、实现Callable
        answers.put("q1", "可以继承Thread类，也可以实现Runnable接口或者实现Callable接口。");

        var result = examService.submit("e1", "u1", answers);
        assertEquals(1, result.get("score"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Object>) ((Map<String, Object>) result.get("answers")).get("q1");
        assertEquals(0.75, graded.get("hitRatio")); // 3/4 = 0.75
        assertTrue((Boolean) graded.get("isFuzzyMatch"));
    }

    @Test
    void submitShortAnswerBelowThreshold_50PercentFails() {
        // 4个关键词命中2个 = 50% → 失败（<60%）
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("多线程实现方法");
        q.setExplanation("xxx\n【采分点】继承Thread、实现Runnable、实现Callable、线程池");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(wqMapper).insert(any(WrongQuestion.class));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        var answers = new HashMap<String, String>();
        // 只命中2个
        answers.put("q1", "用继承Thread类，或者实现Runnable接口。");

        var result = examService.submit("e1", "u1", answers);
        assertEquals(0, result.get("score"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Object>) ((Map<String, Object>) result.get("answers")).get("q1");
        assertEquals(0.5, graded.get("hitRatio"));
        assertFalse((Boolean) graded.get("correct"));
    }

    @Test
    void submitShortAnswerNoKeywordsMarker_FallsBackExact() {
        // 没有【采分点】标记 → 回退精确匹配
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("Spring框架");
        q.setExplanation("Spring是一个Java框架。");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        var answers = new HashMap<String, String>();
        answers.put("q1", "Spring框架");

        assertEquals(1, examService.submit("e1", "u1", answers).get("score"));
    }

    @Test
    void submitShortAnswerExactMismatchWithNoKeywordsFails() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("Spring框架");
        q.setExplanation("解释。");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(wqMapper).insert(any(WrongQuestion.class));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        var answers = new HashMap<String, String>();
        answers.put("q1", "完全不相关的答案");

        assertEquals(0, examService.submit("e1", "u1", answers).get("score"));
    }

    @Test
    void submitShortAnswerKeywordCoverageMatch_SimilarWords() {
        // 字符覆盖率匹配："数据隐藏" vs 用户答案含"信息隐藏"（共享"隐藏"）
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("封装的意义");
        q.setExplanation("【采分点】数据隐藏、封装");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        var answers = new HashMap<String, String>();
        answers.put("q1", "封装实现了信息隐藏。");

        var result = examService.submit("e1", "u1", answers);
        // 封装 子串包含命中，数据隐藏 字符覆盖率（"隐藏"共享2字/4字=50%）→ 命中
        assertEquals(1, result.get("score"));
    }

    // ========== 采分点解析边界 ==========

    @Test
    void submitShortAnswerKeywordsAcrossMultipleLines_StopsAtNewline() {
        // 【采分点】后面有换行 → 只取第一行
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("xxx");
        q.setExplanation("先解释一下\n【采分点】关键词1、关键词2\n\n这里是后续内容不应被解析");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        var answers = new HashMap<String, String>();
        answers.put("q1", "包含关键词1和关键词2的完整答案。");

        var result = examService.submit("e1", "u1", answers);
        assertEquals(1, result.get("score"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Object>) ((Map<String, Object>) result.get("answers")).get("q1");
        assertEquals(1.0, graded.get("hitRatio"));
    }

    @Test
    void submitShortAnswerEmptyExplanation_NoCrash() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("标准答案");
        q.setExplanation("");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        var answers = new HashMap<String, String>();
        answers.put("q1", "标准答案");

        assertEquals(1, examService.submit("e1", "u1", answers).get("score"));
    }

    @Test
    void submitShortAnswerCommaAndSpaceSeparators_AllParsed() {
        // 采分点用英文逗号、中文逗号、空格混合分隔
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("x");
        q.setExplanation("【采分点】封装,继承 多态，抽象");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        var answers = new HashMap<String, String>();
        answers.put("q1", "封装 继承 多态 抽象 四大特性");

        assertEquals(1, examService.submit("e1", "u1", answers).get("score"));
    }

    // ========== 空答案/缺答 ==========

    @Test
    void submitMissingAnswer_EmptyStringUsed() {
        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setAnswer("A");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(wqMapper).insert(any(WrongQuestion.class));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        var result = examService.submit("e1", "u1", new HashMap<>());
        assertEquals(0, result.get("score"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Object>) ((Map<String, Object>) result.get("answers")).get("q1");
        assertEquals("", graded.get("userAnswer"));
    }

    @Test
    void submitAnswerWithWhitespace_Trimmed() {
        Question q = new Question();
        q.setId("q1");
        q.setType("fill");
        q.setAnswer("Spring");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        var answers = new HashMap<String, String>();
        answers.put("q1", "  Spring  \n");

        assertEquals(1, examService.submit("e1", "u1", answers).get("score"));
    }

    // ========== 错题本去重（重复插入不抛异常） ==========

    @Test
    void submitWrongQuestionDuplicate_IgnoresInsertError() {
        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setAnswer("A");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doThrow(new RuntimeException("唯一键冲突")).when(wqMapper).insert(any(WrongQuestion.class));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        var answers = new HashMap<String, String>();
        answers.put("q1", "B");

        // 不应抛出异常，重复错题静默忽略
        var result = assertDoesNotThrow(() -> examService.submit("e1", "u1", answers));
        assertEquals(0, result.get("score"));
    }

    // ========== list ==========

    @Test
    void listReturnsUserExams() {
        Exam e1 = new Exam(); e1.setId("e1"); e1.setTitle("数学");
        Exam e2 = new Exam(); e2.setId("e2"); e2.setTitle("英语");
        when(examMapper.findByUserId("u1")).thenReturn(List.of(e1, e2));

        var list = examService.list("u1");
        assertEquals(2, list.size());
        assertEquals("数学", list.get(0).getTitle());
    }
}
