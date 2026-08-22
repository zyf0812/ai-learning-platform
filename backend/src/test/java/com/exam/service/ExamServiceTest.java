package com.exam.service;

import com.exam.mapper.*;
import com.exam.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

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
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        examService = new ExamService(examMapper, questionMapper, docMapper,
            deepSeek, ragService, wqMapper, attemptMapper, taskQueue);
    }

    // ===== list / get / delete 权限校验 =====

    @Test
    void getExamWithWrongUserIdThrows() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("owner");
        exam.setTitle("test");
        exam.setQuestionTypes("choice");
        exam.setQuestionCount(1);

        when(examMapper.findById("e1")).thenReturn(exam);

        var ex = assertThrows(RuntimeException.class, () -> examService.get("e1", "hacker"));
        assertTrue(ex.getMessage().contains("无权访问"));
    }

    @Test
    void getExamNotFoundReturnsNull() {
        when(examMapper.findById("missing")).thenReturn(null);
        assertNull(examService.get("missing", "anyuser"));
    }

    @Test
    void deleteWithWrongUserIdThrows() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("owner");

        when(examMapper.findById("e1")).thenReturn(exam);

        var ex = assertThrows(RuntimeException.class, () -> examService.delete("e1", "hacker"));
        assertTrue(ex.getMessage().contains("无权删除"));
    }

    @Test
    void deleteNonExistentExamThrows() {
        when(examMapper.findById("missing")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class, () -> examService.delete("missing", "any"));
        assertEquals("考试不存在", ex.getMessage());
    }

    @Test
    void deleteOwnExamSucceeds() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("owner");

        when(examMapper.findById("e1")).thenReturn(exam);
        doNothing().when(examMapper).deleteById("e1");

        assertDoesNotThrow(() -> examService.delete("e1", "owner"));
        verify(examMapper).deleteById("e1");
    }

    // ===== parseKeywords 私有方法测试 (反射) =====

    @Test
    void parseKeywordsExtractsList() {
        String expl = "这是解析。【采分点】封装、继承、多态";
        @SuppressWarnings("unchecked")
        List<String> kws = (List<String>) ReflectionTestUtils.invokeMethod(examService, "parseKeywords", expl);
        assertNotNull(kws);
        assertEquals(3, kws.size());
        assertTrue(kws.contains("封装"));
        assertTrue(kws.contains("继承"));
        assertTrue(kws.contains("多态"));
    }

    @Test
    void parseKeywordsWithMixedSeparators() {
        String expl = "说明文字【采分点】Spring, IoC ，AOP、依赖注入\n后续内容被忽略";
        @SuppressWarnings("unchecked")
        List<String> kws = (List<String>) ReflectionTestUtils.invokeMethod(examService, "parseKeywords", expl);
        assertNotNull(kws);
        assertEquals(4, kws.size());
        assertTrue(kws.contains("Spring"));
        assertTrue(kws.contains("IoC"));
        assertTrue(kws.contains("AOP"));
        assertTrue(kws.contains("依赖注入"));
    }

    @Test
    void parseKeywordsNoMarkerReturnsEmpty() {
        String expl = "普通解析没有采分点标记";
        @SuppressWarnings("unchecked")
        List<String> kws = (List<String>) ReflectionTestUtils.invokeMethod(examService, "parseKeywords", expl);
        assertTrue(kws.isEmpty());
    }

    @Test
    void parseKeywordsNullReturnsEmpty() {
        @SuppressWarnings("unchecked")
        List<String> kws = (List<String>) ReflectionTestUtils.invokeMethod(examService, "parseKeywords", (String) null);
        assertTrue(kws.isEmpty());
    }

    @Test
    void parseKeywordsBlankReturnsEmpty() {
        @SuppressWarnings("unchecked")
        List<String> kws = (List<String>) ReflectionTestUtils.invokeMethod(examService, "parseKeywords", "   ");
        assertTrue(kws.isEmpty());
    }

    // ===== keywordMatch 私有方法测试 =====

    @Test
    void keywordMatchExactSubstring() {
        Boolean result = ReflectionTestUtils.invokeMethod(examService, "keywordMatch", "java是面向对象语言", "面向对象");
        assertTrue(result);
    }

    @Test
    void keywordMatchNoMatchShortKeyword() {
        Boolean result = ReflectionTestUtils.invokeMethod(examService, "keywordMatch", "hello world", "abc");
        assertFalse(result);
    }

    @Test
    void keywordMatchCoverageBasedForLongerKeyword() {
        // 关键词 > 6 字符时不使用覆盖率回退，直接返回 false
        Boolean result1 = ReflectionTestUtils.invokeMethod(examService, "keywordMatch", "Spring的核心是IoC容器", "数据封装");
        assertFalse(result1);

        // 关键词 <= 6 字符时使用覆盖率：用户答案包含 "封装"
        Boolean result2 = ReflectionTestUtils.invokeMethod(examService, "keywordMatch", "面向对象有封装特性", "数据隐藏");
        // "数据隐藏" -> 字符集 {数,据,隐,藏} ，在 "面向对象有封装特性" 中... 没有这些字符，覆盖率<50%
        // 换一个有共享字符的
        Boolean result3 = ReflectionTestUtils.invokeMethod(examService, "keywordMatch", "数据隐藏是重要概念", "信息隐藏");
        // "信息隐藏" 字符集 {信,息,隐,藏} vs 文本含 "隐","藏" -> 覆盖率 2/4 = 50% >= 0.5
        assertTrue(result3);
    }

    // ===== submit 评分：选择题 =====

    @Test
    void submitChoiceQuestionCorrect() {
        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setAnswer("B");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        Map<String, String> answers = Map.of("q1", "B");
        var result = examService.submit("e1", "u1", answers);

        assertEquals(1, result.get("score"));
        assertEquals(1, result.get("total"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertTrue((Boolean) graded.get("q1").get("correct"));
    }

    @Test
    void submitChoiceQuestionWrongCreatesWrongQuestion() {
        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setAnswer("A");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        Map<String, String> answers = Map.of("q1", "C");
        examService.submit("e1", "u1", answers);

        verify(wqMapper).insert(any(WrongQuestion.class));
    }

    // ===== submit 评分：填空题精确匹配 =====

    @Test
    void submitFillBlankWhitespaceTrimmed() {
        Question q = new Question();
        q.setId("q1");
        q.setType("fill");
        q.setAnswer("Spring");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        Map<String, String> answers = Map.of("q1", "  Spring  ");
        var result = examService.submit("e1", "u1", answers);

        assertEquals(1, result.get("score"));
    }

    // ===== submit 评分：判断题 =====

    @Test
    void submitTrueFalseCorrect() {
        Question q = new Question();
        q.setId("q1");
        q.setType("truefalse");
        q.setAnswer("正确");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        Map<String, String> answers = Map.of("q1", "正确");
        var result = examService.submit("e1", "u1", answers);
        assertEquals(1, result.get("score"));
    }

    // ===== submit 评分：简答题模糊匹配（采分点） =====

    @Test
    void submitShortAnswerAllKeywordsHit() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("面向对象三大特性");
        q.setExplanation("答：封装、继承、多态\n【采分点】封装、继承、多态");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        Map<String, String> answers = Map.of("q1", "Java有封装、继承、多态三大特性");
        var result = examService.submit("e1", "u1", answers);

        assertEquals(1, result.get("score"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertTrue((Boolean) graded.get("q1").get("correct"));
    }

    @Test
    void submitShortAnswerPartialHitAboveThreshold() {
        // 3 个关键词命中 2 个 = 66.7% >= 60% 阈值 → 正确
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("三大特性");
        q.setExplanation("说明【采分点】封装、继承、多态");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        Map<String, String> answers = Map.of("q1", "面向对象包含封装和继承两个重要特性");
        var result = examService.submit("e1", "u1", answers);

        assertEquals(1, result.get("score"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertTrue((Boolean) graded.get("q1").get("correct"));
        assertTrue((Boolean) graded.get("q1").get("isFuzzyMatch"));
        assertEquals(0.67, graded.get("q1").get("hitRatio"));
    }

    @Test
    void submitShortAnswerPartialHitBelowThreshold() {
        // 3 个关键词命中 1 个 = 33% < 60% → 错误
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("三大特性");
        q.setExplanation("说明【采分点】封装、继承、多态");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        Map<String, String> answers = Map.of("q1", "Java有封装的特性");
        var result = examService.submit("e1", "u1", answers);

        assertEquals(0, result.get("score"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertFalse((Boolean) graded.get("q1").get("correct"));
        verify(wqMapper).insert(any(WrongQuestion.class));
    }

    @Test
    void submitShortAnswerNoKeywordsFallsBackToExactMatch() {
        // 无【采分点】标记 → 精确匹配
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("标准答案文本");
        q.setExplanation("普通解析无采分点");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        // 精确匹配 → 正确
        var r1 = examService.submit("e1", "u1", Map.of("q1", "标准答案文本"));
        assertEquals(1, r1.get("score"));

        // 近似但不精确 → 错误
        var r2 = examService.submit("e1", "u2", Map.of("q1", "接近标准答案文本"));
        assertEquals(0, r2.get("score"));
    }

    // ===== submit: 空答案处理 =====

    @Test
    void submitMissingAnswerTreatedAsEmpty() {
        Question q = new Question();
        q.setId("q1");
        q.setType("fill");
        q.setAnswer("Spring");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        // 用户未提供该题答案
        Map<String, String> answers = Map.of();
        var result = examService.submit("e1", "u1", answers);

        assertEquals(0, result.get("score"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertEquals("", graded.get("q1").get("userAnswer"));
    }

    // ===== submit: 错题本冲突忽略 =====

    @Test
    void submitWrongQuestionDuplicateIsIgnored() {
        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setAnswer("A");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));
        doThrow(new RuntimeException("duplicate key")).when(wqMapper).insert(any(WrongQuestion.class));

        // 不应因错题本冲突而抛出异常
        assertDoesNotThrow(() -> examService.submit("e1", "u1", Map.of("q1", "B")));
    }
}
