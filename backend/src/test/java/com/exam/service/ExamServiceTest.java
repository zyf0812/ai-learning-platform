package com.exam.service;

import com.exam.mapper.*;
import com.exam.model.Exam;
import com.exam.model.Question;
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

    @BeforeEach
    void setUp() {
        examService = new ExamService(
            examMapper, questionMapper, docMapper, deepSeek, ragService,
            wqMapper, attemptMapper, taskQueue
        );
    }

    // ===== list / get / delete 权限校验测试 =====

    @Test
    void listReturnsUserExams() {
        String userId = "user1";
        List<Exam> exams = List.of(new Exam());
        when(examMapper.findByUserId(userId)).thenReturn(exams);

        var result = examService.list(userId);

        assertSame(exams, result);
        verify(examMapper).findByUserId(userId);
    }

    @Test
    void getWithUserIdReturnsExamWithQuestions() {
        String examId = "e1", userId = "u1";
        Exam exam = new Exam();
        exam.setId(examId);
        exam.setUserId(userId);
        exam.setTitle("Test Exam");
        exam.setQuestionTypes("choice,fill");
        exam.setQuestionCount(2);

        List<Question> questions = List.of(new Question());
        when(examMapper.findById(examId)).thenReturn(exam);
        when(questionMapper.findByExamId(examId)).thenReturn(questions);

        var result = examService.get(examId, userId);

        assertNotNull(result);
        assertEquals(examId, result.get("id"));
        assertEquals("Test Exam", result.get("title"));
        assertEquals("choice,fill", result.get("questionTypes"));
        assertEquals(2, result.get("questionCount"));
        assertSame(questions, result.get("questions"));
    }

    @Test
    void getWithUserIdWrongOwnerThrowsForbidden() {
        String examId = "e1", ownerId = "u1", otherId = "u2";
        Exam exam = new Exam();
        exam.setId(examId);
        exam.setUserId(ownerId);
        when(examMapper.findById(examId)).thenReturn(exam);

        var ex = assertThrows(RuntimeException.class,
            () -> examService.get(examId, otherId));
        assertEquals("无权访问该考试", ex.getMessage());
    }

    @Test
    void getWithUserIdNullExamReturnsNull() {
        when(examMapper.findById("missing")).thenReturn(null);
        assertNull(examService.get("missing", "anyuser"));
    }

    @Test
    void getAdminReturnsExamWithoutOwnerCheck() {
        String examId = "e1";
        Exam exam = new Exam();
        exam.setId(examId);
        exam.setUserId("someone");
        when(examMapper.findById(examId)).thenReturn(exam);
        when(questionMapper.findByExamId(examId)).thenReturn(List.of());

        var result = examService.get(examId);
        assertNotNull(result);
        assertEquals(examId, result.get("id"));
    }

    @Test
    void deleteByOwnerSucceeds() {
        String examId = "e1", userId = "u1";
        Exam exam = new Exam();
        exam.setId(examId);
        exam.setUserId(userId);
        when(examMapper.findById(examId)).thenReturn(exam);

        examService.delete(examId, userId);

        verify(examMapper).deleteById(examId);
    }

    @Test
    void deleteByNonOwnerThrows() {
        String examId = "e1", ownerId = "u1", otherId = "u2";
        Exam exam = new Exam();
        exam.setId(examId);
        exam.setUserId(ownerId);
        when(examMapper.findById(examId)).thenReturn(exam);

        var ex = assertThrows(RuntimeException.class,
            () -> examService.delete(examId, otherId));
        assertEquals("无权删除该考试", ex.getMessage());
    }

    @Test
    void deleteMissingExamThrows() {
        when(examMapper.findById("missing")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class,
            () -> examService.delete("missing", "anyuser"));
        assertEquals("考试不存在", ex.getMessage());
    }

    // ===== submit 评分逻辑测试 =====

    private Question makeQuestion(String id, String type, String answer, String explanation) {
        Question q = new Question();
        q.setId(id);
        q.setType(type);
        q.setAnswer(answer);
        q.setExplanation(explanation);
        q.setContent("Sample question " + id);
        return q;
    }

    @Test
    void submitChoiceQuestionCorrectAnswerScoresPoint() {
        String examId = "e1", userId = "u1";
        Question q = makeQuestion("q1", "choice", "B", "");
        when(questionMapper.findByExamId(examId)).thenReturn(List.of(q));

        Map<String, String> answers = Map.of("q1", "B");
        var result = examService.submit(examId, userId, answers);

        assertEquals(1, result.get("score"));
        assertEquals(1, result.get("total"));
        @SuppressWarnings("unchecked")
        Map<String, Object> graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> gq1 = (Map<String, Object>) graded.get("q1");
        assertTrue((Boolean) gq1.get("correct"));
        assertEquals("B", gq1.get("correctAnswer"));
        assertEquals("B", gq1.get("userAnswer"));
        assertFalse((Boolean) gq1.get("isFuzzyMatch"));
        assertEquals(1.0, gq1.get("hitRatio"));
    }

    @Test
    void submitChoiceQuestionWrongAnswerNoScore() {
        String examId = "e1", userId = "u1";
        Question q = makeQuestion("q1", "choice", "A", "");
        when(questionMapper.findByExamId(examId)).thenReturn(List.of(q));
        doNothing().when(wqMapper).insert(any());
        doNothing().when(attemptMapper).insert(any());

        Map<String, String> answers = Map.of("q1", "C");
        var result = examService.submit(examId, userId, answers);

        assertEquals(0, result.get("score"));
        @SuppressWarnings("unchecked")
        Map<String, Object> graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> gq1 = (Map<String, Object>) graded.get("q1");
        assertFalse((Boolean) gq1.get("correct"));
        verify(wqMapper).insert(any());
    }

    @Test
    void submitFillBlankExactMatchCorrect() {
        String examId = "e1", userId = "u1";
        Question q = makeQuestion("q1", "fill", "Spring", "");
        when(questionMapper.findByExamId(examId)).thenReturn(List.of(q));

        Map<String, String> answers = Map.of("q1", "Spring");
        var result = examService.submit(examId, userId, answers);

        assertEquals(1, result.get("score"));
    }

    @Test
    void submitTrueFalseCorrect() {
        String examId = "e1", userId = "u1";
        Question q = makeQuestion("q1", "truefalse", "正确", "");
        when(questionMapper.findByExamId(examId)).thenReturn(List.of(q));

        Map<String, String> answers = Map.of("q1", "正确");
        var result = examService.submit(examId, userId, answers);
        assertEquals(1, result.get("score"));
    }

    @Test
    void submitTrueFalseWrong() {
        String examId = "e1", userId = "u1";
        Question q = makeQuestion("q1", "truefalse", "错误", "");
        when(questionMapper.findByExamId(examId)).thenReturn(List.of(q));
        doNothing().when(wqMapper).insert(any());

        Map<String, String> answers = Map.of("q1", "正确");
        var result = examService.submit(examId, userId, answers);
        assertEquals(0, result.get("score"));
    }

    // ===== 简答题：采分点模糊匹配测试 =====

    @Test
    void submitShortAnswerWithExactKeywordsMatch() {
        String examId = "e1", userId = "u1";
        String explanation = "面向对象三大特性。【采分点】封装、继承、多态";
        Question q = makeQuestion("q1", "shortanswer", "封装继承多态", explanation);
        when(questionMapper.findByExamId(examId)).thenReturn(List.of(q));

        Map<String, String> answers = Map.of("q1", "面向对象包括封装、继承和多态三个特性");
        var result = examService.submit(examId, userId, answers);

        assertEquals(1, result.get("score"));
        @SuppressWarnings("unchecked")
        Map<String, Object> graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> gq1 = (Map<String, Object>) graded.get("q1");
        assertTrue((Boolean) gq1.get("correct"));
        assertEquals(1.0, gq1.get("hitRatio"));
    }

    @Test
    void submitShortAnswerPartialKeywordsAboveThresholdCorrect() {
        // 3 个采分点命中 2 个 = 66.7% >= 60% 阈值 → 正确
        String examId = "e1", userId = "u1";
        String explanation = "【采分点】封装、继承、多态";
        Question q = makeQuestion("q1", "shortanswer", "参考", explanation);
        when(questionMapper.findByExamId(examId)).thenReturn(List.of(q));

        Map<String, String> answers = Map.of("q1", "主要有封装和继承两个重要特性");
        var result = examService.submit(examId, userId, answers);

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
    void submitShortAnswerPartialKeywordsBelowThresholdWrong() {
        // 4 个采分点命中 1 个 = 25% < 60% → 错误
        String examId = "e1", userId = "u1";
        String explanation = "【采分点】封装、继承、多态、抽象";
        Question q = makeQuestion("q1", "shortanswer", "参考", explanation);
        when(questionMapper.findByExamId(examId)).thenReturn(List.of(q));
        doNothing().when(wqMapper).insert(any());

        Map<String, String> answers = Map.of("q1", "封装是面向对象的重要概念");
        var result = examService.submit(examId, userId, answers);

        assertEquals(0, result.get("score"));
        @SuppressWarnings("unchecked")
        Map<String, Object> graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> gq1 = (Map<String, Object>) graded.get("q1");
        assertFalse((Boolean) gq1.get("correct"));
    }

    @Test
    void submitShortAnswerNoScorePointMarkerFallsBackToExactMatch() {
        // 无【采分点】标记 → 回退到精确匹配
        String examId = "e1", userId = "u1";
        Question q = makeQuestion("q1", "shortanswer", "Spring Framework", "没有采分点标记的普通解析");
        when(questionMapper.findByExamId(examId)).thenReturn(List.of(q));
        doNothing().when(wqMapper).insert(any());

        // 精确匹配成功
        var r1 = examService.submit(examId, userId, Map.of("q1", "Spring Framework"));
        assertEquals(1, r1.get("score"));

        // 近似但不完全匹配 → 失败
        var r2 = examService.submit(examId, userId, Map.of("q1", "Spring"));
        assertEquals(0, r2.get("score"));
    }

    @Test
    void submitShortAnswerKeywordMatchViaCharacterCoverage() {
        // 字符覆盖率测试：用户答案包含关键词大部分字符
        String examId = "e1", userId = "u1";
        String explanation = "【采分点】数据隐藏";
        Question q = makeQuestion("q1", "shortanswer", "参考", explanation);
        when(questionMapper.findByExamId(examId)).thenReturn(List.of(q));

        // "信息隐藏" 和 "数据隐藏" 共享 "隐藏" 两字，覆盖率 >= 0.5
        Map<String, String> answers = Map.of("q1", "这体现了信息隐藏的思想");
        var result = examService.submit(examId, userId, answers);

        assertEquals(1, result.get("score"));
    }

    @Test
    void submitShortAnswerEmptyOrBlankExplanationFallsBackExact() {
        String examId = "e1", userId = "u1";
        Question q = makeQuestion("q1", "shortanswer", "OK", null);
        Question q2 = makeQuestion("q2", "shortanswer", "OK2", "   ");
        when(questionMapper.findByExamId(examId)).thenReturn(List.of(q, q2));

        var result = examService.submit(examId, userId,
            Map.of("q1", "OK", "q2", "OK2"));
        assertEquals(2, result.get("score"));
    }

    // ===== 综合场景测试 =====

    @Test
    void submitMultipleQuestionsMixedCorrectness() {
        String examId = "e1", userId = "u1";
        Question q1 = makeQuestion("q1", "choice", "A", "");
        Question q2 = makeQuestion("q2", "fill", "Java", "");
        Question q3 = makeQuestion("q3", "choice", "C", "");
        when(questionMapper.findByExamId(examId)).thenReturn(List.of(q1, q2, q3));
        doNothing().when(wqMapper).insert(any());

        Map<String, String> answers = new HashMap<>();
        answers.put("q1", "A");   // 对
        answers.put("q2", "Java"); // 对
        answers.put("q3", "B");    // 错

        var result = examService.submit(examId, userId, answers);

        assertEquals(2, result.get("score"));
        assertEquals(3, result.get("total"));
        verify(wqMapper, times(1)).insert(any()); // 只写入1道错题
    }

    @Test
    void submitUnansweredQuestionTreatedAsEmptyString() {
        String examId = "e1", userId = "u1";
        Question q1 = makeQuestion("q1", "choice", "A", "");
        Question q2 = makeQuestion("q2", "fill", "answer", "");
        when(questionMapper.findByExamId(examId)).thenReturn(List.of(q1, q2));
        doNothing().when(wqMapper).insert(any());

        // 只回答 q1，q2 未回答（answers 中没有 q2）
        Map<String, String> answers = Map.of("q1", "A");
        var result = examService.submit(examId, userId, answers);

        assertEquals(1, result.get("score"));
        assertEquals(2, result.get("total"));
        @SuppressWarnings("unchecked")
        Map<String, Object> graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> gq2 = (Map<String, Object>) graded.get("q2");
        assertEquals("", gq2.get("userAnswer"));
        assertFalse((Boolean) gq2.get("correct"));
    }

    @Test
    void submitWhitespaceInAnswerIsTrimmed() {
        String examId = "e1", userId = "u1";
        Question q = makeQuestion("q1", "choice", "B", "");
        when(questionMapper.findByExamId(examId)).thenReturn(List.of(q));

        Map<String, String> answers = Map.of("q1", "  B  ");
        var result = examService.submit(examId, userId, answers);
        assertEquals(1, result.get("score"));
    }

    @Test
    void submitScoreKeywordsSplitByMultipleDelimiters() {
        // 逗号、空格、顿号都应被正确分隔
        String examId = "e1", userId = "u1";
        String explanation = "【采分点】封装, 继承，多态 抽象";
        Question q = makeQuestion("q1", "shortanswer", "参考", explanation);
        when(questionMapper.findByExamId(examId)).thenReturn(List.of(q));

        // 全部命中（4个）
        Map<String, String> answers = Map.of("q1", "封装继承多态抽象是OOP的四个特性");
        var result = examService.submit(examId, userId, answers);
        assertEquals(1, result.get("score"));
        @SuppressWarnings("unchecked")
        Map<String, Object> graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> gq1 = (Map<String, Object>) graded.get("q1");
        assertEquals(1.0, gq1.get("hitRatio"));
    }

    @Test
    void submitKeywordsOnlyTakenUntilNewline() {
        // 【采分点】后面有换行，只应解析到行尾
        String examId = "e1", userId = "u1";
        String explanation = "详细解析如下：\n【采分点】封装、继承\n其他补充说明：多态是额外内容";
        Question q = makeQuestion("q1", "shortanswer", "参考", explanation);
        when(questionMapper.findByExamId(examId)).thenReturn(List.of(q));
        doNothing().when(wqMapper).insert(any());

        // 只命中「封装」→ 50% < 60% → 错误（多态在采分点行之后，不应计入）
        Map<String, String> answers = Map.of("q1", "封装技术很重要");
        var result = examService.submit(examId, userId, answers);
        assertEquals(0, result.get("score"));
    }

    @Test
    void getTaskStatusDelegatesToTaskQueue() {
        when(taskQueue.getStatus("exam:task:", "task123")).thenReturn("DONE:ok");
        assertEquals("DONE:ok", examService.getTaskStatus("task123"));
    }
}
