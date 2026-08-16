package com.exam.service;

import com.exam.mapper.*;
import com.exam.model.Exam;
import com.exam.model.Question;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
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
        examService = new ExamService(
            examMapper, questionMapper, docMapper, deepSeek, ragService,
            wqMapper, attemptMapper, taskQueue);
    }

    // ==================== parseKeywords 反射测试 ====================

    private List<String> callParseKeywords(String explanation) throws Exception {
        Method m = ExamService.class.getDeclaredMethod("parseKeywords", String.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        var result = (List<String>) m.invoke(examService, explanation);
        return result;
    }

    @Test
    void parseKeywords_null_returnsEmpty() throws Exception {
        assertEquals(List.of(), callParseKeywords(null));
    }

    @Test
    void parseKeywords_empty_returnsEmpty() throws Exception {
        assertEquals(List.of(), callParseKeywords(""));
        assertEquals(List.of(), callParseKeywords("   "));
    }

    @Test
    void parseKeywords_noMarker_returnsEmpty() throws Exception {
        assertEquals(List.of(), callParseKeywords("This is a plain explanation without any marker."));
        assertEquals(List.of(), callParseKeywords("采分点：a、b、c")); // 缺少【】
    }

    @Test
    void parseKeywords_basicCommaSeparation() throws Exception {
        String input = "这是解析内容\n【采分点】封装、继承、多态";
        assertEquals(List.of("封装", "继承", "多态"), callParseKeywords(input));
    }

    @Test
    void parseKeywords_chineseCommaSeparator() throws Exception {
        String input = "【采分点】Spring，BeanFactory，ApplicationContext";
        assertEquals(List.of("Spring", "BeanFactory", "ApplicationContext"), callParseKeywords(input));
    }

    @Test
    void parseKeywords_englishCommaSeparator() throws Exception {
        String input = "【采分点】OOP, SOLID, DRY";
        assertEquals(List.of("OOP", "SOLID", "DRY"), callParseKeywords(input));
    }

    @Test
    void parseKeywords_spaceSeparator() throws Exception {
        String input = "【采分点】Java JVM GC 垃圾回收";
        assertEquals(List.of("Java", "JVM", "GC", "垃圾回收"), callParseKeywords(input));
    }

    @Test
    void parseKeywords_mixedSeparators() throws Exception {
        String input = "【采分点】A、B，C, D E";
        assertEquals(List.of("A", "B", "C", "D", "E"), callParseKeywords(input));
    }

    @Test
    void parseKeywords_stopsAtNewline() throws Exception {
        String input = "【采分点】关键词1、关键词2\n这行内容不应该被解析\n【采分点】陷阱";
        assertEquals(List.of("关键词1", "关键词2"), callParseKeywords(input));
    }

    @Test
    void parseKeywords_emptyPartsFiltered() throws Exception {
        String input = "【采分点】、、A、、B、、";
        assertEquals(List.of("A", "B"), callParseKeywords(input));
    }

    @Test
    void parseKeywords_trimsWhitespace() throws Exception {
        String input = "【采分点】  封装  、  继承  、 多态 ";
        assertEquals(List.of("封装", "继承", "多态"), callParseKeywords(input));
    }

    @Test
    void parseKeywords_longPhrases() throws Exception {
        String input = "【采分点】8种基本数据类型、int占4字节、自动装箱拆箱";
        assertEquals(List.of("8种基本数据类型", "int占4字节", "自动装箱拆箱"),
            callParseKeywords(input));
    }

    @Test
    void parseKeywords_markerInMiddle() throws Exception {
        String input = "先写一些解析内容。\n然后给出采分点。\n【采分点】核心1、核心2\n后续还有其他解释。";
        assertEquals(List.of("核心1", "核心2"), callParseKeywords(input));
    }

    // ==================== keywordMatch 反射测试 ====================

    private boolean callKeywordMatch(String text, String keyword) throws Exception {
        Method m = ExamService.class.getDeclaredMethod("keywordMatch", String.class, String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(examService, text, keyword);
    }

    @Test
    void keywordMatch_exactSubstring_true() throws Exception {
        assertTrue(callKeywordMatch("java is a programming language", "java"));
        assertTrue(callKeywordMatch("面向对象的三大特性是封装继承多态", "封装"));
    }

    @Test
    void keywordMatch_noMatch_false() throws Exception {
        assertFalse(callKeywordMatch("hello world", "java"));
    }

    @Test
    void keywordMatch_partialCharsShortKeyword_false() throws Exception {
        // 短关键词（<=6）字符覆盖率 50% 以下
        // "abc" = 3 chars, coverage: ab/3 = 0/3 = 0% < 50%
        assertFalse(callKeywordMatch("xyz", "abc"));
    }

    @Test
    void keywordMatch_charCoverageLongKeyword_false() throws Exception {
        // 长关键词 > 6，直接不考虑覆盖率匹配
        assertFalse(callKeywordMatch("隐藏", "数据封装概念"));
    }

    @Test
    void keywordMatch_charCoverage50PercentShortKeyword_true() throws Exception {
        // "数据隐藏" vs "信息隐藏"：共享"隐藏"两字
        // keyword = "隐藏" (2 chars), text has 隐藏 → coverage 2/2 = 100%
        assertTrue(callKeywordMatch("这是信息隐藏的概念", "隐藏"));
    }

    @Test
    void keywordMatch_charCoverage3CharsKeyword() throws Exception {
        // keyword = "封装性" (3 chars)
        // text has "封装" → 2/3 = 66.7% >= 50%
        assertTrue(callKeywordMatch("封装是OOP的核心", "封装性"));
    }

    @Test
    void keywordMatch_charCoverage4CharsKeyword() throws Exception {
        // keyword = "数据库连接" (5 chars but let's pick 4)
        // keyword = "数据类型" (4 chars). text has "数据" → 2/4 = 50% → pass
        assertTrue(callKeywordMatch("数据是核心", "数据类型"));
    }

    @Test
    void keywordMatch_charCoverageBelowThreshold() throws Exception {
        // keyword = "Spring框架" (6 chars)
        // text only has "S" → 1/6 ≈ 16.7% < 50%
        assertFalse(callKeywordMatch("Some text", "Spring框架"));
    }

    @Test
    void keywordMatch_emptyKeyword_edge() throws Exception {
        // 空关键词子串匹配就 true
        assertTrue(callKeywordMatch("anything", ""));
    }

    // ==================== list / get 权限校验 ====================

    @Test
    void listByUserId_delegatesToMapper() {
        User user = new User();
        List<Exam> expected = List.of(new Exam(), new Exam());
        when(examMapper.findByUserId("u1")).thenReturn(expected);

        var result = examService.list("u1");

        assertSame(expected, result);
        verify(examMapper).findByUserId("u1");
    }

    @Test
    void getByIdAndUserId_wrongUser_throws() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("owner");

        when(examMapper.findById("e1")).thenReturn(exam);

        var ex = assertThrows(RuntimeException.class,
            () -> examService.get("e1", "hacker"));

        assertEquals("无权访问该考试", ex.getMessage());
    }

    @Test
    void getByIdAndUserId_correctUser_returnsData() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("owner");
        exam.setTitle("Midterm");
        exam.setQuestionTypes("choice,fill");
        exam.setQuestionCount(10);

        List<Question> questions = List.of(new Question());
        when(examMapper.findById("e1")).thenReturn(exam);
        when(questionMapper.findByExamId("e1")).thenReturn(questions);

        var result = examService.get("e1", "owner");

        assertEquals("e1", result.get("id"));
        assertEquals("Midterm", result.get("title"));
        assertEquals("owner", result.get("userId"));
        assertSame(questions, result.get("questions"));
    }

    @Test
    void getByIdAndUserId_notFound_returnsNull() {
        when(examMapper.findById("nonexistent")).thenReturn(null);
        assertNull(examService.get("nonexistent", "anyuser"));
    }

    // ==================== delete 权限校验 ====================

    @Test
    void delete_notFound_throws() {
        when(examMapper.findById("e1")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> examService.delete("e1", "user1"));

        assertEquals("考试不存在", ex.getMessage());
    }

    @Test
    void delete_wrongUser_throws() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("owner");
        when(examMapper.findById("e1")).thenReturn(exam);

        var ex = assertThrows(RuntimeException.class,
            () -> examService.delete("e1", "hacker"));

        assertEquals("无权删除该考试", ex.getMessage());
    }

    @Test
    void delete_correctUser_delegates() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("owner");
        when(examMapper.findById("e1")).thenReturn(exam);
        doNothing().when(examMapper).deleteById("e1");

        examService.delete("e1", "owner");

        verify(examMapper).deleteById("e1");
    }

    // ==================== submit 评分逻辑 ====================

    private Question makeChoice(String id, String content, String answer) {
        Question q = new Question();
        q.setId(id);
        q.setType("choice");
        q.setContent(content);
        q.setOptions("[\"A|opt1\",\"B|opt2\",\"C|opt3\",\"D|opt4\"]");
        q.setAnswer(answer);
        q.setExplanation("");
        return q;
    }

    private Question makeFill(String id, String content, String answer) {
        Question q = new Question();
        q.setId(id);
        q.setType("fill");
        q.setContent(content);
        q.setAnswer(answer);
        q.setExplanation("");
        return q;
    }

    private Question makeTrueFalse(String id, String content, String answer) {
        Question q = new Question();
        q.setId(id);
        q.setType("truefalse");
        q.setContent(content);
        q.setAnswer(answer);
        q.setExplanation("");
        return q;
    }

    private Question makeShortAnswer(String id, String content, String answer, String explanation) {
        Question q = new Question();
        q.setId(id);
        q.setType("shortanswer");
        q.setContent(content);
        q.setAnswer(answer);
        q.setExplanation(explanation);
        return q;
    }

    @Test
    void submit_allCorrect_getsFullScore() {
        Question q1 = makeChoice("q1", "1+1=?", "B");
        Question q2 = makeFill("q2", "Java之父是___", "James Gosling");
        Question q3 = makeTrueFalse("q3", "Java是编译型语言", "正确");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q1, q2, q3));
        doNothing().when(attemptMapper).insert(any());

        Map<String, String> answers = Map.of(
            "q1", "B",
            "q2", "James Gosling",
            "q3", "正确"
        );

        var result = examService.submit("e1", "u1", answers);

        assertEquals(3, result.get("score"));
        assertEquals(3, result.get("total"));

        @SuppressWarnings("unchecked")
        var graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertTrue((Boolean) graded.get("q1").get("correct"));
        assertTrue((Boolean) graded.get("q2").get("correct"));
        assertTrue((Boolean) graded.get("q3").get("correct"));

        // 全对不写错题
        verify(wqMapper, never()).insert(any());
    }

    @Test
    void submit_allWrong_zeroScore() {
        Question q1 = makeChoice("q1", "1+1=?", "B");
        Question q2 = makeFill("q2", "Java之父是___", "James Gosling");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q1, q2));
        doNothing().when(attemptMapper).insert(any());

        Map<String, String> answers = Map.of(
            "q1", "C",
            "q2", "Wrong Answer"
        );

        var result = examService.submit("e1", "u1", answers);

        assertEquals(0, result.get("score"));
        assertEquals(2, result.get("total"));

        // 错题写入
        verify(wqMapper, times(2)).insert(any());
    }

    @Test
    void submit_answerTrimmed() {
        Question q1 = makeChoice("q1", "Q", "A");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q1));
        doNothing().when(attemptMapper).insert(any());

        // 用户答案前后有空格
        Map<String, String> answers = Map.of("q1", "  A  ");

        var result = examService.submit("e1", "u1", answers);
        assertEquals(1, result.get("score"));
    }

    @Test
    void submit_missingAnswerTreatedAsEmpty() {
        Question q1 = makeChoice("q1", "Q", "A");
        Question q2 = makeChoice("q2", "Q", "B");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q1, q2));
        doNothing().when(attemptMapper).insert(any());

        // 只回答了 q2，q1 未答
        Map<String, String> answers = Map.of("q2", "B");

        var result = examService.submit("e1", "u1", answers);
        assertEquals(1, result.get("score"));

        @SuppressWarnings("unchecked")
        var graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertFalse((Boolean) graded.get("q1").get("correct"));
        assertEquals("", graded.get("q1").get("userAnswer"));
    }

    @Test
    void submit_shortAnswerExactMatch_correct() {
        Question q = makeShortAnswer("q1", "什么是OOP？", "面向对象编程",
            "OOP就是面向对象编程。\n【采分点】封装、继承、多态");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        Map<String, String> answers = Map.of("q1", "面向对象编程");

        var result = examService.submit("e1", "u1", answers);
        assertEquals(1, result.get("score"));

        @SuppressWarnings("unchecked")
        var graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertTrue((Boolean) graded.get("q1").get("correct"));
        assertEquals(1.0, graded.get("q1").get("hitRatio"));
    }

    @Test
    void submit_shortAnswerFuzzyMatch60Percent_correct() {
        // 4 个关键词，命中 3 个 = 75% >= 60% 阈值
        Question q = makeShortAnswer("q1", "OOP三大特性", "",
            "【采分点】封装、继承、多态、抽象");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        // 命中 封装、继承、多态 3个
        Map<String, String> answers = Map.of("q1", "面向对象有封装、继承和多态三个核心特性");

        var result = examService.submit("e1", "u1", answers);
        assertEquals(1, result.get("score"));

        @SuppressWarnings("unchecked")
        var graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertTrue((Boolean) graded.get("q1").get("correct"));
        assertTrue((Boolean) graded.get("q1").get("isFuzzyMatch"));
        assertEquals(0.75, graded.get("q1").get("hitRatio"));
    }

    @Test
    void submit_shortAnswerFuzzyMatchBelowThreshold_wrong() {
        // 4 个关键词，只命中 2 个 = 50% < 60% 阈值
        Question q = makeShortAnswer("q1", "OOP三大特性", "",
            "【采分点】封装、继承、多态、抽象");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        // 只提到了封装和继承
        Map<String, String> answers = Map.of("q1", "封装和继承是比较重要的概念");

        var result = examService.submit("e1", "u1", answers);
        assertEquals(0, result.get("score"));

        @SuppressWarnings("unchecked")
        var graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertFalse((Boolean) graded.get("q1").get("correct"));
        assertEquals(0.5, graded.get("q1").get("hitRatio"));
    }

    @Test
    void submit_shortAnswerNoMarkersFallbackToExact() {
        // 没有【采分点】标记，走精确匹配
        Question q = makeShortAnswer("q1", "问题", "标准答案",
            "普通的解析，没有采分点标记");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        Map<String, String> answers = Map.of("q1", "标准答案");
        var result = examService.submit("e1", "u1", answers);
        assertEquals(1, result.get("score"));

        // 不完全一致就错
        Map<String, String> answers2 = Map.of("q1", "和答案不一样");
        var result2 = examService.submit("e1", "u1", answers2);
        assertEquals(0, result2.get("score"));
    }

    @Test
    void submit_caseInsensitiveKeywordMatch() {
        Question q = makeShortAnswer("q1", "Java特性", "",
            "【采分点】JVM、Bytecode、GC");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        // 小写匹配
        Map<String, String> answers = Map.of("q1", "jvm 字节码 bytecode 和 gc 垃圾回收");

        var result = examService.submit("e1", "u1", answers);
        assertEquals(1, result.get("score"));
    }

    @Test
    void submit_wrongQuestionIdFormat_idIsUserIdPlusQuestionId() {
        Question q1 = makeChoice("qabc", "Q", "A");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q1));
        doNothing().when(wqMapper).insert(any());
        doNothing().when(attemptMapper).insert(any());

        Map<String, String> answers = Map.of("qabc", "B"); // 答错触发错题写入

        examService.submit("e1", "userXYZ", answers);

        verify(wqMapper).insert(argThat(wq ->
            "userXYZ_qabc".equals(wq.getId()) &&
            "userXYZ".equals(wq.getUserId()) &&
            "qabc".equals(wq.getQuestionId()) &&
            "e1".equals(wq.getExamId()) &&
            "B".equals(wq.getUserAnswer()) &&
            !wq.getMastered() &&
            wq.getReviewCount() == 0));
    }

    // ==================== getTaskStatus ====================

    @Test
    void getTaskStatus_delegatesToTaskQueue() {
        when(taskQueue.getStatus("exam:task:", "t123")).thenReturn("DONE:exam456");
        assertEquals("DONE:exam456", examService.getTaskStatus("t123"));
        verify(taskQueue).getStatus("exam:task:", "t123");
    }
}
