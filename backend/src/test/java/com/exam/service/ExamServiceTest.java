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
        examService = new ExamService(examMapper, questionMapper, docMapper,
                deepSeek, ragService, wqMapper, attemptMapper, taskQueue);
    }

    // ==================== get() 访问控制 ====================

    @Test
    void getWithUserIdReturnsExamWithQuestions() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setTitle("Test Exam");
        exam.setUserId("user1");
        exam.setQuestionTypes("choice,fill");
        exam.setQuestionCount(2);

        Question q1 = new Question();
        q1.setId("q1");
        q1.setExamId("e1");
        q1.setType("choice");
        q1.setContent("What is Java?");
        q1.setAnswer("B");

        when(examMapper.findById("e1")).thenReturn(exam);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q1));

        Map<String, Object> result = examService.get("e1", "user1");

        assertNotNull(result);
        assertEquals("e1", result.get("id"));
        assertEquals("Test Exam", result.get("title"));
        assertEquals(2, result.get("questionCount"));
        assertEquals("choice,fill", result.get("questionTypes"));
        @SuppressWarnings("unchecked")
        List<Question> questions = (List<Question>) result.get("questions");
        assertEquals(1, questions.size());
        assertEquals("q1", questions.get(0).getId());
    }

    @Test
    void getWithWrongUserIdThrowsAccessDenied() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("owner");

        when(examMapper.findById("e1")).thenReturn(exam);

        var ex = assertThrows(RuntimeException.class,
                () -> examService.get("e1", "intruder"));
        assertEquals("无权访问该考试", ex.getMessage());
    }

    @Test
    void getNonExistingExamReturnsNull() {
        when(examMapper.findById("nonexistent")).thenReturn(null);
        assertNull(examService.get("nonexistent", "user1"));
    }

    @Test
    void getAdminModeNoPermissionCheck() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("owner");
        exam.setTitle("Admin View");

        when(examMapper.findById("e1")).thenReturn(exam);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of());

        Map<String, Object> result = examService.get("e1");
        assertNotNull(result);
        assertEquals("Admin View", result.get("title"));
    }

    // ==================== delete() 权限校验 ====================

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

    @Test
    void deleteByNonOwnerThrows() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("owner");

        when(examMapper.findById("e1")).thenReturn(exam);

        var ex = assertThrows(RuntimeException.class,
                () -> examService.delete("e1", "other"));
        assertEquals("无权删除该考试", ex.getMessage());
        verify(examMapper, never()).deleteById(any());
    }

    @Test
    void deleteNonExistingExamThrows() {
        when(examMapper.findById("ghost")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
                () -> examService.delete("ghost", "user1"));
        assertEquals("考试不存在", ex.getMessage());
    }

    @Test
    void deleteAdminModeNoPermissionCheck() {
        doNothing().when(examMapper).deleteById("anyExam");
        assertDoesNotThrow(() -> examService.delete("anyExam"));
        verify(examMapper).deleteById("anyExam");
    }

    // ==================== submit() 核心评分逻辑 ====================

    private Question makeChoice(String id, String answer) {
        Question q = new Question();
        q.setId(id);
        q.setExamId("exam1");
        q.setType("choice");
        q.setContent("选择题");
        q.setOptions("[\"A|1\",\"B|2\",\"C|3\",\"D|4\"]");
        q.setAnswer(answer);
        return q;
    }

    private Question makeFill(String id, String answer) {
        Question q = new Question();
        q.setId(id);
        q.setExamId("exam1");
        q.setType("fill");
        q.setContent("填空题");
        q.setAnswer(answer);
        return q;
    }

    private Question makeTrueFalse(String id, String answer) {
        Question q = new Question();
        q.setId(id);
        q.setExamId("exam1");
        q.setType("truefalse");
        q.setContent("判断题");
        q.setAnswer(answer);
        return q;
    }

    private Question makeShortAnswer(String id, String answer, String explanationWithKeywords) {
        Question q = new Question();
        q.setId(id);
        q.setExamId("exam1");
        q.setType("shortanswer");
        q.setContent("简答题");
        q.setAnswer(answer);
        q.setExplanation(explanationWithKeywords);
        return q;
    }

    @Test
    void submitChoiceCorrectGetsScore() {
        Question q = makeChoice("q1", "C");
        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        Map<String, String> answers = new HashMap<>();
        answers.put("q1", "C");

        Map<String, Object> result = examService.submit("exam1", "u1", answers);

        assertEquals(1, result.get("score"));
        assertEquals(1, result.get("total"));
        @SuppressWarnings("unchecked")
        Map<String, Object> graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> gq1 = (Map<String, Object>) graded.get("q1");
        assertTrue((Boolean) gq1.get("correct"));
        assertEquals("C", gq1.get("correctAnswer"));
    }

    @Test
    void submitChoiceWrongNoScoreAndRecordsWrong() {
        Question q = makeChoice("q1", "A");
        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        Map<String, String> answers = Map.of("q1", "D");

        Map<String, Object> result = examService.submit("exam1", "u1", answers);

        assertEquals(0, result.get("score"));
        verify(wqMapper).insert(any());
    }

    @Test
    void submitFillBlankAnswersTrimmed() {
        Question q = makeFill("q1", "Spring");
        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        // 前后有空格
        Map<String, String> answers = Map.of("q1", "  Spring  ");

        Map<String, Object> result = examService.submit("exam1", "u1", answers);
        assertEquals(1, result.get("score"));
    }

    @Test
    void submitTrueFalseCorrectAnswer() {
        Question q = makeTrueFalse("q1", "正确");
        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        Map<String, String> answers = Map.of("q1", "正确");
        assertEquals(1, examService.submit("exam1", "u1", answers).get("score"));
    }

    @Test
    void submitTrueFalseWrongAnswer() {
        Question q = makeTrueFalse("q1", "错误");
        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        Map<String, String> answers = Map.of("q1", "正确");
        assertEquals(0, examService.submit("exam1", "u1", answers).get("score"));
    }

    // ==================== 简答题模糊匹配 ====================

    @Test
    void submitShortAnswerFuzzyAboveThresholdMarkedCorrect() {
        // 3个采分点，答对2个 = 66.7% >= 60% 阈值
        Question q = makeShortAnswer("q1", "封装继承多态",
                "面向对象三大特性。【采分点】封装、继承、多态");
        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        // 命中2/3
        Map<String, String> answers = Map.of("q1", "面向对象包括封装和继承两个主要概念");
        Map<String, Object> result = examService.submit("exam1", "u1", answers);

        assertEquals(1, result.get("score"));
        @SuppressWarnings("unchecked")
        Map<String, Object> g = (Map<String, Object>) ((Map<?, ?>) result.get("answers")).get("q1");
        assertTrue((Boolean) g.get("correct"));
        assertTrue((Boolean) g.get("isFuzzyMatch"));
        double ratio = (Double) g.get("hitRatio");
        assertTrue(ratio >= 0.6 && ratio < 1.0);
    }

    @Test
    void submitShortAnswerFuzzyBelowThresholdMarkedWrong() {
        // 4个采分点，只答对1个 = 25% < 60%
        Question q = makeShortAnswer("q1", "四大原则",
                "设计原则。【采分点】单一职责、开闭原则、里氏替换、依赖倒置");
        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        Map<String, String> answers = Map.of("q1", "只有开闭原则很重要");
        Map<String, Object> result = examService.submit("exam1", "u1", answers);

        assertEquals(0, result.get("score"));
        @SuppressWarnings("unchecked")
        Map<String, Object> g = (Map<String, Object>) ((Map<?, ?>) result.get("answers")).get("q1");
        assertFalse((Boolean) g.get("correct"));
        assertEquals(0.25, (Double) g.get("hitRatio"));
    }

    @Test
    void submitShortAnswerExactMatch100Percent() {
        Question q = makeShortAnswer("q1", "标准答案",
                "解析。【采分点】标准、答案");
        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        Map<String, String> answers = Map.of("q1", "标准答案");
        Map<String, Object> result = examService.submit("exam1", "u1", answers);

        assertEquals(1, result.get("score"));
        @SuppressWarnings("unchecked")
        Map<String, Object> g = (Map<String, Object>) ((Map<?, ?>) result.get("answers")).get("q1");
        assertTrue((Boolean) g.get("correct"));
        assertFalse((Boolean) g.get("isFuzzyMatch"));
        assertEquals(1.0, (Double) g.get("hitRatio"));
    }

    @Test
    void submitShortAnswerNoKeywordsFallsBackToExactMatch() {
        // 没有【采分点】标记，回退精确匹配
        Question q = makeShortAnswer("q1", "精确答案",
                "普通解析没有采分点标记");
        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        Map<String, String> exact = Map.of("q1", "精确答案");
        assertEquals(1, examService.submit("exam1", "u1", exact).get("score"));

        Map<String, String> partial = Map.of("q1", "精确");
        assertEquals(0, examService.submit("exam1", "u2", partial).get("score"));
    }

    @Test
    void submitShortAnswerKeywordCoverageMatch() {
        // 字符覆盖率匹配：关键词>6字符时不走覆盖率路径，这里测试短词覆盖率
        Question q = makeShortAnswer("q1", "数据隐藏",
                "封装的本质。【采分点】隐藏、数据");
        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        // "信息隐藏" 不直接包含 "数据" 但包含"隐藏"(覆盖率足够) + 直接包含 "隐藏"
        // 实际测试：包含"隐藏"，覆盖率测试：
        Map<String, String> answers = Map.of("q1", "就是信息隐藏的概念");
        assertEquals(0, examService.submit("exam1", "u1", answers).get("score")); // 只命中1/2
    }

    @Test
    void submitShortAnswerEmptyAnswerGetsZero() {
        Question q = makeShortAnswer("q1", "答案", "【采分点】答案");
        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        Map<String, String> answers = Map.of("q1", "");
        assertEquals(0, examService.submit("exam1", "u1", answers).get("score"));
    }

    // ==================== 综合场景 ====================

    @Test
    void submitMultiQuestionMixedTypesCalculatesTotalScore() {
        Question q1 = makeChoice("q1", "A");   // 对 → 1分
        Question q2 = makeFill("q2", "Java");  // 对 → 1分
        Question q3 = makeTrueFalse("q3", "错误"); // 错 → 0分
        Question q4 = makeShortAnswer("q4", "三大特性", "【采分点】封装、继承、多态"); // 命中1/3 → 0

        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q1, q2, q3, q4));
        doNothing().when(attemptMapper).insert(any());

        Map<String, String> answers = new HashMap<>();
        answers.put("q1", "A");
        answers.put("q2", "Java");
        answers.put("q3", "正确");
        answers.put("q4", "封装是重要概念"); // 仅命中封装

        Map<String, Object> result = examService.submit("exam1", "u1", answers);

        assertEquals(2, result.get("score"));
        assertEquals(4, result.get("total"));
        @SuppressWarnings("unchecked")
        Map<String, Object> graded = (Map<String, Object>) result.get("answers");
        assertEquals(4, graded.size());
    }

    @Test
    void submitMissingQuestionIdTreatedAsEmptyString() {
        Question q = makeChoice("q1", "B");
        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        // 用户答案里没有 q1 这个 key → getOrDefault 返回 ""
        Map<String, String> answers = Map.of("other", "value");
        assertEquals(0, examService.submit("exam1", "u1", answers).get("score"));
    }

    // ==================== list() ====================

    @Test
    void listDelegatesToExamMapper() {
        List<Exam> exams = List.of(new Exam(), new Exam());
        when(examMapper.findByUserId("u1")).thenReturn(exams);

        assertEquals(2, examService.list("u1").size());
        verify(examMapper).findByUserId("u1");
    }
}
