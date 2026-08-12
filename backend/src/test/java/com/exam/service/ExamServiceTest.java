package com.exam.service;

import com.exam.mapper.*;
import com.exam.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
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

    @BeforeEach
    void setUp() {
        examService = new ExamService(examMapper, questionMapper, docMapper,
            deepSeek, ragService, wqMapper, attemptMapper, taskQueue);
    }

    // ========== list / get / delete 权限校验 ==========

    @Test
    void listReturnsUserExams() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("u1");
        when(examMapper.findByUserId("u1")).thenReturn(List.of(exam));

        var result = examService.list("u1");

        assertEquals(1, result.size());
        assertEquals("e1", result.get(0).getId());
    }

    @Test
    void getWithUserIdThrowsWhenNotOwner() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("other");
        when(examMapper.findById("e1")).thenReturn(exam);

        var ex = assertThrows(RuntimeException.class,
            () -> examService.get("e1", "u1"));
        assertEquals("无权访问该考试", ex.getMessage());
    }

    @Test
    void getWithUserIdReturnsExamWhenOwner() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("u1");
        exam.setTitle("期中考试");
        exam.setQuestionTypes("choice,fill");
        exam.setQuestionCount(10);
        when(examMapper.findById("e1")).thenReturn(exam);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of());

        var result = examService.get("e1", "u1");

        assertEquals("e1", result.get("id"));
        assertEquals("期中考试", result.get("title"));
        assertEquals("choice,fill", result.get("questionTypes"));
        assertEquals(10, result.get("questionCount"));
    }

    @Test
    void getWithoutUserIdReturnsNullWhenNotFound() {
        when(examMapper.findById("nonexistent")).thenReturn(null);
        assertNull(examService.get("nonexistent"));
    }

    @Test
    void deleteThrowsWhenExamNotFound() {
        when(examMapper.findById("e1")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> examService.delete("e1", "u1"));
        assertEquals("考试不存在", ex.getMessage());
    }

    @Test
    void deleteThrowsWhenNotOwner() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("other");
        when(examMapper.findById("e1")).thenReturn(exam);

        var ex = assertThrows(RuntimeException.class,
            () -> examService.delete("e1", "u1"));
        assertEquals("无权删除该考试", ex.getMessage());
    }

    @Test
    void deleteSucceedsWhenOwner() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("u1");
        when(examMapper.findById("e1")).thenReturn(exam);
        doNothing().when(examMapper).deleteById("e1");

        examService.delete("e1", "u1");

        verify(examMapper).deleteById("e1");
    }

    // ========== submit - 精确匹配题型 ==========

    @Test
    void submitChoiceQuestionCorrectAnswer() {
        Question q = makeChoiceQuestion("q1", "B");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1", Map.of("q1", "B"));

        assertEquals(1, result.get("score"));
        assertEquals(1, result.get("total"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        var g = (Map<String, Object>) graded.get("q1");
        assertTrue((Boolean) g.get("correct"));
        assertEquals(1.0, g.get("hitRatio"));
        assertFalse((Boolean) g.get("isFuzzyMatch"));
    }

    @Test
    void submitChoiceQuestionWrongAnswer() {
        Question q = makeChoiceQuestion("q1", "B");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1", Map.of("q1", "A"));

        assertEquals(0, result.get("score"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        var g = (Map<String, Object>) graded.get("q1");
        assertFalse((Boolean) g.get("correct"));
        assertEquals(0.0, g.get("hitRatio"));
    }

    @Test
    void submitFillBlankExactMatch() {
        Question q = makeFillQuestion("q2", "Spring");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1", Map.of("q2", "Spring"));

        assertEquals(1, result.get("score"));
    }

    @Test
    void submitTrueFalseExactMatch() {
        Question q = makeTrueFalseQuestion("q3", "正确");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1", Map.of("q3", "正确"));

        assertEquals(1, result.get("score"));
    }

    @Test
    void submitTrueFalseWrongAnswer() {
        Question q = makeTrueFalseQuestion("q3", "错误");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1", Map.of("q3", "正确"));

        assertEquals(0, result.get("score"));
    }

    @Test
    void submitEmptyAnswerTreatedAsWrong() {
        Question q = makeChoiceQuestion("q1", "B");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1", Map.of("q1", "   "));

        assertEquals(0, result.get("score"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        var g = (Map<String, Object>) graded.get("q1");
        assertEquals("", g.get("userAnswer"));
    }

    @Test
    void submitMissingAnswerDefaultsToEmpty() {
        Question q = makeChoiceQuestion("q1", "B");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1", Map.of());

        assertEquals(0, result.get("score"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        var g = (Map<String, Object>) graded.get("q1");
        assertEquals("", g.get("userAnswer"));
    }

    // ========== submit - 简答题模糊匹配（核心风险逻辑） ==========

    @Test
    void submitShortAnswerAllKeywordsHit() {
        Question q = makeShortAnswerQuestion("q4", "面向对象的三大特性是封装、继承、多态",
            "解析内容。\n【采分点】封装、继承、多态");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1",
            Map.of("q4", "三大特性包括封装、继承和多态"));

        assertEquals(1, result.get("score"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        var g = (Map<String, Object>) graded.get("q4");
        assertTrue((Boolean) g.get("correct"));
        assertEquals(1.0, g.get("hitRatio"));
    }

    @Test
    void submitShortAnswerPartialHitAboveThreshold() {
        // 3个关键词命中2个 = 0.667 >= 0.6 阈值，判为正确
        Question q = makeShortAnswerQuestion("q4", "参考",
            "解析\n【采分点】封装、继承、多态");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1",
            Map.of("q4", "封装和继承是重要特性")); // 命中2/3

        assertEquals(1, result.get("score"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        var g = (Map<String, Object>) graded.get("q4");
        assertTrue((Boolean) g.get("correct"));
        assertTrue((Boolean) g.get("isFuzzyMatch"));
        // 0.67 rounded to 2 decimals
        assertEquals(0.67, g.get("hitRatio"));
    }

    @Test
    void submitShortAnswerPartialHitBelowThreshold() {
        // 4个关键词命中1个 = 0.25 < 0.6，判为错误
        Question q = makeShortAnswerQuestion("q4", "参考",
            "解析\n【采分点】封装、继承、多态、抽象");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1",
            Map.of("q4", "提到了封装，但其他不知道")); // 命中1/4

        assertEquals(0, result.get("score"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        var g = (Map<String, Object>) graded.get("q4");
        assertFalse((Boolean) g.get("correct"));
        assertEquals(0.25, g.get("hitRatio"));
    }

    @Test
    void submitShortAnswerAtThresholdBoundary() {
        // 5个关键词命中3个 = 0.6，恰好等于阈值，应判为正确
        Question q = makeShortAnswerQuestion("q4", "参考",
            "解析\n【采分点】A、B、C、D、E");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1",
            Map.of("q4", "答案包含A B C")); // 3/5 = 0.6

        assertEquals(1, result.get("score"));
    }

    @Test
    void submitShortAnswerNoKeywordMarkerFallsBackToExactMatch() {
        // 没有【采分点】标记时走精确匹配
        Question q = makeShortAnswerQuestion("q4", "Spring框架",
            "只是普通解析，没有采分点标记");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        // 精确匹配应正确
        var correct = examService.submit("e1", "u1", Map.of("q4", "Spring框架"));
        assertEquals(1, correct.get("score"));

        // 含更多内容的答案应判错（精确匹配模式）
        var wrong = examService.submit("e1", "u1", Map.of("q4", "Spring框架是Java框架"));
        assertEquals(0, wrong.get("score"));
    }

    @Test
    void submitShortAnswerKeywordsWithDifferentSeparators() {
        // 测试顿号、逗号、空格混合分隔
        Question q = makeShortAnswerQuestion("q4", "ref",
            "解析\n【采分点】Java,Spring，MyBatis Hibernate");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1",
            Map.of("q4", "Java Spring MyBatis"));

        assertEquals(1, result.get("score")); // 3/4 = 0.75 >= 0.6
    }

    @Test
    void submitShortAnswerEmptyExplanationFallsBackToExact() {
        Question q = makeShortAnswerQuestion("q4", "标准答案", "");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var r = examService.submit("e1", "u1", Map.of("q4", "标准答案"));
        assertEquals(1, r.get("score"));

        var r2 = examService.submit("e1", "u1", Map.of("q4", "不一样"));
        assertEquals(0, r2.get("score"));
    }

    @Test
    void submitShortAnswerKeywordsAfterNewlineIgnored() {
        // 【采分点】之后只有同行被解析
        Question q = makeShortAnswerQuestion("q4", "ref",
            "解析内容\n【采分点】关键词1、关键词2\n这里的内容不应被解析");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1",
            Map.of("q4", "包含关键词1和关键词2"));
        assertEquals(1, result.get("score"));
    }

    // ========== submit - 字符覆盖率模糊匹配（同义词） ==========

    @Test
    void submitKeywordMatchBySubstring() {
        // 子串包含直接命中
        Question q = makeShortAnswerQuestion("q4", "ref",
            "解析\n【采分点】数据隐藏、封装");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1",
            Map.of("q4", "数据隐藏是封装的目的之一"));
        assertEquals(1, result.get("score")); // 2/2
    }

    @Test
    void submitKeywordMatchByCharacterCoverage() {
        // 测试字符覆盖率匹配："数据隐藏" vs "信息隐藏" 共享"隐藏"
        // keyword="数据隐藏"(4字), coverage=2/4=0.5 -> 匹配
        Question q = makeShortAnswerQuestion("q4", "ref",
            "解析\n【采分点】数据隐藏");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1",
            Map.of("q4", "信息隐藏的概念")); // 覆盖"隐藏"2字
        assertEquals(1, result.get("score"));
    }

    @Test
    void submitCharacterCoverageInsufficientMisses() {
        // 覆盖率不够时应判为未命中
        Question q = makeShortAnswerQuestion("q4", "ref",
            "解析\n【采分点】依赖注入"); // 4字
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1",
            Map.of("q4", "设计模式")); // 没有共同字符
        assertEquals(0, result.get("score"));
    }

    // ========== submit - 错题本写入 ==========

    @Test
    void submitWrongQuestionWrittenToWrongQuestionBook() {
        Question q1 = makeChoiceQuestion("q1", "A");
        Question q2 = makeChoiceQuestion("q2", "B");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q1, q2));

        // q1答对，q2答错
        examService.submit("e1", "u1", Map.of("q1", "A", "q2", "C"));

        ArgumentCaptor<WrongQuestion> captor = ArgumentCaptor.forClass(WrongQuestion.class);
        verify(wqMapper, times(1)).insert(captor.capture());
        WrongQuestion wq = captor.getValue();
        assertEquals("u1_q2", wq.getId());
        assertEquals("u1", wq.getUserId());
        assertEquals("q2", wq.getQuestionId());
        assertEquals("e1", wq.getExamId());
        assertEquals("C", wq.getUserAnswer());
        assertFalse(wq.getMastered());
        assertEquals(0, wq.getReviewCount());
    }

    @Test
    void submitAllCorrectNoWrongQuestionInserted() {
        Question q1 = makeChoiceQuestion("q1", "A");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q1));

        examService.submit("e1", "u1", Map.of("q1", "A"));

        verify(wqMapper, never()).insert(any());
    }

    @Test
    void submitDuplicateWrongQuestionInsertIgnored() {
        // 重复插入异常应被静默忽略
        Question q1 = makeChoiceQuestion("q1", "A");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q1));
        doThrow(new RuntimeException("duplicate key")).when(wqMapper).insert(any());

        // 不应抛出异常
        assertDoesNotThrow(() -> examService.submit("e1", "u1", Map.of("q1", "B")));
    }

    // ========== submit - 考试记录持久化 ==========

    @Test
    void submitExamAttemptPersisted() {
        Question q1 = makeChoiceQuestion("q1", "A");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q1));

        var result = examService.submit("e1", "u1", Map.of("q1", "A"));

        ArgumentCaptor<ExamAttempt> captor = ArgumentCaptor.forClass(ExamAttempt.class);
        verify(attemptMapper).insert(captor.capture());
        ExamAttempt attempt = captor.getValue();
        assertEquals(result.get("id"), attempt.getId());
        assertEquals("e1", attempt.getExamId());
        assertEquals("u1", attempt.getUserId());
        assertEquals(1, attempt.getScore());
        assertEquals(1, attempt.getTotal());
        assertNotNull(attempt.getAnswers());
        assertTrue(attempt.getAnswers().contains("\"correct\":true"));
    }

    // ========== 出题任务状态查询 ==========

    @Test
    void getTaskStatusDelegatesToTaskQueue() {
        when(taskQueue.getStatus("exam:task:", "t1")).thenReturn("DONE:exam123");

        String status = examService.getTaskStatus("t1");

        assertEquals("DONE:exam123", status);
        verify(taskQueue).getStatus("exam:task:", "t1");
    }

    // ========== helpers ==========

    private Question makeChoiceQuestion(String id, String answer) {
        Question q = new Question();
        q.setId(id);
        q.setExamId("e1");
        q.setType("choice");
        q.setContent("题目" + id);
        q.setOptions("[\"A|选项A\",\"B|选项B\",\"C|选项C\",\"D|选项D\"]");
        q.setAnswer(answer);
        q.setExplanation("解析");
        return q;
    }

    private Question makeFillQuestion(String id, String answer) {
        Question q = new Question();
        q.setId(id);
        q.setType("fill");
        q.setContent("填空题" + id);
        q.setOptions("[]");
        q.setAnswer(answer);
        q.setExplanation("解析");
        return q;
    }

    private Question makeTrueFalseQuestion(String id, String answer) {
        Question q = new Question();
        q.setId(id);
        q.setType("truefalse");
        q.setContent("判断题" + id);
        q.setOptions("[]");
        q.setAnswer(answer);
        q.setExplanation("解析");
        return q;
    }

    private Question makeShortAnswerQuestion(String id, String answer, String explanation) {
        Question q = new Question();
        q.setId(id);
        q.setType("shortanswer");
        q.setContent("简答题" + id);
        q.setOptions("[]");
        q.setAnswer(answer);
        q.setExplanation(explanation);
        return q;
    }
}
