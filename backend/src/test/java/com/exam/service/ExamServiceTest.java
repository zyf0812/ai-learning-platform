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
            examMapper, questionMapper, docMapper, deepSeek, ragService,
            wqMapper, attemptMapper, taskQueue
        );
    }

    // ========== 选择题判分：精确匹配 ==========

    @Test
    void submitChoiceCorrectAnswerScores() {
        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setAnswer("B");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        when(wqMapper.insert(any())).thenReturn(1);
        when(attemptMapper.insert(any())).thenReturn(1);

        Map<String, String> answers = Map.of("q1", "B");
        var result = examService.submit("e1", "u1", answers);

        assertEquals(1, result.get("score"));
        assertEquals(1, result.get("total"));
        @SuppressWarnings("unchecked")
        Map<String, Object> graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> g = (Map<String, Object>) graded.get("q1");
        assertTrue((Boolean) g.get("correct"));
        assertEquals("B", g.get("correctAnswer"));
        assertEquals("B", g.get("userAnswer"));
    }

    @Test
    void submitChoiceWrongAnswerNoScore() {
        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setAnswer("A");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        when(wqMapper.insert(any())).thenReturn(1);
        when(attemptMapper.insert(any())).thenReturn(1);

        Map<String, String> answers = Map.of("q1", "C");
        var result = examService.submit("e1", "u1", answers);

        assertEquals(0, result.get("score"));
    }

    // ========== 填空题判分 ==========

    @Test
    void submitFillBlankCorrectAnswerScores() {
        Question q = new Question();
        q.setId("q1");
        q.setType("fill");
        q.setAnswer("Spring");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        when(wqMapper.insert(any())).thenReturn(1);
        when(attemptMapper.insert(any())).thenReturn(1);

        Map<String, String> answers = Map.of("q1", "Spring");
        var result = examService.submit("e1", "u1", answers);

        assertEquals(1, result.get("score"));
    }

    // ========== 判断题判分 ==========

    @Test
    void submitTrueFalseCorrectAnswerScores() {
        Question q = new Question();
        q.setId("q1");
        q.setType("truefalse");
        q.setAnswer("正确");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        when(wqMapper.insert(any())).thenReturn(1);
        when(attemptMapper.insert(any())).thenReturn(1);

        Map<String, String> answers = Map.of("q1", "正确");
        var result = examService.submit("e1", "u1", answers);

        assertEquals(1, result.get("score"));
    }

    @Test
    void submitTrueFalseWrongAnswerNoScore() {
        Question q = new Question();
        q.setId("q1");
        q.setType("truefalse");
        q.setAnswer("错误");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        when(wqMapper.insert(any())).thenReturn(1);
        when(attemptMapper.insert(any())).thenReturn(1);

        Map<String, String> answers = Map.of("q1", "正确");
        var result = examService.submit("e1", "u1", answers);

        assertEquals(0, result.get("score"));
    }

    // ========== 简答题：无采分点，精确匹配 ==========

    @Test
    void submitShortAnswerNoKeywordsExactMatchScores() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("Java是面向对象语言");
        q.setExplanation("基础概念题"); // 无【采分点】

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        when(wqMapper.insert(any())).thenReturn(1);
        when(attemptMapper.insert(any())).thenReturn(1);

        Map<String, String> answers = Map.of("q1", "Java是面向对象语言");
        var result = examService.submit("e1", "u1", answers);

        assertEquals(1, result.get("score"));
    }

    @Test
    void submitShortAnswerNoKeywordsWrongNoScore() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("多态");
        q.setExplanation("OOP概念"); // 无【采分点】

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        when(wqMapper.insert(any())).thenReturn(1);
        when(attemptMapper.insert(any())).thenReturn(1);

        Map<String, String> answers = Map.of("q1", "封装");
        var result = examService.submit("e1", "u1", answers);

        assertEquals(0, result.get("score"));
    }

    // ========== 简答题：带采分点的模糊匹配 ==========

    @Test
    void submitShortAnswerAllKeywordsHit() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("面向对象三大特性");
        q.setExplanation("OOP的核心。【采分点】封装、继承、多态");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        when(wqMapper.insert(any())).thenReturn(1);
        when(attemptMapper.insert(any())).thenReturn(1);

        Map<String, String> answers = Map.of("q1", "Java的封装、继承、多态是三大特性");
        var result = examService.submit("e1", "u1", answers);

        assertEquals(1, result.get("score"));
        @SuppressWarnings("unchecked")
        Map<String, Object> graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> g = (Map<String, Object>) graded.get("q1");
        assertTrue((Boolean) g.get("correct"));
        assertEquals(1.0, g.get("hitRatio"));
    }

    @Test
    void submitShortAnswerAboveThresholdScores() {
        // 5个关键词，命中3个 = 60%，刚好等于阈值，得分
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("Spring核心");
        q.setExplanation("Spring框架核心概念。【采分点】IOC、DI、AOP、Bean、容器");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        when(wqMapper.insert(any())).thenReturn(1);
        when(attemptMapper.insert(any())).thenReturn(1);

        // 只命中3个：IOC、DI、Bean = 3/5 = 60%
        Map<String, String> answers = Map.of("q1", "Spring的IOC容器通过DI管理Bean生命周期");
        var result = examService.submit("e1", "u1", answers);

        assertEquals(1, result.get("score"));
        @SuppressWarnings("unchecked")
        Map<String, Object> graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> g = (Map<String, Object>) graded.get("q1");
        assertTrue((Boolean) g.get("correct"));
        assertTrue((Boolean) g.get("isFuzzyMatch"));
        assertEquals(0.6, g.get("hitRatio"));
    }

    @Test
    void submitShortAnswerBelowThresholdNoScore() {
        // 5个关键词，仅命中2个 = 40% < 60%，不得分
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("Spring核心");
        q.setExplanation("【采分点】IOC、DI、AOP、Bean、容器");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        when(wqMapper.insert(any())).thenReturn(1);
        when(attemptMapper.insert(any())).thenReturn(1);

        // 只命中2个：IOC、DI = 2/5 = 40%
        Map<String, String> answers = Map.of("q1", "Spring的核心是IOC和DI机制");
        var result = examService.submit("e1", "u1", answers);

        assertEquals(0, result.get("score"));
        @SuppressWarnings("unchecked")
        Map<String, Object> graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> g = (Map<String, Object>) graded.get("q1");
        assertFalse((Boolean) g.get("correct"));
        assertEquals(0.4, g.get("hitRatio"));
    }

    // ========== 关键词解析：分隔符多样性 ==========

    @Test
    void submitShortAnswerKeywordsSeparatedByComma() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("List实现类");
        q.setExplanation("常见List实现。【采分点】ArrayList,LinkedList,Vector");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        when(wqMapper.insert(any())).thenReturn(1);
        when(attemptMapper.insert(any())).thenReturn(1);

        Map<String, String> answers = Map.of("q1", "ArrayList和LinkedList是常用的List实现");
        var result = examService.submit("e1", "u1", answers);

        assertEquals(1, result.get("score")); // 2/3 = 66.7% >= 60%
    }

    @Test
    void submitShortAnswerKeywordsWithChineseComma() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("HTTP方法");
        q.setExplanation("常用HTTP方法。【采分点】GET，POST，PUT，DELETE");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        when(wqMapper.insert(any())).thenReturn(1);
        when(attemptMapper.insert(any())).thenReturn(1);

        Map<String, String> answers = Map.of("q1", "GET和POST是最常用的HTTP方法");
        var result = examService.submit("e1", "u1", answers);

        assertEquals(0.5, ((Map<?, ?>) ((Map<?, ?>) result.get("answers")).get("q1")).get("hitRatio"));
        assertEquals(0, result.get("score")); // 2/4 = 50% < 60%
    }

    // ========== 模糊匹配：字符覆盖率 ==========

    @Test
    void submitShortAnswerFuzzyByCharCoverage() {
        // 关键词长度>6时禁用字符覆盖率匹配，只靠子串包含
        // 这里测试短关键词的字符覆盖率："数据隐藏" vs "信息隐藏" 共享 "隐藏"
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("封装");
        q.setExplanation("【采分点】封装、数据隐藏、访问控制");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        when(wqMapper.insert(any())).thenReturn(1);
        when(attemptMapper.insert(any())).thenReturn(1);

        // "信息隐藏" 命中 "数据隐藏" 的 "隐藏"二字 = 2/4 = 50% 字符覆盖 -> 命中
        // 同时 "封装" 子串直接包含 -> 命中
        // 总计 2/3 = 66.7%
        Map<String, String> answers = Map.of("q1", "封装的目的是实现信息隐藏和访问控制");
        var result = examService.submit("e1", "u1", answers);

        assertEquals(1, result.get("score"));
    }

    @Test
    void submitShortAnswerLongKeywordNoCharCoverage() {
        // 关键词长度>6时字符覆盖率不生效
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("Spring依赖注入");
        q.setExplanation("【采分点】依赖注入方式、构造器注入");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        when(wqMapper.insert(any())).thenReturn(1);
        when(attemptMapper.insert(any())).thenReturn(1);

        // 虽然共享"注入"两字，但关键词长度>6，字符覆盖率不生效，只靠子串包含
        // "依赖注入" vs "构造器注入" - 都包含"注入"
        Map<String, String> answers = Map.of("q1", "Spring常用的有构造器注入方式");
        var result = examService.submit("e1", "u1", answers);

        // "构造器注入" 子串直接包含 -> 命中
        // "依赖注入方式" - "方式" 子串包含？用户答案没有"方式"或"依赖"或"依赖注入方式"
        // 实际上用户答案有"构造器注入"命中第2个关键词
        // 50% < 60%，不得分
        assertEquals(0, result.get("score"));
    }

    // ========== 采分点解析：行尾截断 ==========

    @Test
    void submitShortAnswerKeywordsTruncatedAtNewline() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("数据库ACID");
        q.setExplanation("""
            ACID是数据库事务的四大特性。
            【采分点】原子性、一致性、隔离性、持久性
            这些特性保证了事务的可靠性。
            """);

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        when(wqMapper.insert(any())).thenReturn(1);
        when(attemptMapper.insert(any())).thenReturn(1);

        // 3/4 = 75%
        Map<String, String> answers = Map.of("q1", "事务的原子性、一致性和隔离性很重要");
        var result = examService.submit("e1", "u1", answers);

        assertEquals(1, result.get("score"));
    }

    // ========== 空答案 / 未作答 ==========

    @Test
    void submitEmptyAnswerNoScore() {
        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setAnswer("A");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        when(wqMapper.insert(any())).thenReturn(1);
        when(attemptMapper.insert(any())).thenReturn(1);

        Map<String, String> answers = Map.of("q1", "");
        var result = examService.submit("e1", "u1", answers);

        assertEquals(0, result.get("score"));
    }

    @Test
    void submitMissingAnswerNoScore() {
        Question q1 = new Question();
        q1.setId("q1");
        q1.setType("choice");
        q1.setAnswer("A");
        Question q2 = new Question();
        q2.setId("q2");
        q2.setType("choice");
        q2.setAnswer("B");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q1, q2));
        when(wqMapper.insert(any())).thenReturn(1);
        when(attemptMapper.insert(any())).thenReturn(1);

        // 只答了q2
        Map<String, String> answers = Map.of("q2", "B");
        var result = examService.submit("e1", "u1", answers);

        assertEquals(1, result.get("score"));
    }

    // ========== 错题本写入 ==========

    @Test
    void submitWrongAnswerWritesToWrongQuestionBook() {
        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setAnswer("A");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        when(attemptMapper.insert(any())).thenReturn(1);

        Map<String, String> answers = Map.of("q1", "C");
        examService.submit("e1", "u1", answers);

        verify(wqMapper).insert(argThat(wq ->
            "u1_q1".equals(wq.getId())
                && "u1".equals(wq.getUserId())
                && "q1".equals(wq.getQuestionId())
                && "e1".equals(wq.getExamId())
                && "C".equals(wq.getUserAnswer())
                && Boolean.FALSE.equals(wq.getMastered())
                && Integer.valueOf(0).equals(wq.getReviewCount())
        ));
    }

    @Test
    void submitCorrectAnswerDoesNotWriteWrongQuestion() {
        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setAnswer("A");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        when(attemptMapper.insert(any())).thenReturn(1);

        Map<String, String> answers = Map.of("q1", "A");
        examService.submit("e1", "u1", answers);

        verify(wqMapper, never()).insert(any());
    }

    @Test
    void submitDuplicateWrongQuestionInsertFailureIgnored() {
        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setAnswer("A");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        when(wqMapper.insert(any())).thenThrow(new RuntimeException("duplicate key"));
        when(attemptMapper.insert(any())).thenReturn(1);

        Map<String, String> answers = Map.of("q1", "C");
        // 不应抛出异常，错题本插入失败被吞掉
        assertDoesNotThrow(() -> examService.submit("e1", "u1", answers));
    }

    // ========== 考试记录持久化 ==========

    @Test
    void submitPersistsExamAttempt() {
        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setAnswer("A");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        when(wqMapper.insert(any())).thenReturn(1);

        Map<String, String> answers = Map.of("q1", "A");
        var result = examService.submit("e1", "u1", answers);

        verify(attemptMapper).insert(argThat(attempt ->
            "e1".equals(attempt.getExamId())
                && "u1".equals(attempt.getUserId())
                && Integer.valueOf(1).equals(attempt.getScore())
                && Integer.valueOf(1).equals(attempt.getTotal())
        ));
        assertNotNull(result.get("id"));
    }

    @Test
    void submitAttemptInsertFailureDoesNotThrow() {
        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setAnswer("A");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        when(wqMapper.insert(any())).thenReturn(1);
        when(attemptMapper.insert(any())).thenThrow(new RuntimeException("db error"));

        Map<String, String> answers = Map.of("q1", "A");
        assertDoesNotThrow(() -> examService.submit("e1", "u1", answers));
    }

    // ========== 混合题型综合判分 ==========

    @Test
    void submitMixedQuestionTypesGradesCorrectly() {
        Question q1 = new Question(); q1.setId("q1"); q1.setType("choice"); q1.setAnswer("A");
        Question q2 = new Question(); q2.setId("q2"); q2.setType("fill"); q2.setAnswer("JSON");
        Question q3 = new Question(); q3.setId("q3"); q3.setType("truefalse"); q3.setAnswer("正确");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q1, q2, q3));
        when(wqMapper.insert(any())).thenReturn(1);
        when(attemptMapper.insert(any())).thenReturn(1);

        // q1正确，q2正确，q3错误 -> 得2分
        Map<String, String> answers = Map.of(
            "q1", "A",
            "q2", "JSON",
            "q3", "错误"
        );
        var result = examService.submit("e1", "u1", answers);

        assertEquals(2, result.get("score"));
        assertEquals(3, result.get("total"));
    }

    // ========== 列表/删除权限校验 ==========

    @Test
    void getExamWithWrongUserThrows() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("owner");

        when(examMapper.findById("e1")).thenReturn(exam);

        assertThrows(RuntimeException.class,
            () -> examService.get("e1", "otheruser"));
    }

    @Test
    void getExamWithOwnerReturnsData() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("owner");
        exam.setTitle("Test Exam");

        when(examMapper.findById("e1")).thenReturn(exam);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of());

        var result = examService.get("e1", "owner");

        assertNotNull(result);
        assertEquals("e1", result.get("id"));
    }

    @Test
    void deleteExamWithWrongUserThrows() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("owner");

        when(examMapper.findById("e1")).thenReturn(exam);

        assertThrows(RuntimeException.class,
            () -> examService.delete("e1", "otheruser"));
    }

    @Test
    void deleteExamNotFoundThrows() {
        when(examMapper.findById("e1")).thenReturn(null);

        assertThrows(RuntimeException.class,
            () -> examService.delete("e1", "anyuser"));
    }

    @Test
    void deleteExamOwnerSucceeds() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("owner");

        when(examMapper.findById("e1")).thenReturn(exam);
        doNothing().when(examMapper).deleteById("e1");

        assertDoesNotThrow(() -> examService.delete("e1", "owner"));
        verify(examMapper).deleteById("e1");
    }
}
