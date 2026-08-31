package com.exam.service;

import com.exam.mapper.*;
import com.exam.model.Question;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExamServiceScoringTest {

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
        examService = new ExamService(
            examMapper, questionMapper, docMapper, deepSeek, ragService,
            wqMapper, attemptMapper, taskQueue
        );
    }

    private Question question(String id, String type, String answer) {
        return question(id, type, answer, "");
    }

    private Question question(String id, String type, String answer, String explanation) {
        Question q = new Question();
        q.setId(id);
        q.setExamId("ex1");
        q.setType(type);
        q.setContent("题目" + id);
        q.setOptions("[]");
        q.setAnswer(answer);
        q.setExplanation(explanation);
        return q;
    }

    // ==================== 选择题：精确匹配 ====================

    @Test
    void submitChoiceCorrectAddsScore() {
        List<Question> qs = List.of(question("q1", "choice", "B"));
        when(questionMapper.findByExamId("ex1")).thenReturn(qs);

        Map<String, String> answers = Map.of("q1", "B");
        var result = examService.submit("ex1", "u1", answers);

        assertEquals(1, result.get("score"));
        assertEquals(1, result.get("total"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertTrue((Boolean) graded.get("q1").get("correct"));
    }

    @Test
    void submitChoiceWrongNoScore() {
        List<Question> qs = List.of(question("q1", "choice", "B"));
        when(questionMapper.findByExamId("ex1")).thenReturn(qs);

        var result = examService.submit("ex1", "u1", Map.of("q1", "A"));

        assertEquals(0, result.get("score"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertFalse((Boolean) graded.get("q1").get("correct"));
        assertEquals("A", graded.get("q1").get("userAnswer"));
        assertEquals("B", graded.get("q1").get("correctAnswer"));
    }

    @Test
    void submitChoiceWrongInsertsWrongQuestion() {
        List<Question> qs = List.of(question("q1", "choice", "B"));
        when(questionMapper.findByExamId("ex1")).thenReturn(qs);

        examService.submit("ex1", "u1", Map.of("q1", "C"));

        verify(wqMapper).insert(argThat(wq ->
            "u1_q1".equals(wq.getId())
            && "u1".equals(wq.getUserId())
            && "q1".equals(wq.getQuestionId())
            && "ex1".equals(wq.getExamId())
            && "C".equals(wq.getUserAnswer())
            && Boolean.FALSE.equals(wq.getMastered())
            && Integer.valueOf(0).equals(wq.getReviewCount())
        ));
    }

    @Test
    void submitChoiceMissingAnswerTreatedAsEmpty() {
        List<Question> qs = List.of(question("q1", "choice", "B"));
        when(questionMapper.findByExamId("ex1")).thenReturn(qs);

        // 不传该题答案
        var result = examService.submit("ex1", "u1", Map.of());

        assertEquals(0, result.get("score"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertEquals("", graded.get("q1").get("userAnswer"));
    }

    @Test
    void submitAnswerWhitespaceIsTrimmed() {
        // 答案前后空格应不影响匹配
        List<Question> qs = List.of(question("q1", "fill", "Spring"));
        when(questionMapper.findByExamId("ex1")).thenReturn(qs);

        var result = examService.submit("ex1", "u1", Map.of("q1", "  Spring  "));
        assertEquals(1, result.get("score"));
    }

    // ==================== 填空题 ====================

    @Test
    void submitFillBlankCorrect() {
        List<Question> qs = List.of(question("q1", "fill", "IoC容器"));
        when(questionMapper.findByExamId("ex1")).thenReturn(qs);

        var result = examService.submit("ex1", "u1", Map.of("q1", "IoC容器"));
        assertEquals(1, result.get("score"));
    }

    // ==================== 判断题 ====================

    @Test
    void submitTrueFalseCorrect() {
        List<Question> qs = List.of(
            question("q1", "truefalse", "正确"),
            question("q2", "truefalse", "错误")
        );
        when(questionMapper.findByExamId("ex1")).thenReturn(qs);

        var result = examService.submit("ex1", "u1", Map.of("q1", "正确", "q2", "错误"));
        assertEquals(2, result.get("score"));
    }

    @Test
    void submitTrueFalseWrongCaseSensitive() {
        // "对" != "正确"
        List<Question> qs = List.of(question("q1", "truefalse", "正确"));
        when(questionMapper.findByExamId("ex1")).thenReturn(qs);

        var result = examService.submit("ex1", "u1", Map.of("q1", "对"));
        assertEquals(0, result.get("score"));
    }

    // ==================== 简答题：无采分点 → 精确匹配 ====================

    @Test
    void submitShortAnswerNoKeywordsExactMatch() {
        List<Question> qs = List.of(question("q1", "shortanswer", "封装继承多态", "这是解释，无采分点标记"));
        when(questionMapper.findByExamId("ex1")).thenReturn(qs);

        var result = examService.submit("ex1", "u1", Map.of("q1", "封装继承多态"));
        assertEquals(1, result.get("score"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertEquals(1.0, graded.get("q1").get("hitRatio"));
        assertFalse((Boolean) graded.get("q1").get("isFuzzyMatch"));
    }

    @Test
    void submitShortAnswerNoKeywordsMismatchZero() {
        List<Question> qs = List.of(question("q1", "shortanswer", "完全不同的答案", "纯解释无采分点"));
        when(questionMapper.findByExamId("ex1")).thenReturn(qs);

        var result = examService.submit("ex1", "u1", Map.of("q1", "胡说八道"));
        assertEquals(0, result.get("score"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertEquals(0.0, graded.get("q1").get("hitRatio"));
    }

    // ==================== 简答题：有采分点 → 模糊匹配 ====================

    @Test
    void submitShortAnswerAllKeywordsHitFullScore() {
        String expl = "解释段落\n【采分点】封装、继承、多态";
        List<Question> qs = List.of(question("q1", "shortanswer", "OO三大特性", expl));
        when(questionMapper.findByExamId("ex1")).thenReturn(qs);

        var result = examService.submit("ex1", "u1",
            Map.of("q1", "面向对象包括封装、继承、多态三大特性"));
        assertEquals(1, result.get("score"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertEquals(1.0, graded.get("q1").get("hitRatio"));
    }

    @Test
    void submitShortAnswerPartialHitAboveThresholdPasses() {
        // FUZZY_THRESHOLD = 0.6
        // 5个关键词命中3个 = 0.6 → 通过
        String expl = "【采分点】封装、继承、多态、抽象、接口";
        List<Question> qs = List.of(question("q1", "shortanswer", "答案", expl));
        when(questionMapper.findByExamId("ex1")).thenReturn(qs);

        var result = examService.submit("ex1", "u1",
            Map.of("q1", "代码要封装，子类继承父类，使用多态特性"));  // 命中:封装/继承/多态 = 3/5 = 0.6

        assertEquals(1, result.get("score"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertEquals(0.6, graded.get("q1").get("hitRatio"));
        assertTrue((Boolean) graded.get("q1").get("isFuzzyMatch"));
    }

    @Test
    void submitShortAnswerPartialHitBelowThresholdFails() {
        // 4个关键词命中2个 = 0.5 < 0.6 → 不通过
        String expl = "【采分点】封装、继承、多态、抽象";
        List<Question> qs = List.of(question("q1", "shortanswer", "答案", expl));
        when(questionMapper.findByExamId("ex1")).thenReturn(qs);

        var result = examService.submit("ex1", "u1",
            Map.of("q1", "封装和继承"));  // 2/4 = 0.5

        assertEquals(0, result.get("score"));
    }

    @Test
    void submitShortAnswerKeywordsSeparatedByCommaAndSpace() {
        // 支持中英文逗号、顿号、空格分隔
        String expl = "【采分点】封装, 继承,多态 抽象";
        List<Question> qs = List.of(question("q1", "shortanswer", "ans", expl));
        when(questionMapper.findByExamId("ex1")).thenReturn(qs);

        var result = examService.submit("ex1", "u1",
            Map.of("q1", "封装继承多态抽象"));
        assertEquals(1, result.get("score"));
    }

    @Test
    void submitShortAnswerKeywordsOnlyReadToNewLine() {
        // 采分点后换行，后续内容不参与关键词解析
        String expl = "【采分点】封装、继承\n其他解释文字\n这不是关键词";
        List<Question> qs = List.of(question("q1", "shortanswer", "ans", expl));
        when(questionMapper.findByExamId("ex1")).thenReturn(qs);

        var result = examService.submit("ex1", "u1", Map.of("q1", "封装 继承"));
        assertEquals(1, result.get("score"));
    }

    @Test
    void submitShortAnswerKeywordMatchByCoverage() {
        // 字符覆盖率匹配："数据隐藏" vs "信息隐藏" 共享"隐藏"两字
        // keyword.length() <= 6 才尝试覆盖率；"数据隐藏"=4字
        String expl = "【采分点】数据隐藏";
        List<Question> qs = List.of(question("q1", "shortanswer", "ans", expl));
        when(questionMapper.findByExamId("ex1")).thenReturn(qs);

        var result = examService.submit("ex1", "u1",
            Map.of("q1", "这就是信息隐藏的原理"));  // 包含"隐藏"
        assertEquals(1, result.get("score"));
    }

    // ==================== hitRatio 舍入 ====================

    @Test
    void submitHitRatioRoundedToTwoDecimals() {
        // 3个关键词命中1个 = 0.3333... → 0.33
        String expl = "【采分点】A、B、C";
        List<Question> qs = List.of(question("q1", "shortanswer", "ans", expl));
        when(questionMapper.findByExamId("ex1")).thenReturn(qs);

        var result = examService.submit("ex1", "u1", Map.of("q1", "只包含A"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Map<String, Object>>) result.get("answers");
        double ratio = (Double) graded.get("q1").get("hitRatio");
        // Math.round(0.3333 * 100) / 100.0 = 0.33
        assertEquals(0.33, ratio, 0.001);
    }

    // ==================== 权限校验：get/delete ====================

    @Test
    void getByUserIdNonOwnerThrowsAccessDenied() {
        var exam = new com.exam.model.Exam();
        exam.setId("ex1");
        exam.setUserId("owner");
        when(examMapper.findById("ex1")).thenReturn(exam);
        when(questionMapper.findByExamId("ex1")).thenReturn(List.of());

        assertThrows(RuntimeException.class, () -> examService.get("ex1", "intruder"));
    }

    @Test
    void getByUserIdOwnerAccessAllowed() {
        var exam = new com.exam.model.Exam();
        exam.setId("ex1");
        exam.setUserId("owner");
        exam.setTitle("title1");
        when(examMapper.findById("ex1")).thenReturn(exam);
        when(questionMapper.findByExamId("ex1")).thenReturn(List.of());

        var result = examService.get("ex1", "owner");
        assertNotNull(result);
        assertEquals("ex1", result.get("id"));
    }

    @Test
    void getByUserIdNonExistentReturnsNull() {
        when(examMapper.findById("ghost")).thenReturn(null);
        assertNull(examService.get("ghost", "any"));
    }

    @Test
    void deleteByUserIdNonExistentThrows() {
        when(examMapper.findById("exX")).thenReturn(null);
        assertThrows(RuntimeException.class, () -> examService.delete("exX", "u1"));
    }

    @Test
    void deleteByUserIdNonOwnerThrows() {
        var exam = new com.exam.model.Exam();
        exam.setId("ex1");
        exam.setUserId("owner");
        when(examMapper.findById("ex1")).thenReturn(exam);

        assertThrows(RuntimeException.class, () -> examService.delete("ex1", "other"));
        verify(examMapper, never()).deleteById(anyString());
    }

    @Test
    void deleteByUserIdOwnerAllowed() {
        var exam = new com.exam.model.Exam();
        exam.setId("ex1");
        exam.setUserId("owner");
        when(examMapper.findById("ex1")).thenReturn(exam);

        examService.delete("ex1", "owner");
        verify(examMapper).deleteById("ex1");
    }

    // ==================== 考试记录持久化 ====================

    @Test
    void submitPersistsExamAttempt() {
        List<Question> qs = List.of(question("q1", "choice", "A"));
        when(questionMapper.findByExamId("ex1")).thenReturn(qs);

        examService.submit("ex1", "u1", Map.of("q1", "A"));

        ArgumentCaptor<com.exam.model.ExamAttempt> captor =
            ArgumentCaptor.forClass(com.exam.model.ExamAttempt.class);
        verify(attemptMapper).insert(captor.capture());
        com.exam.model.ExamAttempt a = captor.getValue();
        assertEquals("ex1", a.getExamId());
        assertEquals("u1", a.getUserId());
        assertEquals(Integer.valueOf(1), a.getScore());
        assertEquals(Integer.valueOf(1), a.getTotal());
        assertNotNull(a.getId());
        assertEquals(8, a.getId().length());
    }

    // ==================== 错题去重：插入异常被吞 ====================

    @Test
    void submitWrongQuestionDuplicateInsertExceptionSwallowed() {
        // 同一用户重复作答同一错题：wqMapper.insert 抛异常（主键冲突），不应中断
        List<Question> qs = List.of(question("q1", "choice", "A"));
        when(questionMapper.findByExamId("ex1")).thenReturn(qs);
        doThrow(new RuntimeException("duplicate key")).when(wqMapper).insert(any());

        assertDoesNotThrow(() -> examService.submit("ex1", "u1", Map.of("q1", "B")));

        // 但仍应尝试写入 attempt
        verify(attemptMapper).insert(any());
    }

    // ==================== 多题混合判分汇总 ====================

    @Test
    void submitMixedTypesCorrectSummary() {
        List<Question> qs = List.of(
            question("q1", "choice", "B"),
            question("q2", "fill", "Spring"),
            question("q3", "truefalse", "错误"),
            question("q4", "shortanswer", "参考",
                "【采分点】封装、继承、多态、抽象")
        );
        when(questionMapper.findByExamId("ex1")).thenReturn(qs);

        Map<String, String> answers = new HashMap<>();
        answers.put("q1", "B");               // 对
        answers.put("q2", "wrongAnswer");     // 错
        answers.put("q3", "错误");            // 对
        answers.put("q4", "封装和继承");      // 2/4 = 0.5 < 0.6 → 错

        var result = examService.submit("ex1", "u1", answers);
        assertEquals(2, result.get("score"));
        assertEquals(4, result.get("total"));
    }
}
