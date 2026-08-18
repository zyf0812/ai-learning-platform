package com.exam.service;

import com.exam.mapper.*;
import com.exam.model.Question;
import com.exam.model.WrongQuestion;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 专注测试 ExamService 的评分/答题核心逻辑：
 *  - 选择题/填空题/判断题 精确匹配
 *  - 简答题采分点解析 + 模糊匹配
 *  - 错题本写入
 *  - 边界条件（空答案、多余空格、大小写）
 */
@ExtendWith(MockitoExtension.class)
class ExamServiceGradingTest {

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

    // ===== helper: 构造 Question =====
    private Question mkQuestion(String id, String type, String content,
                                String answer, String explanation, String options) {
        Question q = new Question();
        q.setId(id);
        q.setType(type);
        q.setContent(content);
        q.setAnswer(answer);
        q.setExplanation(explanation == null ? "" : explanation);
        q.setOptions(options == null ? "[]" : options);
        return q;
    }

    // ========== 选择题（精确匹配） ==========

    @Test
    void submitChoiceCorrectAnswer() {
        Question q = mkQuestion("q1", "choice", "Java之父？", "B",
            "James Gosling",
            "[\"A|Bill Gates\",\"B|James Gosling\",\"C|Linus\",\"D|Guido\"]");
        when(questionMapper.findByExamId("ex1")).thenReturn(List.of(q));

        Map<String, String> answers = Map.of("q1", "B");
        var result = examService.submit("ex1", "u1", answers);

        assertEquals(1, result.get("score"));
        assertEquals(1, result.get("total"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        var g = (Map<String, Object>) graded.get("q1");
        assertTrue((Boolean) g.get("correct"));
        assertEquals(1.0, g.get("hitRatio"));
        assertFalse((Boolean) g.get("isFuzzyMatch"));
        verify(wqMapper, never()).insert(any());
    }

    @Test
    void submitChoiceWrongAnswer() {
        Question q = mkQuestion("q1", "choice", "Java之父？", "B", "",
            "[\"A|Bill\",\"B|James\"]");
        when(questionMapper.findByExamId("ex1")).thenReturn(List.of(q));

        var result = examService.submit("ex1", "u1", Map.of("q1", "A"));

        assertEquals(0, result.get("score"));
        verify(wqMapper).insert(any(WrongQuestion.class));
    }

    @Test
    void submitChoiceAnswerWithSpacesTrimmed() {
        Question q = mkQuestion("q1", "choice", "T?", "A", "", "[]");
        when(questionMapper.findByExamId("ex1")).thenReturn(List.of(q));

        // 用户答案前后有空格
        var result = examService.submit("ex1", "u1", Map.of("q1", " A "));
        assertEquals(1, result.get("score"));
    }

    // ========== 填空题（精确匹配） ==========

    @Test
    void submitFillBlankCorrect() {
        Question q = mkQuestion("q1", "fill",
            "___是Java的核心IOC框架", "Spring", "", "[]");
        when(questionMapper.findByExamId("ex1")).thenReturn(List.of(q));

        var result = examService.submit("ex1", "u1", Map.of("q1", "Spring"));
        assertEquals(1, result.get("score"));
    }

    @Test
    void submitFillBlankCaseSensitive() {
        Question q = mkQuestion("q1", "fill", "框架", "Spring", "", "[]");
        when(questionMapper.findByExamId("ex1")).thenReturn(List.of(q));

        // 大小写不同 → 判错
        var result = examService.submit("ex1", "u1", Map.of("q1", "spring"));
        assertEquals(0, result.get("score"));
    }

    // ========== 判断题（精确匹配） ==========

    @Test
    void submitTrueFalseCorrect() {
        Question q = mkQuestion("q1", "truefalse",
            "Java是编译型语言", "正确", "", "[]");
        when(questionMapper.findByExamId("ex1")).thenReturn(List.of(q));

        var result = examService.submit("ex1", "u1", Map.of("q1", "正确"));
        assertEquals(1, result.get("score"));
    }

    @Test
    void submitTrueFalseWrongChineseWord() {
        Question q = mkQuestion("q1", "truefalse", "T?", "正确", "", "[]");
        when(questionMapper.findByExamId("ex1")).thenReturn(List.of(q));

        var result = examService.submit("ex1", "u1", Map.of("q1", "错误"));
        assertEquals(0, result.get("score"));
    }

    // ========== 简答题：精确匹配（无采分点） ==========

    @Test
    void submitShortAnswerNoKeywordsFallbackExactMatch() {
        Question q = mkQuestion("q1", "shortanswer",
            "简述OSI七层", "应用层、表示层、会话层、传输层、网络层、链路层、物理层",
            "标准七层模型。", "[]"); // 无【采分点】
        when(questionMapper.findByExamId("ex1")).thenReturn(List.of(q));

        // 完全匹配 → 正确
        var r = examService.submit("ex1", "u1",
            Map.of("q1", "应用层、表示层、会话层、传输层、网络层、链路层、物理层"));
        assertEquals(1, r.get("score"));

        // 不匹配 → 错误
        var r2 = examService.submit("ex1", "u1",
            Map.of("q1", "只有五层"));
        assertEquals(0, r2.get("score"));
    }

    // ========== 简答题：采分点模糊匹配 ==========

    @Test
    void submitShortAnswerFuzzyAllKeywordsHit() {
        Question q = mkQuestion("q1", "shortanswer",
            "什么是面向对象？", "自己组织语言",
            "面向对象是一种编程范式。【采分点】封装、继承、多态、抽象", "[]");
        when(questionMapper.findByExamId("ex1")).thenReturn(List.of(q));

        var r = examService.submit("ex1", "u1", Map.of("q1",
            "面向对象的四大特性：封装、继承、多态和抽象"));
        assertEquals(1, r.get("score"));
        @SuppressWarnings("unchecked")
        var g = (Map<String, Object>) ((Map<?, ?>) r.get("answers")).get("q1");
        assertEquals(1.0, g.get("hitRatio"));
    }

    @Test
    void submitShortAnswerFuzzyExactlyAtThreshold() {
        // 5 个采分点，命中 3 个 = 60% → 正好达到阈值 0.6 → 正确
        Question q = mkQuestion("q1", "shortanswer", "T?", "A",
            "X.【采分点】封装、继承、多态、抽象、组合", "[]");
        when(questionMapper.findByExamId("ex1")).thenReturn(List.of(q));

        var r = examService.submit("ex1", "u1",
            Map.of("q1", "答案里有封装 继承 多态")); // 3/5 = 0.6
        assertEquals(1, r.get("score"));
        @SuppressWarnings("unchecked")
        var g = (Map<String, Object>) ((Map<?, ?>) r.get("answers")).get("q1");
        assertEquals(0.6, g.get("hitRatio"));
        assertTrue((Boolean) g.get("isFuzzyMatch"));
    }

    @Test
    void submitShortAnswerFuzzyBelowThreshold() {
        // 5 个采分点，命中 2 个 = 40% < 0.6 → 错误
        Question q = mkQuestion("q1", "shortanswer", "T?", "A",
            "X.【采分点】封装、继承、多态、抽象、组合", "[]");
        when(questionMapper.findByExamId("ex1")).thenReturn(List.of(q));

        var r = examService.submit("ex1", "u1",
            Map.of("q1", "只提到了封装和继承")); // 2/5 = 0.4
        assertEquals(0, r.get("score"));
    }

    @Test
    void submitShortAnswerEmptyAnswerZeroHits() {
        Question q = mkQuestion("q1", "shortanswer", "T?", "A",
            "X.【采分点】封装、继承、多态", "[]");
        when(questionMapper.findByExamId("ex1")).thenReturn(List.of(q));

        var r = examService.submit("ex1", "u1", Map.of("q1", ""));
        assertEquals(0, r.get("score"));
    }

    // ========== 简答题：通过反射测试 parseKeywords 私有方法 ==========

    @Test
    void parseKeywordsNormal() throws Exception {
        Method m = ExamService.class.getDeclaredMethod("parseKeywords", String.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        var kws = (List<String>) m.invoke(examService,
            "解释。【采分点】封装、继承、多态、抽象");
        assertEquals(List.of("封装", "继承", "多态", "抽象"), kws);
    }

    @Test
    void parseKeywordsMixedSeparators() throws Exception {
        Method m = ExamService.class.getDeclaredMethod("parseKeywords", String.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        var kws = (List<String>) m.invoke(examService,
            "X.【采分点】封装,继承 多态，抽象");
        assertEquals(List.of("封装", "继承", "多态", "抽象"), kws);
    }

    @Test
    void parseKeywordsStopsAtNewline() throws Exception {
        Method m = ExamService.class.getDeclaredMethod("parseKeywords", String.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        var kws = (List<String>) m.invoke(examService,
            "X.【采分点】封装、继承\n后续内容不应被解析");
        assertEquals(List.of("封装", "继承"), kws);
    }

    @Test
    void parseKeywordsNoMarkerEmpty() throws Exception {
        Method m = ExamService.class.getDeclaredMethod("parseKeywords", String.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        var kws = (List<String>) m.invoke(examService, "没有采分点的解释");
        assertTrue(kws.isEmpty());
    }

    @Test
    void parseKeywordsNullBlankEmpty() throws Exception {
        Method m = ExamService.class.getDeclaredMethod("parseKeywords", String.class);
        m.setAccessible(true);
        assertTrue(((List<?>) m.invoke(examService, (String) null)).isEmpty());
        assertTrue(((List<?>) m.invoke(examService, "")).isEmpty());
        assertTrue(((List<?>) m.invoke(examService, "   ")).isEmpty());
    }

    @Test
    void parseKeywordsMarkerAtEndNoContent() throws Exception {
        Method m = ExamService.class.getDeclaredMethod("parseKeywords", String.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        var kws = (List<String>) m.invoke(examService, "解释。【采分点】");
        assertTrue(kws.isEmpty());
    }

    // ========== 简答题：通过反射测试 keywordMatch 私有方法 ==========

    @Test
    void keywordMatchSubstringContains() throws Exception {
        Method m = ExamService.class.getDeclaredMethod("keywordMatch", String.class, String.class);
        m.setAccessible(true);
        assertTrue((Boolean) m.invoke(examService, "Java具有封装特性", "封装"));
    }

    @Test
    void keywordMatchCharacterCoverageAboveHalf() throws Exception {
        Method m = ExamService.class.getDeclaredMethod("keywordMatch", String.class, String.class);
        m.setAccessible(true);
        // "数据隐藏" vs "信息隐藏" → 共享"隐藏"两字 + 覆 >= 0.5
        // keyword = "数据隐藏" (4字)，用户答案有 "隐藏" (2/4=0.5) → true
        assertTrue((Boolean) m.invoke(examService, "信息隐藏是OOP特性", "数据隐藏"));
    }

    @Test
    void keywordMatchCharacterCoverageBelowHalf() throws Exception {
        Method m = ExamService.class.getDeclaredMethod("keywordMatch", String.class, String.class);
        m.setAccessible(true);
        // keyword="封装继承" (4字)，用户答案只有"封装"(2字) = 恰好 0.5 → 也是 true
        // 这里让 1/4 = 0.25 < 0.5 → false
        assertFalse((Boolean) m.invoke(examService, "提到了一个封字", "封装继承"));
    }

    @Test
    void keywordMatchLongKeywordNoCoverageFallback() throws Exception {
        Method m = ExamService.class.getDeclaredMethod("keywordMatch", String.class, String.class);
        m.setAccessible(true);
        // keyword 长度 > 6 时字符覆盖率分支不触发，仅依靠子串包含
        String longKw = "一二三四五六七八";
        assertFalse((Boolean) m.invoke(examService, "只有一二字", longKw));
        assertTrue((Boolean) m.invoke(examService, "包含一二三四五六七八全部字", longKw));
    }

    // ========== 错题本 & 考试记录持久化 ==========

    @Test
    void submitWrongQuestionsWrittenToWrongBook() {
        Question q1 = mkQuestion("q1", "choice", "T1", "A", "", "[]");
        Question q2 = mkQuestion("q2", "choice", "T2", "B", "", "[]");
        when(questionMapper.findByExamId("ex1")).thenReturn(List.of(q1, q2));

        examService.submit("ex1", "u1", Map.of("q1", "A", "q2", "C")); // q2 错

        var captor = ArgumentCaptor.forClass(WrongQuestion.class);
        verify(wqMapper, times(1)).insert(captor.capture());
        WrongQuestion wq = captor.getValue();
        assertEquals("u1_q2", wq.getId());
        assertEquals("u1", wq.getUserId());
        assertEquals("q2", wq.getQuestionId());
        assertEquals("C", wq.getUserAnswer());
        assertFalse(wq.getMastered());
        assertEquals(0, wq.getReviewCount());
    }

    @Test
    void submitSameWrongQuestionTwiceDoesNotFail() {
        Question q1 = mkQuestion("q1", "choice", "T", "A", "", "[]");
        when(questionMapper.findByExamId("ex1")).thenReturn(List.of(q1));
        // 第二次写入重复主键会抛异常，service 应该忽略
        doThrow(new RuntimeException("主键冲突")).when(wqMapper).insert(any());

        assertDoesNotThrow(() -> examService.submit("ex1", "u1", Map.of("q1", "B")));
    }

    @Test
    void submitAttemptSavedWithScore() {
        Question q = mkQuestion("q1", "choice", "T", "A", "", "[]");
        when(questionMapper.findByExamId("ex1")).thenReturn(List.of(q));

        var result = examService.submit("ex1", "u1", Map.of("q1", "A"));

        verify(attemptMapper).insert(any());
        assertNotNull(result.get("id"));
        assertEquals(1, result.get("score"));
        assertEquals(1, result.get("total"));
    }

    // ========== 未回答的题目 ==========

    @Test
    void submitMissingAnswerCountsAsEmptyWrong() {
        Question q1 = mkQuestion("q1", "choice", "T", "A", "", "[]");
        Question q2 = mkQuestion("q2", "fill", "F", "Spring", "", "[]");
        when(questionMapper.findByExamId("ex1")).thenReturn(List.of(q1, q2));

        // 只回答 q1，q2 未作答
        var r = examService.submit("ex1", "u1", Map.of("q1", "A"));
        assertEquals(1, r.get("score"));
        assertEquals(2, r.get("total"));
    }

    // ========== get / delete 权限校验 ==========

    @Test
    void getExamWithWrongUserThrows() {
        var exam = new com.exam.model.Exam();
        exam.setId("ex1");
        exam.setUserId("owner");
        when(examMapper.findById("ex1")).thenReturn(exam);

        var ex = assertThrows(RuntimeException.class,
            () -> examService.get("ex1", "hacker"));
        assertTrue(ex.getMessage().contains("无权访问"));
    }

    @Test
    void getExamNotFoundReturnsNull() {
        when(examMapper.findById("nope")).thenReturn(null);
        assertNull(examService.get("nope", "u1"));
    }

    @Test
    void deleteExamNotFoundThrows() {
        when(examMapper.findById("nope")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class,
            () -> examService.delete("nope", "u1"));
        assertTrue(ex.getMessage().contains("考试不存在"));
    }

    @Test
    void deleteExamWrongUserThrows() {
        var exam = new com.exam.model.Exam();
        exam.setId("ex1");
        exam.setUserId("owner");
        when(examMapper.findById("ex1")).thenReturn(exam);

        var ex = assertThrows(RuntimeException.class,
            () -> examService.delete("ex1", "hacker"));
        assertTrue(ex.getMessage().contains("无权删除"));
    }
}
