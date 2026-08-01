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
        examService = new ExamService(examMapper, questionMapper, docMapper,
            deepSeek, ragService, wqMapper, attemptMapper, taskQueue);
    }

    // ---------- 权限校验 ----------

    @Test
    void getWithWrongUserThrowsAccessDenied() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("owner");
        exam.setTitle("Test");
        exam.setQuestionTypes("choice");
        exam.setQuestionCount(1);

        when(examMapper.findById("e1")).thenReturn(exam);

        var ex = assertThrows(RuntimeException.class,
            () -> examService.get("e1", "other-user"));
        assertEquals("无权访问该考试", ex.getMessage());
    }

    @Test
    void getWithOwnerReturnsExamData() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("u1");
        exam.setTitle("Math");
        exam.setQuestionTypes("choice,fill");
        exam.setQuestionCount(2);

        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setContent("1+1?");
        q.setOptions("[\"A|1\",\"B|2\",\"C|3\",\"D|4\"]");
        q.setAnswer("B");

        when(examMapper.findById("e1")).thenReturn(exam);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, Object> result = examService.get("e1", "u1");
        assertNotNull(result);
        assertEquals("Math", result.get("title"));
        assertEquals(2, result.get("questionCount"));
        assertEquals("u1", result.get("userId"));
        @SuppressWarnings("unchecked")
        List<Question> questions = (List<Question>) result.get("questions");
        assertEquals(1, questions.size());
        assertEquals("q1", questions.get(0).getId());
    }

    @Test
    void getMissingExamReturnsNull() {
        when(examMapper.findById("nonexistent")).thenReturn(null);
        assertNull(examService.get("nonexistent", "u1"));
    }

    @Test
    void deleteWithWrongUserThrows() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("owner");

        when(examMapper.findById("e1")).thenReturn(exam);

        var ex = assertThrows(RuntimeException.class,
            () -> examService.delete("e1", "intruder"));
        assertEquals("无权删除该考试", ex.getMessage());
        verify(examMapper, never()).deleteById(anyString());
    }

    @Test
    void deleteNonexistentExamThrows() {
        when(examMapper.findById("missing")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class,
            () -> examService.delete("missing", "u1"));
        assertEquals("考试不存在", ex.getMessage());
    }

    @Test
    void deleteByOwnerSucceeds() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("u1");

        when(examMapper.findById("e1")).thenReturn(exam);
        doNothing().when(examMapper).deleteById("e1");

        examService.delete("e1", "u1");
        verify(examMapper).deleteById("e1");
    }

    // ---------- list ----------

    @Test
    void listReturnsUserExams() {
        Exam e1 = new Exam();
        e1.setId("e1");
        Exam e2 = new Exam();
        e2.setId("e2");

        when(examMapper.findByUserId("u1")).thenReturn(List.of(e1, e2));

        List<Exam> result = examService.list("u1");
        assertEquals(2, result.size());
        assertEquals("e1", result.get(0).getId());
    }

    // ---------- submit: 选择题精确匹配 ----------

    @Test
    void submitChoiceCorrectGivesScore() {
        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setAnswer("B");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        Map<String, String> answers = Map.of("q1", "B");
        Map<String, Object> result = examService.submit("e1", "u1", answers);

        assertEquals(1, result.get("score"));
        assertEquals(1, result.get("total"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertTrue((Boolean) graded.get("q1").get("correct"));
        assertEquals(1.0, graded.get("q1").get("hitRatio"));
        assertFalse((Boolean) graded.get("q1").get("isFuzzyMatch"));
    }

    @Test
    void submitChoiceWrongNoScoreAndWritesWrongBook() {
        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setAnswer("B");
        q.setExamId("e1");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(wqMapper).insert(any(WrongQuestion.class));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        Map<String, String> answers = Map.of("q1", "A");
        Map<String, Object> result = examService.submit("e1", "u1", answers);

        assertEquals(0, result.get("score"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertFalse((Boolean) graded.get("q1").get("correct"));
        assertEquals("A", graded.get("q1").get("userAnswer"));

        verify(wqMapper).insert(any(WrongQuestion.class));
    }

    // ---------- submit: 填空题/判断题精确匹配 ----------

    @Test
    void submitFillBlankExactMatch() {
        Question q = new Question();
        q.setId("q1");
        q.setType("fill");
        q.setAnswer("Spring");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        Map<String, Object> result = examService.submit("e1", "u1", Map.of("q1", "Spring"));
        assertEquals(1, result.get("score"));
    }

    @Test
    void submitFillBlankCaseSensitiveMismatch() {
        Question q = new Question();
        q.setId("q1");
        q.setType("fill");
        q.setAnswer("Spring");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(wqMapper).insert(any(WrongQuestion.class));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        Map<String, Object> result = examService.submit("e1", "u1", Map.of("q1", "spring"));
        assertEquals(0, result.get("score"));
    }

    @Test
    void submitTrueFalseCorrectAnswer() {
        Question q = new Question();
        q.setId("q1");
        q.setType("truefalse");
        q.setAnswer("正确");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        assertEquals(1, examService.submit("e1", "u1", Map.of("q1", "正确")).get("score"));
        assertEquals(0, examService.submit("e1", "u1", Map.of("q1", "错误")).get("score"));
    }

    // ---------- submit: 简答题采分点模糊匹配 ----------

    @Test
    void submitShortAnswerWithKeywordsFuzzyPass() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("面向对象三大特性是封装、继承、多态");
        q.setExplanation("考察面向对象基础。【采分点】封装、继承、多态");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        // 命中 3/3 = 100% -> 正确
        Map<String, Object> r1 = examService.submit("e1", "u1",
            Map.of("q1", "面向对象的三大特性是封装、继承和多态"));
        assertEquals(1, r1.get("score"));
    }

    @Test
    void submitShortAnswerPartialHitAbove60PercentPasses() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("Java的特点包括封装、继承、多态、跨平台");
        q.setExplanation("【采分点】封装、继承、多态、跨平台");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        // 命中 3/4 = 75% >= 60% 阈值 -> 正确，且标记模糊匹配
        Map<String, String> answers = Map.of("q1", "Java的特点是封装、继承、多态");
        Map<String, Object> result = examService.submit("e1", "u1", answers);

        assertEquals(1, result.get("score"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertTrue((Boolean) graded.get("q1").get("isFuzzyMatch"));
        double ratio = (Double) graded.get("q1").get("hitRatio");
        assertTrue(ratio >= 0.6 && ratio < 1.0, "hitRatio should be between 0.6 and 1.0 for fuzzy pass");
    }

    @Test
    void submitShortAnswerPartialHitBelow60PercentFails() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("Java有8种基本数据类型");
        q.setExplanation("【采分点】8种、基本数据类型、int占4字节、byte、char");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(wqMapper).insert(any(WrongQuestion.class));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        // 命中 1/5 = 20% < 60% -> 错误
        Map<String, String> answers = Map.of("q1", "Java数据类型丰富");
        Map<String, Object> result = examService.submit("e1", "u1", answers);
        assertEquals(0, result.get("score"));
    }

    @Test
    void submitShortAnswerExactlyAtThreshold60PercentPasses() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("OOP concepts");
        q.setExplanation("【采分点】封装、继承、多态、继承性、多态性");
        // 注意：有5个关键词，命中3个正好=60%

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        // 3/5 = 60.0% 正好阈值
        Map<String, String> answers = Map.of("q1", "封装特性继承特性多态");
        Map<String, Object> result = examService.submit("e1", "u1", answers);
        assertEquals(1, result.get("score"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> graded = (Map<String, Map<String, Object>>) result.get("answers");
        double ratio = (Double) graded.get("q1").get("hitRatio");
        assertEquals(0.6, ratio, 0.001);
    }

    @Test
    void submitShortAnswerNoKeywordMarkerFallsBackToExactMatch() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("标准答案文本");
        q.setExplanation("普通解析没有采分点标记");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(wqMapper).insert(any(WrongQuestion.class));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        // 精确相等 -> 正确
        assertEquals(1, examService.submit("e1", "u1", Map.of("q1", "标准答案文本")).get("score"));
        // 内容相似但不等 -> 错误
        assertEquals(0, examService.submit("e1", "u1", Map.of("q1", "标准答案")).get("score"));
    }

    @Test
    void submitShortAnswerNullExplanationTreatedAsNoKeywords() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("固定答案");
        q.setExplanation(null);

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(wqMapper).insert(any(WrongQuestion.class));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        assertEquals(1, examService.submit("e1", "u1", Map.of("q1", "固定答案")).get("score"));
        assertEquals(0, examService.submit("e1", "u1", Map.of("q1", "别的答案")).get("score"));
    }

    @Test
    void submitShortAnswerKeywordCharCoverageFuzzyMatch() {
        // 测试字符覆盖率匹配（同义词场景：数据隐藏 vs 信息隐藏）
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("封装的作用是数据隐藏");
        q.setExplanation("【采分点】封装、数据隐藏");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        // "信息隐藏" vs "数据隐藏"：共享"隐藏"两字，字符覆盖率检测应能识别
        Map<String, String> answers = Map.of("q1", "封装 信息隐藏");
        Map<String, Object> result = examService.submit("e1", "u1", answers);
        assertEquals(1, result.get("score"));
    }

    // ---------- submit: 混合题型综合 ----------

    @Test
    void submitMixedQuestionTypesGradesCorrectly() {
        Question q1 = new Question();
        q1.setId("q1");
        q1.setType("choice");
        q1.setAnswer("A");

        Question q2 = new Question();
        q2.setId("q2");
        q2.setType("fill");
        q2.setAnswer("Java");

        Question q3 = new Question();
        q3.setId("q3");
        q3.setType("shortanswer");
        q3.setAnswer("...");
        q3.setExplanation("【采分点】多态、封装");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q1, q2, q3));
        doNothing().when(wqMapper).insert(any(WrongQuestion.class));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        // q1正确, q2错误, q3命中1个=50%<60%错误 => 总分1
        Map<String, String> answers = new HashMap<>();
        answers.put("q1", "A");
        answers.put("q2", "Python");
        answers.put("q3", "提到了封装这个概念");
        Map<String, Object> result = examService.submit("e1", "u1", answers);

        assertEquals(1, result.get("score"));
        assertEquals(3, result.get("total"));
    }

    // ---------- submit: 空答案/缺失答案 ----------

    @Test
    void submitEmptyAnswerTreatedAsBlank() {
        Question q = new Question();
        q.setId("q1");
        q.setType("fill");
        q.setAnswer("Hello");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(wqMapper).insert(any(WrongQuestion.class));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        // 完全没提交该题
        Map<String, Object> r1 = examService.submit("e1", "u1", Map.of());
        assertEquals(0, r1.get("score"));

        // 提交空字符串
        Map<String, Object> r2 = examService.submit("e1", "u1", Map.of("q1", ""));
        assertEquals(0, r2.get("score"));

        // 提交全空格（会trim）
        Map<String, Object> r3 = examService.submit("e1", "u1", Map.of("q1", "   "));
        assertEquals(0, r3.get("score"));
    }

    // ---------- submit: 错题本去重 ----------

    @Test
    void submitDuplicateWrongQuestionInsertIgnored() {
        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setAnswer("B");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doThrow(new RuntimeException("duplicate key")).when(wqMapper).insert(any(WrongQuestion.class));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        // 错题本重复插入应该被忽略，不影响整体提交
        Map<String, Object> result = examService.submit("e1", "u1", Map.of("q1", "A"));
        assertEquals(0, result.get("score"));
        assertNotNull(result.get("id")); // attemptId 应该已生成
    }

    // ---------- submit: attempt持久化 ----------

    @Test
    void submitPersistsAttemptRecord() {
        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setAnswer("A");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        examService.submit("e1", "u1", Map.of("q1", "A"));

        verify(attemptMapper).insert(argThat(attempt ->
            "e1".equals(attempt.getExamId())
                && "u1".equals(attempt.getUserId())
                && attempt.getScore() == 1
                && attempt.getTotal() == 1
                && attempt.getId() != null
                && attempt.getAnswers() != null
        ));
    }

    // ---------- submit: hitRatio四舍五入到2位小数 ----------

    @Test
    void submitHitRatioRoundedToTwoDecimals() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("...");
        q.setExplanation("【采分点】A、B、C");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        // 命中1/3 = 0.333... -> 舍入后0.33
        Map<String, Object> result = examService.submit("e1", "u1", Map.of("q1", "包含A关键词"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertEquals(0.33, graded.get("q1").get("hitRatio"));
    }
}
