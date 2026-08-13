package com.exam.service;

import com.exam.mapper.*;
import com.exam.model.Question;
import com.exam.model.WrongQuestion;
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
class ExamServiceSubmitTest {

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

    // ========== submit(): 选择题/填空题/判断题 精确匹配 ==========

    @Test
    void submitAllCorrectFullScore() {
        Question q1 = mkQuestion("q1", "choice", "B");
        Question q2 = mkQuestion("q2", "fill", "Spring");
        Question q3 = mkQuestion("q3", "truefalse", "正确");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q1, q2, q3));

        Map<String, String> answers = new HashMap<>();
        answers.put("q1", "B");
        answers.put("q2", "Spring");
        answers.put("q3", "正确");

        var result = examService.submit("e1", "u1", answers);

        assertEquals(3, result.get("score"));
        assertEquals(3, result.get("total"));
    }

    @Test
    void submitChoiceWrongAnswerIsCaseSensitive() {
        Question q = mkQuestion("q1", "choice", "B");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1", Map.of("q1", "b")); // 小写

        assertEquals(0, result.get("score"));
    }

    @Test
    void submitMissingAnswerTreatedAsEmpty() {
        Question q1 = mkQuestion("q1", "fill", "Hello");
        Question q2 = mkQuestion("q2", "fill", "World");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q1, q2));

        // 只回答 q2，q1 缺失
        var result = examService.submit("e1", "u1", Map.of("q2", "World"));

        assertEquals(1, result.get("score"));
        @SuppressWarnings("unchecked")
        var answers = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        var q1Graded = (Map<String, Object>) answers.get("q1");
        assertEquals("", q1Graded.get("userAnswer"));
        assertEquals(false, q1Graded.get("correct"));
    }

    @Test
    void submitWhitespaceInAnswerTrimmed() {
        Question q = mkQuestion("q1", "fill", "Java");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        // 用户答案两端有空白
        var result = examService.submit("e1", "u1", Map.of("q1", "  Java  "));

        assertEquals(1, result.get("score"));
    }

    // ========== submit(): 简答题 精确匹配（无采分点时） ==========

    @Test
    void submitShortAnswerExactMatchWhenNoKeywords() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("Spring 框架");
        q.setExplanation("这是一个解析，没有采分点标记"); // 无【采分点】

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        // 完全匹配 → 正确
        var result = examService.submit("e1", "u1", Map.of("q1", "Spring 框架"));
        assertEquals(1, result.get("score"));
    }

    @Test
    void submitShortAnswerNoKeywordsMismatchIsWrong() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("Spring IoC 容器");
        q.setExplanation("没有采分点");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1", Map.of("q1", "完全不相关"));
        assertEquals(0, result.get("score"));
    }

    // ========== submit(): 简答题 模糊匹配（有采分点时） ==========

    @Test
    void submitShortAnswerAllKeywordsHit() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("封装继承多态");
        q.setExplanation("面向对象三大特征。\n【采分点】封装、继承、多态");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1",
            Map.of("q1", "面向对象有封装、继承和多态三个特征。"));

        assertEquals(1, result.get("score"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Object>) ((Map<?, ?>) result.get("answers")).get("q1");
        assertEquals(true, graded.get("correct"));
        assertEquals(1.0, graded.get("hitRatio"));
    }

    @Test
    void submitShortAnswerEnoughKeywordsHitPassesAt60Percent() {
        // 5 个关键词，命中 3 个 = 60% → 刚好通过阈值
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("OOP features");
        q.setExplanation("解析说明\n【采分点】封装、继承、多态、抽象、类");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        // 命中 3 个：封装、继承、多态（3/5 = 60% → 刚好达到阈值）
        var result = examService.submit("e1", "u1",
            Map.of("q1", "Java有封装、继承和多态的概念。"));

        assertEquals(1, result.get("score"));
    }

    @Test
    void submitShortAnswerBelowThresholdIsWrong() {
        // 5 个关键词，仅命中 2 个 = 40% → < 60% 判错
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("答案内容");
        q.setExplanation("说明\n【采分点】封装、继承、多态、抽象、类");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1",
            Map.of("q1", "主要是封装和抽象两个概念。")); // 2/5 = 40%

        assertEquals(0, result.get("score"));
    }

    @Test
    void submitShortAnswerPartialHitMarkedFuzzy() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("答案");
        q.setExplanation("解析\n【采分点】封装、继承、多态");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        // 2/3 = 66.7% → 通过，但不是 100% → isFuzzy=true
        var result = examService.submit("e1", "u1",
            Map.of("q1", "OOP 包括封装和继承。"));

        assertEquals(1, result.get("score"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Object>) ((Map<?, ?>) result.get("answers")).get("q1");
        assertEquals(true, graded.get("isFuzzyMatch"));
        assertTrue((double) graded.get("hitRatio") < 1.0);
    }

    @Test
    void submitShortAnswerKeywordsSeparatedByCommaSpace() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("答案");
        q.setExplanation("解析\n【采分点】封装,继承 多态，抽象");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        // 4 个关键词全命中：封装、继承、多态、抽象
        var result = examService.submit("e1", "u1",
            Map.of("q1", "四个概念：封装 继承 多态 抽象"));

        assertEquals(1, result.get("score"));
    }

    @Test
    void submitShortAnswerKeywordsAfterNewlineIgnored() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("答案");
        q.setExplanation("解析\n【采分点】封装、继承\n这一行不应该被解析");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        // 只应有 2 个关键词，全部命中
        var result = examService.submit("e1", "u1",
            Map.of("q1", "有封装和继承两个概念。"));

        assertEquals(1, result.get("score"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Object>) ((Map<?, ?>) result.get("answers")).get("q1");
        assertEquals(1.0, graded.get("hitRatio"));
    }

    @Test
    void submitShortAnswerKeywordFuzzyCharCoverage() {
        // 字符覆盖率匹配："数据隐藏" vs "信息隐藏" → 共享"隐藏" 2/4 = 50%
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("答案");
        q.setExplanation("解析\n【采分点】封装、多态、数据隐藏");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        // 用户写的是"信息隐藏"而非"数据隐藏"，通过字符覆盖率匹配
        var result = examService.submit("e1", "u1",
            Map.of("q1", "OOP的核心概念：封装、多态、信息隐藏"));

        // 3 个关键词：封装命中(子串)、多态命中(子串)、数据隐藏命中(覆盖>=50%)
        // hitRatio = 3/3 = 1.0
        assertEquals(1, result.get("score"));
    }

    @Test
    void submitShortAnswerExplanationNullOrBlankFallsBackToExact() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("Spring");
        q.setExplanation(null); // null → 无采分点 → 精确匹配

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        // 精确匹配 Spring → 正确
        var r1 = examService.submit("e1", "u1", Map.of("q1", "Spring"));
        assertEquals(1, r1.get("score"));

        // 不匹配 → 错误
        var r2 = examService.submit("e1", "u2", Map.of("q1", "其他答案"));
        assertEquals(0, r2.get("score"));
    }

    // ========== submit(): 错题追踪 ==========

    @Test
    void submitWrongAnswerSavedToWrongQuestionBook() {
        Question q = mkQuestion("q1", "choice", "A");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        examService.submit("e1", "u1", Map.of("q1", "B")); // 错误

        verify(wqMapper).insert(any(WrongQuestion.class));
    }

    @Test
    void submitCorrectAnswerNotSavedToWrongBook() {
        Question q = mkQuestion("q1", "choice", "A");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        examService.submit("e1", "u1", Map.of("q1", "A")); // 正确

        verify(wqMapper, never()).insert(any(WrongQuestion.class));
    }

    @Test
    void submitDuplicateWrongQuestionInsertExceptionIgnored() {
        Question q = mkQuestion("q1", "choice", "A");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        // 重复插入抛出异常（如唯一约束冲突）→ 应被吞掉
        doThrow(new RuntimeException("duplicate key")).when(wqMapper).insert(any());

        assertDoesNotThrow(() ->
            examService.submit("e1", "u1", Map.of("q1", "B")));
    }

    // ========== submit(): 答题记录持久化 ==========

    @Test
    void submitAttemptPersisted() {
        Question q = mkQuestion("q1", "choice", "A");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1", Map.of("q1", "A"));

        verify(attemptMapper).insert(any());
        assertNotNull(result.get("id")); // attemptId
    }

    @Test
    void submitAttemptInsertExceptionDoesNotFail() {
        Question q = mkQuestion("q1", "choice", "A");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doThrow(new RuntimeException("DB down")).when(attemptMapper).insert(any());

        assertDoesNotThrow(() ->
            examService.submit("e1", "u1", Map.of("q1", "A")));
    }

    // ========== get(): 权限校验 ==========

    @Test
    void getWithUserIdMismatchThrows() {
        com.exam.model.Exam exam = new com.exam.model.Exam();
        exam.setId("e1");
        exam.setUserId("owner");

        when(examMapper.findById("e1")).thenReturn(exam);

        var ex = assertThrows(RuntimeException.class,
            () -> examService.get("e1", "otheruser"));
        assertTrue(ex.getMessage().contains("无权"));
    }

    @Test
    void getWithCorrectUserIdReturnsExam() {
        com.exam.model.Exam exam = new com.exam.model.Exam();
        exam.setId("e1");
        exam.setUserId("owner");
        exam.setTitle("Test");

        when(examMapper.findById("e1")).thenReturn(exam);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of());

        var result = examService.get("e1", "owner");

        assertEquals("e1", result.get("id"));
        assertEquals("Test", result.get("title"));
    }

    @Test
    void getNotFoundReturnsNull() {
        when(examMapper.findById("nope")).thenReturn(null);

        assertNull(examService.get("nope", "u1"));
    }

    // ========== delete(): 权限校验 ==========

    @Test
    void deleteWrongUserThrows() {
        com.exam.model.Exam exam = new com.exam.model.Exam();
        exam.setId("e1");
        exam.setUserId("owner");

        when(examMapper.findById("e1")).thenReturn(exam);

        var ex = assertThrows(RuntimeException.class,
            () -> examService.delete("e1", "other"));
        assertTrue(ex.getMessage().contains("无权"));
    }

    @Test
    void deleteNonexistentThrows() {
        when(examMapper.findById("nope")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> examService.delete("nope", "u1"));
        assertEquals("考试不存在", ex.getMessage());
    }

    @Test
    void deleteOwnerCanDelete() {
        com.exam.model.Exam exam = new com.exam.model.Exam();
        exam.setId("e1");
        exam.setUserId("owner");

        when(examMapper.findById("e1")).thenReturn(exam);
        doNothing().when(examMapper).deleteById("e1");

        examService.delete("e1", "owner");

        verify(examMapper).deleteById("e1");
    }

    // ========== helper ==========

    private Question mkQuestion(String id, String type, String answer) {
        Question q = new Question();
        q.setId(id);
        q.setType(type);
        q.setAnswer(answer);
        q.setExplanation("");
        return q;
    }
}
