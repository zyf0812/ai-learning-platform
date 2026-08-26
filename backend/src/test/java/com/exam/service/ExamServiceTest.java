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

    @BeforeEach
    void setUp() {
        examService = new ExamService(
            examMapper, questionMapper, docMapper,
            deepSeek, ragService, wqMapper, attemptMapper, taskQueue
        );
    }

    // ======== 权限校验测试 ========

    @Test
    void getExamAsOwner_returnsExamWithQuestions() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("userA");
        exam.setTitle("Test Exam");
        exam.setQuestionTypes("choice,fill");
        exam.setQuestionCount(2);

        Question q1 = new Question();
        q1.setId("q1");
        q1.setType("choice");

        when(examMapper.findById("e1")).thenReturn(exam);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q1));

        Map<String, Object> result = examService.get("e1", "userA");
        assertNotNull(result);
        assertEquals("e1", result.get("id"));
        assertEquals("Test Exam", result.get("title"));
        assertEquals(2, result.get("questionCount"));
        assertEquals("userA", result.get("userId"));
    }

    @Test
    void getExamAsNonOwner_throwsAccessDenied() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("userA");

        when(examMapper.findById("e1")).thenReturn(exam);

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> examService.get("e1", "userB"));
        assertEquals("无权访问该考试", ex.getMessage());
    }

    @Test
    void getExamNotFound_returnsNull() {
        when(examMapper.findById("nonexistent")).thenReturn(null);
        assertNull(examService.get("nonexistent", "userA"));
    }

    @Test
    void deleteExamAsOwner_deletesSuccessfully() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("userA");

        when(examMapper.findById("e1")).thenReturn(exam);
        doNothing().when(examMapper).deleteById("e1");

        assertDoesNotThrow(() -> examService.delete("e1", "userA"));
        verify(examMapper).deleteById("e1");
    }

    @Test
    void deleteExamAsNonOwner_throwsAccessDenied() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("userA");

        when(examMapper.findById("e1")).thenReturn(exam);

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> examService.delete("e1", "userB"));
        assertEquals("无权删除该考试", ex.getMessage());
        verify(examMapper, never()).deleteById(anyString());
    }

    @Test
    void deleteNonexistentExam_throwsNotFound() {
        when(examMapper.findById("nonexistent")).thenReturn(null);
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> examService.delete("nonexistent", "userA"));
        assertEquals("考试不存在", ex.getMessage());
    }

    // ======== 选择题/填空题/判断题 精确匹配判分 ========

    @Test
    void submit_choiceCorrectAnswer_scoresOne() {
        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setContent("Java之父？");
        q.setAnswer("B");
        q.setExplanation("解析");
        q.setOptions("[\"A|Bl\",\"B|Gosling\"]");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, String> answers = Map.of("q1", "B");
        Map<String, Object> result = examService.submit("e1", "user1", answers);

        assertEquals(1, result.get("score"));
        assertEquals(1, result.get("total"));
        @SuppressWarnings("unchecked")
        Map<String, Object> graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> g = (Map<String, Object>) graded.get("q1");
        assertTrue((Boolean) g.get("correct"));
        assertEquals("B", g.get("correctAnswer"));
        assertEquals("B", g.get("userAnswer"));
        assertEquals(1.0, g.get("hitRatio"));
        assertFalse((Boolean) g.get("isFuzzyMatch"));
    }

    @Test
    void submit_choiceWrongAnswer_scoresZero() {
        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setAnswer("B");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(wqMapper).insert(any(WrongQuestion.class));

        Map<String, String> answers = Map.of("q1", "A");
        Map<String, Object> result = examService.submit("e1", "user1", answers);

        assertEquals(0, result.get("score"));
        @SuppressWarnings("unchecked")
        Map<String, Object> graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> g = (Map<String, Object>) graded.get("q1");
        assertFalse((Boolean) g.get("correct"));
        verify(wqMapper).insert(any(WrongQuestion.class));
    }

    @Test
    void submit_fillBlankExactMatch_correct() {
        Question q = new Question();
        q.setId("q1");
        q.setType("fill");
        q.setAnswer("Spring");
        q.setExplanation("");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, Object> result = examService.submit("e1", "u1", Map.of("q1", "Spring"));
        assertEquals(1, result.get("score"));
    }

    @Test
    void submit_fillBlankExtraSpaces_stillTrimmedForCompare() {
        Question q = new Question();
        q.setId("q1");
        q.setType("fill");
        q.setAnswer("Java");
        q.setExplanation("");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, Object> result = examService.submit("e1", "u1", Map.of("q1", "  Java  "));
        assertEquals(1, result.get("score"));
    }

    @Test
    void submit_trueFalseCorrect_correct() {
        Question q = new Question();
        q.setId("q1");
        q.setType("truefalse");
        q.setAnswer("正确");
        q.setExplanation("");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, Object> result = examService.submit("e1", "u1", Map.of("q1", "正确"));
        assertEquals(1, result.get("score"));
    }

    // ======== 简答题采分点解析 + 模糊匹配 ========

    @Test
    void submit_shortanswerNoKeywords_fallbackExactMatch() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("面向对象编程");
        q.setExplanation("这里是普通解析，没有采分点标记。");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        // 精确匹配
        Map<String, Object> r1 = examService.submit("e1", "u1", Map.of("q1", "面向对象编程"));
        assertEquals(1, r1.get("score"));

        // 不匹配
        Map<String, Object> r2 = examService.submit("e1", "u2", Map.of("q1", "函数式编程"));
        assertEquals(0, r2.get("score"));
    }

    @Test
    void submit_shortanswerWithKeywords_exactAllHit_correct() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("参考");
        q.setExplanation("OOP三大特性\n【采分点】封装、继承、多态");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, Object> result = examService.submit("e1", "u1",
            Map.of("q1", "三大特性是封装、继承和多态"));
        assertEquals(1, result.get("score"));
        @SuppressWarnings("unchecked")
        Map<String, Object> graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> g = (Map<String, Object>) graded.get("q1");
        assertTrue((Boolean) g.get("correct"));
        assertEquals(1.0, g.get("hitRatio"));
    }

    @Test
    void submit_shortanswerTwoOutOfThreeKeywords_exceedsThreshold_correctFuzzy() {
        // 命中 2/3 = 66.6% > 60% 阈值 → 判为正确（模糊匹配）
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("");
        q.setExplanation("【采分点】封装、继承、多态");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, Object> result = examService.submit("e1", "u1",
            Map.of("q1", "封装和继承是OOP的核心"));
        assertEquals(1, result.get("score"));
        @SuppressWarnings("unchecked")
        Map<String, Object> graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> g = (Map<String, Object>) graded.get("q1");
        assertTrue((Boolean) g.get("correct"));
        assertTrue((Boolean) g.get("isFuzzyMatch"));
        // 2/3 = 0.66666... → round(66.666)/100 = 0.67
        assertEquals(0.67, g.get("hitRatio"));
    }

    @Test
    void submit_shortanswerOneOutOfThree_belowThreshold_wrong() {
        // 命中 1/3 = 33.3% < 60% → 错误
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("");
        q.setExplanation("【采分点】封装、继承、多态");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(wqMapper).insert(any(WrongQuestion.class));

        Map<String, Object> result = examService.submit("e1", "u1",
            Map.of("q1", "只有封装是OOP的特性"));
        assertEquals(0, result.get("score"));
        @SuppressWarnings("unchecked")
        Map<String, Object> graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> g = (Map<String, Object>) graded.get("q1");
        assertFalse((Boolean) g.get("correct"));
    }

    @Test
    void submit_shortanswerKeywordMatchPartialCharacters_coverageAboveHalf() {
        // keywordMatch 逻辑：关键字长度<=6 字符覆盖率 >=0.5 也命中
        // "封装" vs "数据隐藏" → 共享"藏"? 不，测试相反场景："继承" vs "继承机制" → 包含直接命中
        // 让我们测字符覆盖：关键词"多态性" (3字符) 用户答案"多态特征" (含"多""态" → 2/3 ≈ 0.67 ≥ 0.5)
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("");
        q.setExplanation("【采分点】多态性");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, Object> result = examService.submit("e1", "u1",
            Map.of("q1", "多态特征表现"));
        assertEquals(1, result.get("score"));
    }

    @Test
    void submit_shortanswerKeywordsAcrossMultipleLines_onlyFirstLineParsed() {
        // parseKeywords 会在换行处截断，防止解析后续内容
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("");
        q.setExplanation("解析文字。\n【采分点】封装、继承\n下一段更多内容不应解析、不应计入、关键词");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        // 只有封装、继承两个关键词；命中一个 = 50% < 60%，若把后续的也解析了，命中数会变
        Map<String, Object> result = examService.submit("e1", "u1",
            Map.of("q1", "包含封装这个词"));
        assertEquals(0, result.get("score"));
    }

    @Test
    void submit_shortanswerKeywordsWithVariousSeparators_allParsed() {
        // 顿号、逗号、中文逗号、空格均为分隔符
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("");
        q.setExplanation("【采分点】A,B，C D、E");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        // 命中4/5 = 80% → 正确
        Map<String, Object> result = examService.submit("e1", "u1",
            Map.of("q1", "A B C D 都在这里"));
        assertEquals(1, result.get("score"));
    }

    // ======== 混合多题 + 错题本 + 分数汇总 ========

    @Test
    void submit_mixedQuestionTypes_scoresSummedCorrectly() {
        Question q1 = new Question();
        q1.setId("q1"); q1.setType("choice"); q1.setAnswer("A"); q1.setExplanation("");

        Question q2 = new Question();
        q2.setId("q2"); q2.setType("fill"); q2.setAnswer("JVM"); q2.setExplanation("");

        Question q3 = new Question();
        q3.setId("q3"); q3.setType("truefalse"); q3.setAnswer("错误"); q3.setExplanation("");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q1, q2, q3));
        doNothing().when(wqMapper).insert(any(WrongQuestion.class));

        Map<String, String> answers = new HashMap<>();
        answers.put("q1", "A");      // 对
        answers.put("q2", "JRE");    // 错
        answers.put("q3", "错误");   // 对

        Map<String, Object> result = examService.submit("e1", "u1", answers);

        assertEquals(2, result.get("score"));
        assertEquals(3, result.get("total"));
        // 1道错题 → 错题本写入1次
        verify(wqMapper, times(1)).insert(any(WrongQuestion.class));
        verify(attemptMapper).insert(any(ExamAttempt.class));
    }

    @Test
    void submit_missingAnswer_treatedAsEmptyString() {
        Question q = new Question();
        q.setId("q1");
        q.setType("fill");
        q.setAnswer("正确答案");
        q.setExplanation("");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(wqMapper).insert(any(WrongQuestion.class));

        // answers 中没有 q1 → 默认为 ""
        Map<String, Object> result = examService.submit("e1", "u1", Map.of());
        assertEquals(0, result.get("score"));
    }

    @Test
    void submit_wrongQuestionIdCompositeId_correctlyFormatted() {
        Question q = new Question();
        q.setId("qABC");
        q.setType("fill");
        q.setAnswer("答案");
        q.setExplanation("");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(wqMapper).insert(any(WrongQuestion.class));

        examService.submit("e1", "uXYZ", Map.of());

        // 捕获并验证错题本 ID 为 userId_questionId
        verify(wqMapper).insert(argThat(wq ->
            "uXYZ_qABC".equals(wq.getId())
            && "uXYZ".equals(wq.getUserId())
            && "qABC".equals(wq.getQuestionId())
            && "e1".equals(wq.getExamId())
            && !wq.getMastered()
            && wq.getReviewCount() == 0
        ));
    }

    // ======== 任务状态查询 ========

    @Test
    void getTaskStatus_delegatesToTaskQueue() {
        when(taskQueue.getStatus("exam:task:", "task123")).thenReturn("DONE:exam456");
        assertEquals("DONE:exam456", examService.getTaskStatus("task123"));
        verify(taskQueue).getStatus("exam:task:", "task123");
    }
}
