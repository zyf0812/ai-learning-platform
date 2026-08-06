package com.exam.service;

import com.exam.mapper.*;
import com.exam.model.Exam;
import com.exam.model.Question;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    @BeforeEach
    void setUp() {
        examService = new ExamService(examMapper, questionMapper, docMapper,
            deepSeek, ragService, wqMapper, attemptMapper, taskQueue);
    }

    // --- 权限校验测试 ---

    @Test
    void getWithWrongUserThrowsAccessDenied() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("owner");
        exam.setTitle("Test");
        when(examMapper.findById("e1")).thenReturn(exam);

        var ex = assertThrows(RuntimeException.class,
            () -> examService.get("e1", "hacker"));
        assertEquals("无权访问该考试", ex.getMessage());
    }

    @Test
    void getWithCorrectUserReturnsData() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("userA");
        exam.setTitle("Test");
        exam.setQuestionTypes("choice,fill");
        exam.setQuestionCount(2);
        when(examMapper.findById("e1")).thenReturn(exam);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of());

        Map<String, Object> result = examService.get("e1", "userA");
        assertNotNull(result);
        assertEquals("e1", result.get("id"));
        assertEquals("Test", result.get("title"));
        assertEquals("userA", result.get("userId"));
    }

    @Test
    void getWithNullExamReturnsNull() {
        when(examMapper.findById("nonexistent")).thenReturn(null);
        assertNull(examService.get("nonexistent", "anyuser"));
    }

    @Test
    void deleteNonExistentExamThrows() {
        when(examMapper.findById("e99")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class,
            () -> examService.delete("e99", "userA"));
        assertEquals("考试不存在", ex.getMessage());
    }

    @Test
    void deleteWithWrongUserThrowsAccessDenied() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("owner");
        when(examMapper.findById("e1")).thenReturn(exam);

        var ex = assertThrows(RuntimeException.class,
            () -> examService.delete("e1", "hacker"));
        assertEquals("无权删除该考试", ex.getMessage());
    }

    @Test
    void deleteWithCorrectUserCallsMapper() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("userA");
        when(examMapper.findById("e1")).thenReturn(exam);

        examService.delete("e1", "userA");
        verify(examMapper).deleteById("e1");
    }

    // --- 列表查询测试 ---

    @Test
    void listReturnsUserExams() {
        List<Exam> exams = List.of(new Exam(), new Exam());
        when(examMapper.findByUserId("u1")).thenReturn(exams);
        assertEquals(2, examService.list("u1").size());
    }

    // --- 考试提交 / 判分算法测试 ---

    @Test
    void submitChoiceQuestionCorrectAnswer() {
        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setAnswer("B");
        q.setExplanation("解析");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, String> answers = new HashMap<>();
        answers.put("q1", "B");

        Map<String, Object> result = examService.submit("e1", "u1", answers);

        assertEquals(1, result.get("score"));
        assertEquals(1, result.get("total"));
        @SuppressWarnings("unchecked")
        Map<String, Object> graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> gq1 = (Map<String, Object>) graded.get("q1");
        assertTrue((Boolean) gq1.get("correct"));
        assertEquals(1.0, gq1.get("hitRatio"));
        assertFalse((Boolean) gq1.get("isFuzzyMatch"));
    }

    @Test
    void submitChoiceQuestionWrongAnswer() {
        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setAnswer("A");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        when(wqMapper.insert(any())).thenReturn(1);

        Map<String, String> answers = Map.of("q1", "C");
        Map<String, Object> result = examService.submit("e1", "u1", answers);

        assertEquals(0, result.get("score"));
        @SuppressWarnings("unchecked")
        Map<String, Object> graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> gq1 = (Map<String, Object>) graded.get("q1");
        assertFalse((Boolean) gq1.get("correct"));
        verify(wqMapper).insert(any());
    }

    @Test
    void submitFillBlankQuestionExactMatch() {
        Question q = new Question();
        q.setId("q1");
        q.setType("fill");
        q.setAnswer("Spring");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, String> answers = Map.of("q1", "Spring");
        Map<String, Object> result = examService.submit("e1", "u1", answers);

        assertEquals(1, result.get("score"));
    }

    @Test
    void submitTrueFalseQuestionExactMatch() {
        Question qCorrect = new Question();
        qCorrect.setId("q1");
        qCorrect.setType("truefalse");
        qCorrect.setAnswer("正确");

        Question qWrong = new Question();
        qWrong.setId("q2");
        qWrong.setType("truefalse");
        qWrong.setAnswer("错误");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(qCorrect, qWrong));

        Map<String, String> answers = new HashMap<>();
        answers.put("q1", "正确");
        answers.put("q2", "正确");

        Map<String, Object> result = examService.submit("e1", "u1", answers);
        assertEquals(1, result.get("score"));
    }

    @Test
    void submitMissingAnswerCountsAsWrong() {
        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setAnswer("A");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, String> answers = new HashMap<>(); // 空答案
        Map<String, Object> result = examService.submit("e1", "u1", answers);

        assertEquals(0, result.get("score"));
        @SuppressWarnings("unchecked")
        Map<String, Object> graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> gq1 = (Map<String, Object>) graded.get("q1");
        assertEquals("", gq1.get("userAnswer"));
        assertFalse((Boolean) gq1.get("correct"));
    }

    @Test
    void submitAnswerIsTrimmedBeforeCompare() {
        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setAnswer("B");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        // 用户答案带有前后空格
        Map<String, String> answers = Map.of("q1", "  B  ");
        Map<String, Object> result = examService.submit("e1", "u1", answers);

        assertEquals(1, result.get("score"));
    }

    // --- 简答题模糊匹配测试 ---

    @Test
    void submitShortAnswerWithKeywordsHitsAll() throws Exception {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("面向对象的三大特性是封装、继承、多态");
        q.setExplanation("面向对象三大特性的定义。【采分点】封装、继承、多态");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        // 用户答案完整覆盖
        Map<String, String> answers = Map.of("q1", "封装、继承和多态是面向对象的三大特性");
        Map<String, Object> result = examService.submit("e1", "u1", answers);

        assertEquals(1, result.get("score"));
        @SuppressWarnings("unchecked")
        Map<String, Object> graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> gq1 = (Map<String, Object>) graded.get("q1");
        assertTrue((Boolean) gq1.get("correct"));
        assertEquals(1.0, gq1.get("hitRatio"));
    }

    @Test
    void submitShortAnswerPartialHitAboveThreshold() throws Exception {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("面向对象的三大特性");
        q.setExplanation("解析内容。【采分点】封装、继承、多态");
        // 3个采分点，命中2个 = 66.7% >= 60% 阈值，应判对

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, String> answers = Map.of("q1", "面向对象有封装和继承两个特性");
        Map<String, Object> result = examService.submit("e1", "u1", answers);

        assertEquals(1, result.get("score"));
        @SuppressWarnings("unchecked")
        Map<String, Object> graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> gq1 = (Map<String, Object>) graded.get("q1");
        assertTrue((Boolean) gq1.get("correct"));
        assertTrue((Boolean) gq1.get("isFuzzyMatch"));
        double ratio = (Double) gq1.get("hitRatio");
        assertTrue(ratio >= 0.6 && ratio < 1.0);
    }

    @Test
    void submitShortAnswerPartialHitBelowThreshold() throws Exception {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("答案");
        q.setExplanation("解析。【采分点】封装、继承、多态");
        // 3个采分点只命中1个 = 33.3% < 60%，应判错

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        when(wqMapper.insert(any())).thenReturn(1);

        Map<String, String> answers = Map.of("q1", "只有封装这个概念");
        Map<String, Object> result = examService.submit("e1", "u1", answers);

        assertEquals(0, result.get("score"));
        @SuppressWarnings("unchecked")
        Map<String, Object> graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> gq1 = (Map<String, Object>) graded.get("q1");
        assertFalse((Boolean) gq1.get("correct"));
    }

    @Test
    void submitShortAnswerNoKeywordsFallsBackToExactMatch() throws Exception {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("Spring Boot");
        q.setExplanation("普通解析，没有采分点标记");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        // 精确匹配成功
        Map<String, String> a1 = Map.of("q1", "Spring Boot");
        assertEquals(1, examService.submit("e1", "u1", a1).get("score"));

        // 不匹配失败
        when(wqMapper.insert(any())).thenReturn(1);
        Map<String, String> a2 = Map.of("q1", "Spring MVC");
        assertEquals(0, examService.submit("e1", "u2", a2).get("score"));
    }

    @Test
    void submitShortAnswerEmptyExplanationTreatedAsNoKeywords() throws Exception {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("42");
        q.setExplanation(""); // 空解析

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        assertEquals(1, examService.submit("e1", "u1", Map.of("q1", "42")).get("score"));
        assertEquals(0, examService.submit("e1", "u1", Map.of("q1", "41")).get("score"));
    }

    // --- 采分点解析 (parseKeywords) 反射测试 ---

    @Test
    void parseKeywordsExtractsAfterMarker() throws Exception {
        Method m = ExamService.class.getDeclaredMethod("parseKeywords", String.class);
        m.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) m.invoke(examService,
            "一些解析内容。【采分点】封装、继承、多态");
        assertEquals(List.of("封装", "继承", "多态"), result);
    }

    @Test
    void parseKeywordsWithCommaAndSpaceDelimiters() throws Exception {
        Method m = ExamService.class.getDeclaredMethod("parseKeywords", String.class);
        m.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) m.invoke(examService,
            "解析。【采分点】封装, 继承，多态 抽象");
        assertEquals(List.of("封装", "继承", "多态", "抽象"), result);
    }

    @Test
    void parseKeywordsWithoutMarkerReturnsEmpty() throws Exception {
        Method m = ExamService.class.getDeclaredMethod("parseKeywords", String.class);
        m.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> r1 = (List<String>) m.invoke(examService, "普通解析，没有采分点");
        assertTrue(r1.isEmpty());

        @SuppressWarnings("unchecked")
        List<String> r2 = (List<String>) m.invoke(examService, "");
        assertTrue(r2.isEmpty());

        @SuppressWarnings("unchecked")
        List<String> r3 = (List<String>) m.invoke(examService, null);
        assertTrue(r3.isEmpty());
    }

    @Test
    void parseKeywordsStopsAtNewline() throws Exception {
        Method m = ExamService.class.getDeclaredMethod("parseKeywords", String.class);
        m.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) m.invoke(examService,
            "解析。【采分点】A、B、C\n接下来是其他内容，不应该被解析");
        assertEquals(List.of("A", "B", "C"), result);
    }

    // --- 关键词匹配 (keywordMatch) 反射测试 ---

    @Test
    void keywordMatchSubstringContainment() throws Exception {
        Method m = ExamService.class.getDeclaredMethod("keywordMatch", String.class, String.class);
        m.setAccessible(true);

        assertTrue((Boolean) m.invoke(examService, "java封装特性详解", "封装"));
        assertTrue((Boolean) m.invoke(examService, "the encapsulation concept", "encapsulation"));
    }

    @Test
    void keywordMatchCharCoverageForSimilarTerms() throws Exception {
        Method m = ExamService.class.getDeclaredMethod("keywordMatch", String.class, String.class);
        m.setAccessible(true);

        // 数据隐藏 vs 信息隐藏：共享"隐藏"2字（短关键词<7字），覆盖率>=0.5 → 匹配
        assertTrue((Boolean) m.invoke(examService, "信息隐藏是封装的目的", "隐藏"));
    }

    @Test
    void keywordMatchNoMatch() throws Exception {
        Method m = ExamService.class.getDeclaredMethod("keywordMatch", String.class, String.class);
        m.setAccessible(true);

        assertFalse((Boolean) m.invoke(examService, "完全不相关的内容", "封装"));
    }

    // --- 出题状态查询 ---

    @Test
    void getTaskStatusDelegatesToTaskQueue() {
        when(taskQueue.getStatus("exam:task:", "task123")).thenReturn("COMPLETED");
        assertEquals("COMPLETED", examService.getTaskStatus("task123"));
        verify(taskQueue).getStatus("exam:task:", "task123");
    }

    // --- 混合题型综合测试 ---

    @Test
    void submitMixedQuestionTypesCalculatesTotalScore() {
        List<Question> questions = new ArrayList<>();

        Question q1 = new Question(); q1.setId("q1"); q1.setType("choice"); q1.setAnswer("A");
        Question q2 = new Question(); q2.setId("q2"); q2.setType("fill"); q2.setAnswer("容器");
        Question q3 = new Question(); q3.setId("q3"); q3.setType("truefalse"); q3.setAnswer("错误");
        Question q4 = new Question(); q4.setId("q4"); q4.setType("shortanswer");
        q4.setAnswer("标准答案"); q4.setExplanation("解析。【采分点】A、B、C、D、E");

        questions.add(q1); questions.add(q2); questions.add(q3); questions.add(q4);
        when(questionMapper.findByExamId("e1")).thenReturn(questions);

        Map<String, String> answers = new HashMap<>();
        answers.put("q1", "A");       // 对
        answers.put("q2", "容器");     // 对
        answers.put("q3", "正确");     // 错
        answers.put("q4", "包含A、B、C、D四个要点"); // 4/5 = 80% → 对

        Map<String, Object> result = examService.submit("e1", "u1", answers);
        assertEquals(3, result.get("score")); // q1 + q2 + q4 = 3
        assertEquals(4, result.get("total"));
    }
}
