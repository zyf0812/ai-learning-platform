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
        examService = new ExamService(
            examMapper, questionMapper, docMapper, deepSeek, ragService,
            wqMapper, attemptMapper, taskQueue
        );
    }

    // ==================== get / delete 权限校验 ====================

    @Test
    void getExamByIdNotFoundReturnsNull() {
        when(examMapper.findById("nonexistent")).thenReturn(null);
        assertNull(examService.get("nonexistent", "user1"));
    }

    @Test
    void getExamByOtherUserThrows() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("owner");
        when(examMapper.findById("e1")).thenReturn(exam);

        var ex = assertThrows(RuntimeException.class, () -> examService.get("e1", "attacker"));
        assertEquals("无权访问该考试", ex.getMessage());
    }

    @Test
    void getExamByOwnerReturnsWithQuestions() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("user1");
        exam.setTitle("期中考试");
        exam.setQuestionTypes("choice,fill");
        exam.setQuestionCount(2);

        Question q1 = new Question();
        q1.setId("q1");
        q1.setType("choice");

        when(examMapper.findById("e1")).thenReturn(exam);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q1));

        Map<String, Object> result = examService.get("e1", "user1");
        assertEquals("期中考试", result.get("title"));
        assertEquals(2, result.get("questionCount"));
        assertEquals("user1", result.get("userId"));
        @SuppressWarnings("unchecked")
        List<Question> qs = (List<Question>) result.get("questions");
        assertEquals(1, qs.size());
    }

    @Test
    void deleteExamNotFoundThrows() {
        when(examMapper.findById("e1")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class, () -> examService.delete("e1", "u1"));
        assertEquals("考试不存在", ex.getMessage());
    }

    @Test
    void deleteExamByOtherUserThrows() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("owner");
        when(examMapper.findById("e1")).thenReturn(exam);

        var ex = assertThrows(RuntimeException.class, () -> examService.delete("e1", "attacker"));
        assertEquals("无权删除该考试", ex.getMessage());
    }

    @Test
    void deleteExamByOwnerSucceeds() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("user1");
        when(examMapper.findById("e1")).thenReturn(exam);

        examService.delete("e1", "user1");

        verify(examMapper).deleteById("e1");
    }

    // ==================== submit: 选择题/填空题/判断题 精确匹配 ====================

    @Test
    void submitChoiceCorrectGivesScore() {
        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setAnswer("B");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, String> answers = new HashMap<>();
        answers.put("q1", "B");

        Map<String, Object> result = examService.submit("e1", "u1", answers);

        assertEquals(1, result.get("score"));
        assertEquals(1, result.get("total"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertTrue((Boolean) graded.get("q1").get("correct"));
    }

    @Test
    void submitChoiceWrongNoScoreAndRecordWrongQuestion() {
        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setAnswer("B");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, String> answers = new HashMap<>();
        answers.put("q1", "A");

        Map<String, Object> result = examService.submit("e1", "u1", answers);

        assertEquals(0, result.get("score"));
        verify(wqMapper).insert(any());
    }

    @Test
    void submitTrueFalseCorrect() {
        Question q = new Question();
        q.setId("q1");
        q.setType("truefalse");
        q.setAnswer("正确");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, String> answers = Map.of("q1", "正确");
        assertEquals(1, examService.submit("e1", "u1", answers).get("score"));
    }

    @Test
    void submitFillBlankWithExtraSpacesTrimmed() {
        Question q = new Question();
        q.setId("q1");
        q.setType("fill");
        q.setAnswer("Spring");  // trim should match " Spring " -> "Spring"

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, String> answers = Map.of("q1", "  Spring  ");
        assertEquals(1, examService.submit("e1", "u1", answers).get("score"));
    }

    @Test
    void submitMissingAnswerCountsAsWrong() {
        Question q = new Question();
        q.setId("q1");
        q.setType("fill");
        q.setAnswer("anything");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        // 没有 q1 答案
        assertEquals(0, examService.submit("e1", "u1", Map.of()).get("score"));
    }

    // ==================== submit: 简答题采分点模糊匹配 ====================

    private Question shortAnswerQuestion(String explanation) {
        Question q = new Question();
        q.setId("s1");
        q.setType("shortanswer");
        q.setAnswer("参考标准答案");
        q.setExplanation(explanation);
        return q;
    }

    @Test
    void submitShortAnswerExactMatchAllKeywords() {
        // 3个关键词，全部命中 = 100% >= 60%
        Question q = shortAnswerQuestion("面向对象的三个特性【采分点】封装、继承、多态");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, String> answers = Map.of("s1", "面向对象语言具备封装、继承和多态三大特性");
        Map<String, Object> result = examService.submit("e1", "u1", answers);

        assertEquals(1, result.get("score"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertTrue((Boolean) graded.get("s1").get("correct"));
        assertEquals(1.0, graded.get("s1").get("hitRatio"));
        // 全部命中，不是模糊匹配（isFuzzyMatch=false）
        assertFalse((Boolean) graded.get("s1").get("isFuzzyMatch"));
    }

    @Test
    void submitShortAnswerPartialBelowThresholdNoScore() {
        // 3个关键词，只命中1个 = 33% < 60%
        Question q = shortAnswerQuestion("说明OO特性【采分点】封装、继承、多态");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, String> answers = Map.of("s1", "封装是类的重要概念");
        Map<String, Object> result = examService.submit("e1", "u1", answers);

        assertEquals(0, result.get("score"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertFalse((Boolean) graded.get("s1").get("correct"));
        assertEquals(0.33, graded.get("s1").get("hitRatio"));
    }

    @Test
    void submitShortAnswerPartialAboveThresholdFuzzyMatch() {
        // 5个关键词，命中4个 = 80% >= 60%，但不满100%，标记 isFuzzyMatch
        Question q = shortAnswerQuestion("【采分点】封装、继承、多态、抽象、组合");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, String> answers = Map.of("s1", "OO特性包括封装、继承、多态、抽象四个");
        Map<String, Object> result = examService.submit("e1", "u1", answers);

        assertEquals(1, result.get("score"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertTrue((Boolean) graded.get("s1").get("correct"));
        assertTrue((Boolean) graded.get("s1").get("isFuzzyMatch"));
        assertEquals(0.8, graded.get("s1").get("hitRatio"));
    }

    @Test
    void submitShortAnswerNoScorePointMarkerFallsBackExactMatch() {
        // 没有【采分点】标记，回退到精确匹配
        Question q = shortAnswerQuestion("这个解释没有采分点标记");
        q.setAnswer("标准答案");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, String> correctExact = Map.of("s1", "标准答案");
        assertEquals(1, examService.submit("e1", "u1", correctExact).get("score"));

        Map<String, String> closeButNotExact = Map.of("s1", "包含 标准答案 但不是完全等于");
        assertEquals(0, examService.submit("e1", "u2", closeButNotExact).get("score"));
    }

    @Test
    void submitShortAnswerKeywordCoverageModeNotOnlySubstring() {
        // keywordMatch 的字符覆盖率分支（子串不包含，但字符覆盖率 >= 0.5 且 keyword <=6 字）
        // 关键词"数据隐藏"（4字），用户答案包含"信息隐藏"（共享"隐藏"两字）
        // 字符覆盖率：{数,据,隐,藏} 命中 {隐,藏} = 2/4 = 0.5 -> 算命中
        Question q = shortAnswerQuestion("封装特性【采分点】数据隐藏、访问控制");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, String> answers = Map.of("s1", "封装强调信息隐藏和内部访问控制");
        // "数据隐藏" 字符覆盖率：2/4=0.5；"访问控制" 直接命中。2/2=100%
        assertEquals(1, examService.submit("e1", "u1", answers).get("score"));
    }

    @Test
    void submitShortAnswerKeywordsSeparatedByCommasAndSpaces() {
        // 测试分隔符：逗号、顿号、空格
        Question q = shortAnswerQuestion("【采分点】封装, 继承，多态 抽象");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, String> answers = Map.of("s1", "封装继承多态抽象");
        assertEquals(1.0, ((Map<?, ?>) ((Map<?, ?>) examService.submit("e1", "u1", answers)
            .get("answers")).get("s1")).get("hitRatio"));
    }

    @Test
    void submitShortAnswerEmptyKeywordMarker() {
        // 【采分点】后面无内容
        Question q = shortAnswerQuestion("空的采分点标记【采分点】\n下一行内容");
        q.setAnswer("foo");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        // 回退到精确匹配
        assertEquals(1, examService.submit("e1", "u1", Map.of("s1", "foo")).get("score"));
        assertEquals(0, examService.submit("e1", "u1", Map.of("s1", "bar")).get("score"));
    }

    @Test
    void submitShortAnswerNullExplanationFallsBackExact() {
        Question q = new Question();
        q.setId("s1");
        q.setType("shortanswer");
        q.setAnswer("好");
        q.setExplanation(null);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        assertEquals(1, examService.submit("e1", "u1", Map.of("s1", "好")).get("score"));
    }

    // ==================== submit: 错题本去重（重复ID冲突吞异常） ====================

    @Test
    void submitDuplicateWrongQuestionInsertSwallowsException() {
        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setAnswer("A");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        // 首次插入正常，第二次抛异常（如主键冲突），但 submit 内部 try/catch 吞掉
        doThrow(new RuntimeException("Duplicate key"))
            .when(wqMapper).insert(any());

        assertDoesNotThrow(() -> examService.submit("e1", "u1", Map.of("q1", "B")));
    }

    // ==================== submit: ExamAttempt 持久化 + JSON异常回退 ====================

    @Test
    void submitAttemptInsertionFailureDoesNotFail() {
        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setAnswer("A");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        doThrow(new RuntimeException("DB down"))
            .when(attemptMapper).insert(any());

        Map<String, Object> result = examService.submit("e1", "u1", Map.of("q1", "A"));
        // 即使 attemptMapper 插入失败，提交结果仍正确返回
        assertEquals(1, result.get("score"));
    }

    // ==================== list: 用户考试列表 ====================

    @Test
    void listReturnsUserExams() {
        Exam e1 = new Exam();
        Exam e2 = new Exam();
        when(examMapper.findByUserId("u1")).thenReturn(List.of(e1, e2));
        assertEquals(2, examService.list("u1").size());
    }
}
