package com.exam.service;

import com.exam.mapper.*;
import com.exam.model.Exam;
import com.exam.model.Question;
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
        examService = new ExamService(examMapper, questionMapper, docMapper,
                deepSeek, ragService, wqMapper, attemptMapper, taskQueue);
    }

    // --- 权限校验 ---

    @Test
    void getExamWithWrongUserThrowsForbidden() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("owner123");
        exam.setTitle("Test");
        exam.setQuestionTypes("choice");
        exam.setQuestionCount(1);

        when(examMapper.findById("e1")).thenReturn(exam);

        var ex = assertThrows(RuntimeException.class,
                () -> examService.get("e1", "other-user"));
        assertEquals("无权访问该考试", ex.getMessage());
    }

    @Test
    void getExamWithCorrectUserReturnsDetails() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("user1");
        exam.setTitle("Midterm");
        exam.setQuestionTypes("choice,fill");
        exam.setQuestionCount(2);

        Question q1 = new Question();
        q1.setId("q1");
        q1.setExamId("e1");
        q1.setType("choice");
        q1.setContent("What is Java?");

        when(examMapper.findById("e1")).thenReturn(exam);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q1));

        var result = examService.get("e1", "user1");

        assertNotNull(result);
        assertEquals("e1", result.get("id"));
        assertEquals("Midterm", result.get("title"));
        assertEquals(2, result.get("questionCount"));
        @SuppressWarnings("unchecked")
        List<Question> questions = (List<Question>) result.get("questions");
        assertEquals(1, questions.size());
        assertEquals("q1", questions.get(0).getId());
    }

    @Test
    void getNonExistentExamReturnsNull() {
        when(examMapper.findById("not-exist")).thenReturn(null);
        assertNull(examService.get("not-exist", "any-user"));
    }

    @Test
    void deleteExamWithWrongUserThrows() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("owner");

        when(examMapper.findById("e1")).thenReturn(exam);

        var ex = assertThrows(RuntimeException.class,
                () -> examService.delete("e1", "other"));
        assertEquals("无权删除该考试", ex.getMessage());
        verify(examMapper, never()).deleteById(anyString());
    }

    @Test
    void deleteNonExistentExamThrows() {
        when(examMapper.findById("e1")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
                () -> examService.delete("e1", "any"));
        assertEquals("考试不存在", ex.getMessage());
    }

    @Test
    void deleteExamByOwnerSucceeds() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("owner");

        when(examMapper.findById("e1")).thenReturn(exam);
        doNothing().when(examMapper).deleteById("e1");

        assertDoesNotThrow(() -> examService.delete("e1", "owner"));
        verify(examMapper).deleteById("e1");
    }

    // --- 考试提交 & 评分逻辑 ---

    private Question makeChoice(String id, String answer) {
        Question q = new Question();
        q.setId(id);
        q.setType("choice");
        q.setContent("选择题" + id);
        q.setOptions("[\"A|opt1\",\"B|opt2\"]");
        q.setAnswer(answer);
        q.setExplanation("标准解析");
        return q;
    }

    private Question makeFill(String id, String answer) {
        Question q = new Question();
        q.setId(id);
        q.setType("fill");
        q.setContent("填空题" + id);
        q.setAnswer(answer);
        q.setExplanation("解析");
        return q;
    }

    private Question makeTrueFalse(String id, String answer) {
        Question q = new Question();
        q.setId(id);
        q.setType("truefalse");
        q.setContent("判断题" + id);
        q.setAnswer(answer);
        q.setExplanation("解析");
        return q;
    }

    private Question makeShortAnswer(String id, String answer, String explanation) {
        Question q = new Question();
        q.setId(id);
        q.setType("shortanswer");
        q.setContent("简答题" + id);
        q.setAnswer(answer);
        q.setExplanation(explanation);
        return q;
    }

    @Test
    void submitAllCorrectReturnsFullScore() {
        Question q1 = makeChoice("q1", "A");
        Question q2 = makeFill("q2", "Spring");
        Question q3 = makeTrueFalse("q3", "正确");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q1, q2, q3));

        Map<String, String> answers = new HashMap<>();
        answers.put("q1", "A");
        answers.put("q2", "Spring");
        answers.put("q3", "正确");

        var result = examService.submit("e1", "u1", answers);

        assertEquals(3, result.get("score"));
        assertEquals(3, result.get("total"));
        assertNotNull(result.get("id"));
        assertNotNull(result.get("answers"));
        verify(attemptMapper).insert(any());
    }

    @Test
    void submitAllWrongReturnsZeroScoreAndRecordsWrongQuestions() {
        Question q1 = makeChoice("q1", "A");
        Question q2 = makeFill("q2", "Spring");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q1, q2));

        Map<String, String> answers = new HashMap<>();
        answers.put("q1", "B");
        answers.put("q2", "Summer");

        var result = examService.submit("e1", "u1", answers);

        assertEquals(0, result.get("score"));
        assertEquals(2, result.get("total"));
        verify(wqMapper, times(2)).insert(any());
    }

    @Test
    void submitWithEmptyOrMissingAnswersTreatedAsWrong() {
        Question q1 = makeChoice("q1", "A");
        Question q2 = makeFill("q2", "Spring");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q1, q2));

        Map<String, String> answers = new HashMap<>();
        answers.put("q1", "");

        var result = examService.submit("e1", "u1", answers);

        assertEquals(0, result.get("score"));
    }

    @Test
    void submitAnswerIsTrimmedBeforeComparison() {
        Question q1 = makeChoice("q1", "A");
        Question q2 = makeFill("q2", "Spring");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q1, q2));

        Map<String, String> answers = new HashMap<>();
        answers.put("q1", " A ");  // with spaces
        answers.put("q2", "  Spring  ");

        var result = examService.submit("e1", "u1", answers);

        assertEquals(2, result.get("score"));
    }

    // --- 简答题模糊判分 ---
    // 注意：使用长关键词（>6字符）可禁用字符覆盖率 fallback，只通过子串包含匹配

    @Test
    void shortAnswerSingleLongKeywordExactContainmentPasses() {
        // Long keyword: "面向对象编程特征" = 8 chars (> 6) → no char coverage fallback
        String explanation = "解析。【采分点】面向对象编程特征";
        Question q = makeShortAnswer("q1", "OO特征", explanation);

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, String> answers = new HashMap<>();
        answers.put("q1", "本题考察面向对象编程特征及其应用");

        var result = examService.submit("e1", "u1", answers);

        assertEquals(1, result.get("score"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertTrue((Boolean) graded.get("q1").get("correct"));
        assertFalse((Boolean) graded.get("q1").get("isFuzzyMatch")); // 100% not fuzzy
        assertEquals(1.0, graded.get("q1").get("hitRatio"));
    }

    @Test
    void shortAnswerSingleLongKeywordNoContainmentFails() {
        String explanation = "解析。【采分点】接口和抽象类的区别";  // 9 chars >6
        Question q = makeShortAnswer("q1", "Java类型", explanation);

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, String> answers = new HashMap<>();
        answers.put("q1", "Java支持多态、封装、继承");  // No substring match

        var result = examService.submit("e1", "u1", answers);

        assertEquals(0, result.get("score"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertFalse((Boolean) graded.get("q1").get("correct"));
    }

    @Test
    void shortAnswerFiveKeywordsThreeHitPassesThreshold() {
        // 5 keywords, 3 hits = 60% → exactly at FUZZY_THRESHOLD (0.6)
        // Use short keywords (<=6) but ensure hit detection is unambiguous
        String explanation = "解析。【采分点】封装性、继承性、多态性、抽象性、组合性";
        Question q = makeShortAnswer("q1", "OO特征", explanation);

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, String> answers = new HashMap<>();
        // Contains exactly 3 of 5
        answers.put("q1", "这个问题的答案包含封装性、继承性以及多态性");

        var result = examService.submit("e1", "u1", answers);

        assertEquals(1, result.get("score"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertTrue((Boolean) graded.get("q1").get("correct"));
    }

    @Test
    void shortAnswerFiveKeywordsTwoHitFailsBelowThreshold() {
        // 5 keywords, 2 hits = 40% < 60%
        String explanation = "解析。【采分点】封装性、继承性、多态性、抽象性、组合性";
        Question q = makeShortAnswer("q1", "OO特征", explanation);

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, String> answers = new HashMap<>();
        answers.put("q1", "主要特性是封装性和继承性");  // only 2/5

        var result = examService.submit("e1", "u1", answers);

        assertEquals(0, result.get("score"));
    }

    @Test
    void shortAnswerNoKeywordsMarkerFallsBackToExactMatch() {
        Question q = makeShortAnswer("q1", "42", "没有采分点标记的解析");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, String> answers1 = new HashMap<>();
        answers1.put("q1", "42");
        var r1 = examService.submit("e1", "u1", answers1);
        assertEquals(1, r1.get("score"));

        Map<String, String> answers2 = new HashMap<>();
        answers2.put("q1", "43");
        var r2 = examService.submit("e1", "u1", answers2);
        assertEquals(0, r2.get("score"));
    }

    @Test
    void shortAnswerNullExplanationFallsBackExactMatch() {
        Question q = makeShortAnswer("q1", "精确答案", null);

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, String> answers1 = new HashMap<>();
        answers1.put("q1", "精确答案");
        assertEquals(1, examService.submit("e1", "u1", answers1).get("score"));

        Map<String, String> answers2 = new HashMap<>();
        answers2.put("q1", "其他答案");
        assertEquals(0, examService.submit("e1", "u1", answers2).get("score"));
    }

    @Test
    void shortAnswerBlankExplanationFallsBack() {
        Question q = makeShortAnswer("q1", "ok", "   ");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, String> answers = new HashMap<>();
        answers.put("q1", "ok");
        assertEquals(1, examService.submit("e1", "u1", answers).get("score"));
    }

    @Test
    void shortAnswerExactHitRatioOneIsNotFuzzy() {
        // 1 keyword, exact containment → hitRatio=1.0, isFuzzyMatch=false
        String explanation = "解析。【采分点】依赖注入";
        Question q = makeShortAnswer("q1", "DI概念", explanation);

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, String> answers = new HashMap<>();
        answers.put("q1", "依赖注入是Spring的核心");

        var result = examService.submit("e1", "u1", answers);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertEquals(1.0, graded.get("q1").get("hitRatio"));
        assertFalse((Boolean) graded.get("q1").get("isFuzzyMatch"));
    }

    @Test
    void shortAnswerPartialRatioFlagsAsFuzzy() {
        // 4 keywords, 3 hits = 75% (> 60% but < 100%)
        // isFuzzyMatch = correct && hitRatio < 1.0 → true
        String explanation = "解析。【采分点】控制器、服务层、数据访问层、视图层";
        Question q = makeShortAnswer("q1", "MVC", explanation);

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, String> answers = new HashMap<>();
        answers.put("q1", "MVC架构包含控制器负责请求、服务层处理逻辑、以及数据访问层");

        var result = examService.submit("e1", "u1", answers);

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertTrue((Boolean) graded.get("q1").get("correct"));
        assertTrue((Boolean) graded.get("q1").get("isFuzzyMatch"));
        double hitRatio = (Double) graded.get("q1").get("hitRatio");
        assertEquals(0.75, hitRatio, 0.01);
    }

    @Test
    void shortAnswerKeywordCharacterCoverageFallbackWorks() {
        // Short keyword <= 6 chars with character overlap (synonym scenario)
        // "数据隐藏" (4 chars) vs answer "信息隐藏": shared chars 隐 + 藏 = 2/4 = 50% → passes
        String explanation = "解析。【采分点】数据隐藏";
        Question q = makeShortAnswer("q1", "封装概念", explanation);

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, String> answers = new HashMap<>();
        answers.put("q1", "信息隐藏是封装的一种体现");

        assertEquals(1, examService.submit("e1", "u1", answers).get("score"));
    }

    @Test
    void shortAnswerLongKeywordDisablesCharacterCoverageFallback() {
        // Keyword > 6 chars → character coverage skipped, substring only
        String explanation = "解析。【采分点】数据库事务隔离级别";  // 9 chars
        Question q = makeShortAnswer("q1", "DB", explanation);

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, String> answers = new HashMap<>();
        // Even with some shared characters, no match since > 6 and not substring
        answers.put("q1", "数据库概念很多但这个答案不包含完整关键词");

        assertEquals(0, examService.submit("e1", "u1", answers).get("score"));
    }

    @Test
    void shortAnswerKeywordsParsedOnlyUntilEndOfLine() {
        // Keywords after 【采分点】 should stop at first \n
        // Keywords: ["第一关键词", "第二关键词"] only (NOT "后续" or "内容")
        String explanation = "前置内容。【采分点】第一关键词、第二关键词\n后续段落内容不在关键词范围内";
        Question q = makeShortAnswer("q1", "test", explanation);

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, String> answers = new HashMap<>();
        // Match 2 keywords out of 2 → 100% → score 1
        answers.put("q1", "答案包含第一关键词和第二关键词");

        assertEquals(1, examService.submit("e1", "u1", answers).get("score"));
    }

    @Test
    void shortAnswerKeywordsParsedUntilEndOfLineFailWithTooFewHits() {
        // Use LONG keywords (>6 chars) to disable char-coverage fallback,
        // so hit ratio depends purely on substring containment.
        // 5 keywords on one line; parser stops at the newline (skips "后续段落").
        String explanation = "前置内容。【采分点】面向对象编程特征、集合框架数据结构、多线程并发编程模型、函数式编程范式、数据库事务隔离机制\n后续段落";
        Question q = makeShortAnswer("q1", "test", explanation);

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, String> answers = new HashMap<>();
        // Match only 2/5 keywords = 40% < 60% threshold → score 0
        answers.put("q1", "本题涉及面向对象编程特征以及集合框架数据结构的应用");

        assertEquals(0, examService.submit("e1", "u1", answers).get("score"));
        // Verify keywords after newline ("后续段落") were NOT parsed as keywords
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> graded = (Map<String, Map<String, Object>>) examService.submit("e1", "u1", answers).get("answers");
        assertEquals(0.4, graded.get("q1").get("hitRatio")); // 2/5, not 3/6
    }

    @Test
    void shortAnswerMixedSeparatorsAllParsed() {
        // Support separators: 、(Chinese comma) ,(ASCII) ，(fullwidth) \s(whitespace)
        String explanation = "解析。【采分点】Alpha,Beta，Gamma Delta";
        Question q = makeShortAnswer("q1", "框架", explanation);

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, String> answers = new HashMap<>();
        answers.put("q1", "包含 Alpha 和 Beta 还有 Gamma");  // 3/4 = 75%

        assertEquals(1, examService.submit("e1", "u1", answers).get("score"));
    }

    @Test
    void shortAnswerKeywordMatchingIsCaseInsensitive() {
        String explanation = "解析。【采分点】SPRINGFRAMEWORK、MYBATIS";
        Question q = makeShortAnswer("q1", "框架", explanation);

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, String> answers = new HashMap<>();
        answers.put("q1", "项目用 springframework 和 mybatis");

        assertEquals(1, examService.submit("e1", "u1", answers).get("score"));
    }

    @Test
    void submitDuplicateWrongQuestionDoesNotCrash() {
        Question q1 = makeChoice("q1", "A");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q1));
        doThrow(new RuntimeException("duplicate key constraint"))
                .when(wqMapper).insert(any());

        Map<String, String> answers = new HashMap<>();
        answers.put("q1", "B");

        assertDoesNotThrow(() -> examService.submit("e1", "u1", answers));
    }

    @Test
    void submitAttemptMapperFailureDoesNotCrash() {
        Question q1 = makeChoice("q1", "A");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q1));
        doThrow(new RuntimeException("DB connection lost"))
                .when(attemptMapper).insert(any());

        Map<String, String> answers = new HashMap<>();
        answers.put("q1", "A");

        var result = assertDoesNotThrow(() -> examService.submit("e1", "u1", answers));
        assertEquals(1, result.get("score"));
    }

    // --- 考试列表 ---

    @Test
    void listReturnsUserExams() {
        Exam e1 = new Exam();
        e1.setId("e1");
        e1.setTitle("Exam1");
        Exam e2 = new Exam();
        e2.setId("e2");
        e2.setTitle("Exam2");

        when(examMapper.findByUserId("u1")).thenReturn(List.of(e1, e2));

        var result = examService.list("u1");

        assertEquals(2, result.size());
        assertEquals("e1", result.get(0).getId());
    }
}
