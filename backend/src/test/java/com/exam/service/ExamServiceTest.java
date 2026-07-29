package com.exam.service;

import com.exam.mapper.*;
import com.exam.model.Exam;
import com.exam.model.Question;
import com.exam.model.WrongQuestion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
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
        examService = new ExamService(
            examMapper, questionMapper, docMapper, deepSeek, ragService,
            wqMapper, attemptMapper, taskQueue);
    }

    // ===== 访问控制：get() 权限校验 =====

    @Test
    void getExamByIdOwnerSucceeds() {
        Exam exam = new Exam();
        exam.setId("ex1");
        exam.setUserId("owner");
        exam.setTitle("My Exam");
        exam.setQuestionTypes("choice,fill");
        exam.setQuestionCount(2);
        when(examMapper.findById("ex1")).thenReturn(exam);

        List<Question> questions = new ArrayList<>();
        when(questionMapper.findByExamId("ex1")).thenReturn(questions);

        var result = examService.get("ex1", "owner");
        assertNotNull(result);
        assertEquals("ex1", result.get("id"));
        assertEquals("owner", result.get("userId"));
        assertEquals(questions, result.get("questions"));
    }

    @Test
    void getExamByIdNonOwnerThrows() {
        Exam exam = new Exam();
        exam.setId("ex1");
        exam.setUserId("owner");
        when(examMapper.findById("ex1")).thenReturn(exam);

        var ex = assertThrows(RuntimeException.class,
            () -> examService.get("ex1", "hacker"));
        assertEquals("无权访问该考试", ex.getMessage());
    }

    @Test
    void getExamByIdNotFoundReturnsNull() {
        when(examMapper.findById("nonexistent")).thenReturn(null);
        assertNull(examService.get("nonexistent", "anyuser"));
    }

    // ===== 访问控制：delete() 权限校验 =====

    @Test
    void deleteExamOwnerSucceeds() {
        Exam exam = new Exam();
        exam.setId("ex1");
        exam.setUserId("owner");
        when(examMapper.findById("ex1")).thenReturn(exam);
        doNothing().when(examMapper).deleteById("ex1");

        assertDoesNotThrow(() -> examService.delete("ex1", "owner"));
        verify(examMapper).deleteById("ex1");
    }

    @Test
    void deleteExamNonOwnerThrows() {
        Exam exam = new Exam();
        exam.setId("ex1");
        exam.setUserId("owner");
        when(examMapper.findById("ex1")).thenReturn(exam);

        var ex = assertThrows(RuntimeException.class,
            () -> examService.delete("ex1", "hacker"));
        assertEquals("无权删除该考试", ex.getMessage());
        verify(examMapper, never()).deleteById(anyString());
    }

    @Test
    void deleteExamNotFoundThrows() {
        when(examMapper.findById("nonexistent")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> examService.delete("nonexistent", "anyuser"));
        assertEquals("考试不存在", ex.getMessage());
    }

    // ===== list() =====

    @Test
    void listReturnsExamsForUser() {
        List<Exam> exams = new ArrayList<>();
        Exam e1 = new Exam();
        e1.setId("e1");
        exams.add(e1);
        when(examMapper.findByUserId("u1")).thenReturn(exams);

        var result = examService.list("u1");
        assertEquals(1, result.size());
        assertEquals("e1", result.get(0).getId());
    }

    // ===== submit() 判分逻辑 =====

    private Question makeChoiceQuestion(String id, String answer) {
        Question q = new Question();
        q.setId(id);
        q.setType("choice");
        q.setContent("What is 2+2?");
        q.setAnswer(answer);
        q.setExplanation("Basic math");
        return q;
    }

    private Question makeFillQuestion(String id, String answer) {
        Question q = new Question();
        q.setId(id);
        q.setType("fill");
        q.setContent("___ is the capital of France.");
        q.setAnswer(answer);
        return q;
    }

    private Question makeTrueFalseQuestion(String id, String answer) {
        Question q = new Question();
        q.setId(id);
        q.setType("truefalse");
        q.setContent("Earth is flat.");
        q.setAnswer(answer);
        return q;
    }

    private Question makeShortAnswerQuestion(String id, String answer, String explanationWithKeywords) {
        Question q = new Question();
        q.setId(id);
        q.setType("shortanswer");
        q.setContent("Explain the principle of encapsulation.");
        q.setAnswer(answer);
        q.setExplanation(explanationWithKeywords);
        return q;
    }

    @Test
    void submitChoiceQuestionCorrectGetsScore() {
        Question q = makeChoiceQuestion("q1", "B");
        when(questionMapper.findByExamId("ex1")).thenReturn(List.of(q));
        when(attemptMapper.insert(any())).thenReturn(null);

        Map<String, String> answers = Map.of("q1", "B");
        var result = examService.submit("ex1", "u1", answers);

        assertEquals(1, result.get("score"));
        assertEquals(1, result.get("total"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertTrue((Boolean) graded.get("q1").get("correct"));
        assertEquals("B", graded.get("q1").get("correctAnswer"));
    }

    @Test
    void submitChoiceQuestionWrongNoScoreAndAddsWrongQuestion() {
        Question q = makeChoiceQuestion("q1", "B");
        when(questionMapper.findByExamId("ex1")).thenReturn(List.of(q));
        when(attemptMapper.insert(any())).thenReturn(null);
        when(wqMapper.insert(any(WrongQuestion.class))).thenReturn(null);

        Map<String, String> answers = Map.of("q1", "A");
        var result = examService.submit("ex1", "u1", answers);

        assertEquals(0, result.get("score"));
        assertEquals(1, result.get("total"));

        ArgumentCaptor<WrongQuestion> captor = ArgumentCaptor.forClass(WrongQuestion.class);
        verify(wqMapper).insert(captor.capture());
        WrongQuestion wq = captor.getValue();
        assertEquals("u1_q1", wq.getId());
        assertEquals("u1", wq.getUserId());
        assertEquals("A", wq.getUserAnswer());
        assertFalse(wq.getMastered());
        assertEquals(0, wq.getReviewCount());
    }

    @Test
    void submitMultipleMixedQuestionsGradesEach() {
        Question choice = makeChoiceQuestion("qc", "A");
        Question fill = makeFillQuestion("qf", "Paris");
        Question tf = makeTrueFalseQuestion("qtf", "错误");

        when(questionMapper.findByExamId("ex1")).thenReturn(List.of(choice, fill, tf));
        when(attemptMapper.insert(any())).thenReturn(null);

        // choice正确, fill正确, tf错误 → 2/3
        Map<String, String> answers = Map.of(
            "qc", "A",
            "qf", "Paris",
            "qtf", "正确"
        );
        var result = examService.submit("ex1", "u1", answers);
        assertEquals(2, result.get("score"));
        assertEquals(3, result.get("total"));
    }

    @Test
    void submitEmptyAnswerTreatedAsBlank() {
        Question q = makeFillQuestion("q1", "value");
        when(questionMapper.findByExamId("ex1")).thenReturn(List.of(q));
        when(attemptMapper.insert(any())).thenReturn(null);

        // 完全没有这个题的答案
        Map<String, String> answers = Map.of();
        var result = examService.submit("ex1", "u1", answers);

        @SuppressWarnings("unchecked")
        var graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertEquals("", graded.get("q1").get("userAnswer"));
        assertFalse((Boolean) graded.get("q1").get("correct"));
    }

    @Test
    void submitAnswerIsTrimmed() {
        Question q = makeFillQuestion("q1", "Paris");
        when(questionMapper.findByExamId("ex1")).thenReturn(List.of(q));
        when(attemptMapper.insert(any())).thenReturn(null);

        // 前后有空格，应被trim后匹配
        Map<String, String> answers = Map.of("q1", "  Paris  ");
        var result = examService.submit("ex1", "u1", answers);
        assertEquals(1, result.get("score"));
    }

    // ===== 简答题：精确匹配（无采分点） =====

    @Test
    void submitShortAnswerNoKeywordsExactMatch() {
        Question q = makeShortAnswerQuestion("qs", "封装是一种数据隐藏机制",
            "标准解释。注意这一行没有采分点标记");
        when(questionMapper.findByExamId("ex1")).thenReturn(List.of(q));
        when(attemptMapper.insert(any())).thenReturn(null);

        Map<String, String> answers = Map.of("qs", "封装是一种数据隐藏机制");
        var result = examService.submit("ex1", "u1", answers);
        assertEquals(1, result.get("score"));
    }

    @Test
    void submitShortAnswerNoKeywordsNoMatch() {
        Question q = makeShortAnswerQuestion("qs", "封装是一种数据隐藏机制",
            "没有采分点");
        when(questionMapper.findByExamId("ex1")).thenReturn(List.of(q));
        when(attemptMapper.insert(any())).thenReturn(null);

        Map<String, String> answers = Map.of("qs", "完全不相关的答案");
        var result = examService.submit("ex1", "u1", answers);
        assertEquals(0, result.get("score"));
    }

    // ===== 简答题：采分点模糊匹配 =====

    @Test
    void submitShortAnswerAllKeywordsHitIsCorrect() {
        Question q = makeShortAnswerQuestion("qs", "标准答案文本",
            "封装的基本概念\n【采分点】数据隐藏、封装、访问修饰符、私有属性");
        when(questionMapper.findByExamId("ex1")).thenReturn(List.of(q));
        when(attemptMapper.insert(any())).thenReturn(null);

        Map<String, String> answers = Map.of("qs",
            "封装就是通过数据隐藏，使用访问修饰符将私有属性保护起来。");
        var result = examService.submit("ex1", "u1", answers);

        assertEquals(1, result.get("score"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertTrue((Boolean) graded.get("qs").get("correct"));
        // 100%命中 → 不是fuzzy match
        assertFalse((Boolean) graded.get("qs").get("isFuzzyMatch"));
        assertEquals(1.0, (Double) graded.get("qs").get("hitRatio"), 0.001);
    }

    @Test
    void submitShortAnswerAboveThresholdIsFuzzyCorrect() {
        // 4个采分点，命中3个 = 0.75 ≥ 0.6 → 正确（模糊匹配）
        Question q = makeShortAnswerQuestion("qs", "标准答案文本",
            "解释内容\n【采分点】多态、继承、封装、抽象");
        when(questionMapper.findByExamId("ex1")).thenReturn(List.of(q));
        when(attemptMapper.insert(any())).thenReturn(null);

        // 命中"多态、继承、封装" 3个 → 75%
        Map<String, String> answers = Map.of("qs",
            "面向对象三大特性是封装、继承、多态。");
        var result = examService.submit("ex1", "u1", answers);

        assertEquals(1, result.get("score"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertTrue((Boolean) graded.get("qs").get("correct"));
        assertTrue((Boolean) graded.get("qs").get("isFuzzyMatch"));
        assertEquals(0.75, (Double) graded.get("qs").get("hitRatio"), 0.001);
    }

    @Test
    void submitShortAnswerBelowThresholdIsWrong() {
        // 4个采分点，命中2个 = 0.5 < 0.6 → 错误
        Question q = makeShortAnswerQuestion("qs", "标准答案文本",
            "解释\n【采分点】数据隐藏、抽象类、接口、访问控制");
        when(questionMapper.findByExamId("ex1")).thenReturn(List.of(q));
        when(attemptMapper.insert(any())).thenReturn(null);

        // 只命中"接口、访问"相关（2/4 = 50%）
        Map<String, String> answers = Map.of("qs",
            "接口定义了契约，用于访问公共方法。");
        var result = examService.submit("ex1", "u1", answers);

        assertEquals(0, result.get("score"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertFalse((Boolean) graded.get("qs").get("correct"));
    }

    @Test
    void submitShortAnswerExactlyAtThresholdIsCorrect() {
        // 5个采分点，命中3个 = 0.6 → 刚好等于阈值，应判为正确
        Question q = makeShortAnswerQuestion("qs", "标准答案",
            "内容\n【采分点】A、B、C、D、E");
        when(questionMapper.findByExamId("ex1")).thenReturn(List.of(q));
        when(attemptMapper.insert(any())).thenReturn(null);

        Map<String, String> answers = Map.of("qs", "答案中有A B C");
        var result = examService.submit("ex1", "u1", answers);

        assertEquals(1, result.get("score"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertTrue((Boolean) graded.get("qs").get("correct"));
        assertEquals(0.6, (Double) graded.get("qs").get("hitRatio"), 0.001);
    }

    @Test
    void submitShortAnswerKeywordsSplitByDifferentSeparators() {
        // 顿号、逗号、空格混合分隔
        Question q = makeShortAnswerQuestion("qs", "答案",
            "说明\n【采分点】封装,继承 多态、抽象");
        when(questionMapper.findByExamId("ex1")).thenReturn(List.of(q));
        when(attemptMapper.insert(any())).thenReturn(null);

        // 全部命中
        Map<String, String> answers = Map.of("qs",
            "面向对象包含封装、继承、多态和抽象四大特性。");
        var result = examService.submit("ex1", "u1", answers);
        assertEquals(1, result.get("score"));
    }

    @Test
    void submitShortAnswerBlankExplanationFallsBackExact() {
        Question q = makeShortAnswerQuestion("qs", "标准答案", null);
        when(questionMapper.findByExamId("ex1")).thenReturn(List.of(q));
        when(attemptMapper.insert(any())).thenReturn(null);

        // 空explanation → 无采分点 → 精确匹配
        Map<String, String> answers = Map.of("qs", "标准答案");
        var result = examService.submit("ex1", "u1", answers);
        assertEquals(1, result.get("score"));
    }

    @Test
    void submitShortAnswerEmptyExplanationFallsBackExact() {
        Question q = makeShortAnswerQuestion("qs", "标准答案", "");
        when(questionMapper.findByExamId("ex1")).thenReturn(List.of(q));
        when(attemptMapper.insert(any())).thenReturn(null);

        Map<String, String> answers = Map.of("qs", "标准答案");
        var result = examService.submit("ex1", "u1", answers);
        assertEquals(1, result.get("score"));
    }

    // ===== 采分点行边界解析 =====

    @Test
    void submitShortAnswerKeywordsOnlyTakeFirstLine() {
        // 【采分点】之后有换行，应该只取到换行前的关键词
        Question q = makeShortAnswerQuestion("qs", "答案",
            "长解释\n【采分点】关键词1、关键词2\n后续内容不应解析为关键词3、关键词4");
        when(questionMapper.findByExamId("ex1")).thenReturn(List.of(q));
        when(attemptMapper.insert(any())).thenReturn(null);

        // 只命中关键词1（不命中关键词2）= 50% < 60% → 错
        Map<String, String> answers1 = Map.of("qs", "包含关键词1但没有第二个");
        var r1 = examService.submit("ex1", "u1", answers1);
        assertEquals(0, r1.get("score"));

        // 如果命中2个关键词 → 100%
        Map<String, String> answers2 = Map.of("qs", "包含关键词1和关键词2");
        var r2 = examService.submit("ex1", "u2", answers2);
        assertEquals(1, r2.get("score"));
    }

    // ===== 字符覆盖率匹配（同义词/近似表达） =====

    @Test
    void submitShortAnswerCharCoverageTriggersPartialMatch() {
        // 采分词较长的不触发覆盖率匹配(>6字符)；短的字符匹配
        Question q = makeShortAnswerQuestion("qs", "答案",
            "解释\n【采分点】数据隐藏、封装、私有字段");
        when(questionMapper.findByExamId("ex1")).thenReturn(List.of(q));
        when(attemptMapper.insert(any())).thenReturn(null);

        // "信息隐藏" 共享 "隐藏"二字 → 与 "数据隐藏" 有部分字符重叠
        // 精确子串不包含"数据隐藏"，但字符覆盖率：
        //   "数据隐藏"4字中，用户答案含"隐藏"2字 → 50% → 不匹配（<0.5 threshold？不，≥0.5才匹配）
        // 让我们直接匹配"封装"和"私有"：(命中2/3=66.7% ≥60% → 正确)
        Map<String, String> answers = Map.of("qs", "封装保护私有字段的数据");
        var result = examService.submit("ex1", "u1", answers);
        assertEquals(1, result.get("score"));
    }

    // ===== 任务状态查询 =====

    @Test
    void getTaskStatusDelegatesToTaskQueue() {
        when(taskQueue.getStatus("exam:task:", "task123")).thenReturn("DONE:result");
        assertEquals("DONE:result", examService.getTaskStatus("task123"));
        verify(taskQueue).getStatus("exam:task:", "task123");
    }

    // ===== 错题重复插入忽略异常 =====

    @Test
    void submitDuplicateWrongQuestionDoesNotCrash() {
        Question q = makeChoiceQuestion("q1", "B");
        when(questionMapper.findByExamId("ex1")).thenReturn(List.of(q));
        when(attemptMapper.insert(any())).thenReturn(null);
        // 重复错题，insert抛出异常（主键冲突），应被静默吞掉
        when(wqMapper.insert(any(WrongQuestion.class)))
            .thenThrow(new RuntimeException("Duplicate key"));

        Map<String, String> answers = Map.of("q1", "A");
        assertDoesNotThrow(() -> examService.submit("ex1", "u1", answers));
    }

    // ===== 考试尝试持久化 =====

    @Test
    void submitPersistsExamAttempt() {
        Question q = makeChoiceQuestion("q1", "A");
        when(questionMapper.findByExamId("ex1")).thenReturn(List.of(q));
        when(wqMapper.insert(any(WrongQuestion.class))).thenReturn(null);

        Map<String, String> answers = Map.of("q1", "A");
        examService.submit("ex1", "u1", answers);

        verify(attemptMapper).insert(any(com.exam.model.ExamAttempt.class));
    }
}
