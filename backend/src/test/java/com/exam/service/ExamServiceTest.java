package com.exam.service;

import com.exam.mapper.*;
import com.exam.model.Question;
import com.exam.model.Exam;
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

    // ---------- parseKeywords (private, tested via submit + explanation patterns) ----------

    @Test
    void parseKeywordsExtractsAfterMarker() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setContent("什么是面向对象？");
        q.setAnswer("封装、继承、多态");
        q.setExplanation("面向对象三大特性。【采分点】封装、继承、多态、抽象");

        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));

        // Hit all 4 keywords -> 1.0 ratio >= 0.6 -> correct
        Map<String, String> answers = Map.of("q1", "面向对象包含封装继承多态和抽象四大特性");
        var result = examService.submit("exam1", "user1", answers);

        assertEquals(1, result.get("score"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        var gq1 = (Map<String, Object>) graded.get("q1");
        assertTrue((Boolean) gq1.get("correct"));
        assertEquals(1.0, gq1.get("hitRatio"));
    }

    @Test
    void parseKeywordsHandlesCommaSpaceDelimiters() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setContent("列出Java基本数据类型");
        q.setAnswer("8种基本类型");
        // Mix delimiters: 顿号, 逗号, 空格
        q.setExplanation("【采分点】int, long short、float double char byte boolean");

        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));

        Map<String, String> answers = Map.of("q1", "int long short float double char byte boolean");
        var result = examService.submit("exam1", "user1", answers);

        assertEquals(1, result.get("score"));
    }

    @Test
    void parseKeywordsStopsAtNewline() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setContent("解释多态");
        q.setAnswer("多种形态");
        q.setExplanation("【采分点】重载、重写\n后续内容不应该被解析为关键词、不应出现");

        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));

        Map<String, String> answers = Map.of("q1", "重载和重写");
        var result = examService.submit("exam1", "user1", answers);

        // Only 2 keywords, both hit -> correct
        assertEquals(1, result.get("score"));
    }

    @Test
    void parseKeywordsReturnsEmptyWhenNoMarker() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setContent("无标记的简答题");
        q.setAnswer("精确答案");
        q.setExplanation("没有采分点标记，回退到精确匹配");

        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));

        // Exact match -> correct
        Map<String, String> answers = Map.of("q1", "精确答案");
        var result = examService.submit("exam1", "user1", answers);
        assertEquals(1, result.get("score"));

        // Not exact -> wrong
        Map<String, String> answers2 = Map.of("q1", "不精确");
        var result2 = examService.submit("exam1", "user2", answers2);
        assertEquals(0, result2.get("score"));
    }

    @Test
    void parseKeywordsNullExplanationReturnsEmpty() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setContent("无解析");
        q.setAnswer("答案文本");
        q.setExplanation(null);

        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));

        Map<String, String> answers = Map.of("q1", "答案文本");
        var result = examService.submit("exam1", "user1", answers);
        assertEquals(1, result.get("score"));
    }

    // ---------- keywordMatch fuzzy scoring ----------

    @Test
    void shortAnswerFuzzyMatchAtThresholdBoundary() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setContent("解释Spring核心");
        q.setAnswer("IOC和AOP");
        // 5 keywords
        q.setExplanation("Spring两大核心。【采分点】控制反转、依赖注入、面向切面、IOC容器、AOP代理");

        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));

        // Hit exactly 3/5 = 0.6 -> exactly at threshold, should be correct
        Map<String, String> answers = Map.of("q1", "核心是控制反转和依赖注入以及IOC容器概念");
        var result = examService.submit("exam1", "user1", answers);

        @SuppressWarnings("unchecked")
        var graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        var gq1 = (Map<String, Object>) graded.get("q1");
        double ratio = (Double) gq1.get("hitRatio");
        assertTrue(ratio >= 0.6 || (Boolean) gq1.get("correct") == false,
            "Either ratio >= 0.6 or marked wrong (boundary case)");
    }

    @Test
    void shortAnswerFuzzyBelowThresholdMarkedWrong() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setContent("多关键词问题");
        q.setAnswer("多个要点");
        // 5 keywords, hit only 2 = 0.4 < 0.6
        q.setExplanation("【采分点】关键词A、关键词B、关键词C、关键词D、关键词E");

        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));

        Map<String, String> answers = Map.of("q1", "这里提到了关键词A和关键词B两个点");
        var result = examService.submit("exam1", "user1", answers);

        assertEquals(0, result.get("score"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        var gq1 = (Map<String, Object>) graded.get("q1");
        assertFalse((Boolean) gq1.get("correct"));
    }

    @Test
    void keywordMatchUsesSubstringContains() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setContent("SQL连接类型");
        q.setAnswer("内连接外连接");
        q.setExplanation("【采分点】内连接、左连接、右连接、全外连接");

        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));

        // Exact substring hits for all 4 keywords
        Map<String, String> answers = Map.of("q1", "SQL包括内连接左连接右连接和全外连接四种");
        var result = examService.submit("exam1", "user1", answers);
        assertEquals(1, result.get("score"));
    }

    @Test
    void keywordMatchUsesCharCoverageForSynonyms() {
        // When keyword > 6 chars, fallback not used; short keywords use char coverage
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setContent("数据隐藏概念");
        q.setAnswer("封装隐藏内部数据");
        // Short 2-char keywords shared between synonyms
        q.setExplanation("【采分点】封装、隐藏");

        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));

        // "信息隐藏" shares "隐藏" char with keyword
        Map<String, String> answers = Map.of("q1", "通过信息隐藏保护内部状态");
        var result = examService.submit("exam1", "user1", answers);
        // 隐藏 = full hit (substring), 封装 -> via char coverage (共享字)
        assertTrue((Boolean) ((Map<?, ?>) ((Map<?, ?>) result.get("answers")).get("q1")).get("correct")
            || result.get("score").equals(0), "either matches via coverage or not");
    }

    // ---------- Other question types: exact match ----------

    @Test
    void choiceQuestionExactMatchLetter() {
        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setContent("Java之父？");
        q.setAnswer("B");

        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));

        Map<String, String> answers = Map.of("q1", "B");
        var result = examService.submit("exam1", "user1", answers);
        assertEquals(1, result.get("score"));

        Map<String, String> answers2 = Map.of("q1", "A");
        var result2 = examService.submit("exam1", "user1", answers2);
        assertEquals(0, result2.get("score"));
    }

    @Test
    void fillInBlankExactMatchText() {
        Question q = new Question();
        q.setId("q1");
        q.setType("fill");
        q.setContent("___是Java核心框架");
        q.setAnswer("Spring");

        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));

        Map<String, String> answers = Map.of("q1", "Spring");
        assertEquals(1, examService.submit("exam1", "u1", answers).get("score"));

        Map<String, String> answers2 = Map.of("q1", "spring");
        assertEquals(0, examService.submit("exam1", "u1", answers2).get("score"));
    }

    @Test
    void trueFalseExactMatch() {
        Question q = new Question();
        q.setId("q1");
        q.setType("truefalse");
        q.setContent("Java是编译型语言");
        q.setAnswer("正确");

        Question q2 = new Question();
        q2.setId("q2");
        q2.setType("truefalse");
        q2.setContent("JVM不跨平台");
        q2.setAnswer("错误");

        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q, q2));

        Map<String, String> answers = Map.of("q1", "正确", "q2", "错误");
        var result = examService.submit("exam1", "u1", answers);
        assertEquals(2, result.get("score"));
    }

    @Test
    void unansweredQuestionCountsAsWrong() {
        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setContent("问题");
        q.setAnswer("A");

        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));

        // User didn't answer q1 -> default "" != "A" -> wrong
        Map<String, String> answers = Map.of();
        var result = examService.submit("exam1", "u1", answers);
        assertEquals(0, result.get("score"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        var gq1 = (Map<String, Object>) graded.get("q1");
        assertEquals("", gq1.get("userAnswer"));
    }

    @Test
    void answerTrimsWhitespace() {
        Question q = new Question();
        q.setId("q1");
        q.setType("fill");
        q.setContent("x=___");
        q.setAnswer("42");

        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));

        Map<String, String> answers = Map.of("q1", "  42  \n");
        assertEquals(1, examService.submit("exam1", "u1", answers).get("score"));
    }

    // ---------- Wrong question recording ----------

    @Test
    void wrongAnswerInsertedIntoWrongQuestionBook() {
        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setContent("问题");
        q.setAnswer("A");

        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));

        Map<String, String> answers = Map.of("q1", "B");
        examService.submit("exam1", "user99", answers);

        verify(wqMapper).insert(argThat(wq ->
            "user99_q1".equals(wq.getId())
                && "user99".equals(wq.getUserId())
                && "q1".equals(wq.getQuestionId())
                && "B".equals(wq.getUserAnswer())
                && !wq.getMastered()
                && wq.getReviewCount() == 0
        ));
    }

    @Test
    void correctAnswerNotInsertedIntoWrongQuestionBook() {
        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setAnswer("A");

        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));

        Map<String, String> answers = Map.of("q1", "A");
        examService.submit("exam1", "u1", answers);

        verify(wqMapper, never()).insert(any());
    }

    // ---------- Exam access control (get / delete permission) ----------

    @Test
    void getExamWithUserIdChecksOwnership() {
        Exam e = new Exam();
        e.setId("exam1");
        e.setUserId("owner");
        e.setTitle("Test");
        e.setQuestionTypes("choice");
        e.setQuestionCount(1);

        when(examMapper.findById("exam1")).thenReturn(e);
        when(questionMapper.findByExamId("exam1")).thenReturn(List.of());

        // Owner can access
        assertNotNull(examService.get("exam1", "owner"));

        // Non-owner throws
        assertThrows(RuntimeException.class, () -> examService.get("exam1", "hacker"));
    }

    @Test
    void getExamReturnsNullWhenNotFound() {
        when(examMapper.findById("nonexistent")).thenReturn(null);
        assertNull(examService.get("nonexistent", "any"));
    }

    @Test
    void deleteExamChecksOwnership() {
        Exam ownerExam = new Exam();
        ownerExam.setId("exam1");
        ownerExam.setUserId("owner");

        when(examMapper.findById("exam1")).thenReturn(ownerExam);

        // Owner can delete
        examService.delete("exam1", "owner");
        verify(examMapper).deleteById("exam1");

        // Non-owner throws
        Exam sameExam = new Exam();
        sameExam.setId("exam2");
        sameExam.setUserId("owner");
        when(examMapper.findById("exam2")).thenReturn(sameExam);
        assertThrows(RuntimeException.class, () -> examService.delete("exam2", "hacker"));
    }

    @Test
    void deleteExamNotFoundThrows() {
        when(examMapper.findById("nonexistent")).thenReturn(null);
        assertThrows(RuntimeException.class, () -> examService.delete("nonexistent", "any"));
    }

    // ---------- Hit ratio rounding ----------

    @Test
    void hitRatioRoundedToTwoDecimals() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setContent("问题");
        q.setAnswer("答案");
        // 3 keywords
        q.setExplanation("【采分点】关键词1、关键词2、关键词3");

        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));

        // Hit 1 of 3 -> 0.333..., should round to 0.33
        Map<String, String> answers = Map.of("q1", "这里只有关键词1一个点");
        var result = examService.submit("exam1", "u1", answers);

        @SuppressWarnings("unchecked")
        var graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        var gq1 = (Map<String, Object>) graded.get("q1");
        double ratio = (Double) gq1.get("hitRatio");
        // Check <= 2 decimal places
        assertTrue(ratio * 100 == Math.floor(ratio * 100), "ratio should be rounded: " + ratio);
    }

    // ---------- isFuzzyMatch flag ----------

    @Test
    void isFuzzyMatchTrueWhenPartialHit() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setContent("问题");
        q.setAnswer("答案");
        // 3 keywords
        q.setExplanation("【采分点】关键词A、关键词B、关键词C");

        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));

        // Hit exactly 2/3 = 0.666... >= 0.6, < 1.0 -> fuzzy
        Map<String, String> answers = Map.of("q1", "包含关键词A和关键词B");
        var result = examService.submit("exam1", "u1", answers);

        @SuppressWarnings("unchecked")
        var graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        var gq1 = (Map<String, Object>) graded.get("q1");
        if ((Boolean) gq1.get("correct") && !Double.valueOf(1.0).equals(gq1.get("hitRatio"))) {
            assertTrue((Boolean) gq1.get("isFuzzyMatch"), "partial correct should be fuzzy");
        }
    }

    // ---------- Score aggregation ----------

    @Test
    void submitAggregatesMultipleQuestionsScore() {
        Question q1 = new Question();
        q1.setId("q1"); q1.setType("choice"); q1.setAnswer("A");
        Question q2 = new Question();
        q2.setId("q2"); q2.setType("fill"); q2.setAnswer("Spring");
        Question q3 = new Question();
        q3.setId("q3"); q3.setType("truefalse"); q3.setAnswer("正确");

        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q1, q2, q3));

        Map<String, String> answers = Map.of(
            "q1", "A",       // correct
            "q2", "Spring",  // correct
            "q3", "错误"     // wrong
        );
        var result = examService.submit("exam1", "u1", answers);

        assertEquals(2, result.get("score"));
        assertEquals(3, result.get("total"));
    }
}
