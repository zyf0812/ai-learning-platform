package com.exam.service;

import com.exam.mapper.*;
import com.exam.model.Exam;
import com.exam.model.Question;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        examService = new ExamService(examMapper, questionMapper, docMapper,
                deepSeek, ragService, wqMapper, attemptMapper, taskQueue);
    }

    // ---------- 访问控制：get(id, userId) ----------

    @Test
    void getWithValidOwnerReturnsExamWithQuestions() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setTitle("期中测试");
        exam.setUserId("userA");
        exam.setQuestionTypes("choice,fill");
        exam.setQuestionCount(2);

        List<Question> questions = new ArrayList<>();
        Question q1 = new Question();
        q1.setId("q1");
        q1.setType("choice");
        questions.add(q1);

        when(examMapper.findById("e1")).thenReturn(exam);
        when(questionMapper.findByExamId("e1")).thenReturn(questions);

        Map<String, Object> result = examService.get("e1", "userA");

        assertNotNull(result);
        assertEquals("e1", result.get("id"));
        assertEquals("期中测试", result.get("title"));
        assertEquals(2, result.get("questionCount"));
        assertEquals(questions, result.get("questions"));
    }

    @Test
    void getWithNonOwnerThrowsPermissionError() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("userA");

        when(examMapper.findById("e1")).thenReturn(exam);

        var ex = assertThrows(RuntimeException.class,
                () -> examService.get("e1", "userB"));
        assertEquals("无权访问该考试", ex.getMessage());
    }

    @Test
    void getWithMissingExamReturnsNull() {
        when(examMapper.findById("missing")).thenReturn(null);
        assertNull(examService.get("missing", "anyUser"));
    }

    // ---------- 访问控制：delete(id, userId) ----------

    @Test
    void deleteByOwnerSucceeds() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("userA");

        when(examMapper.findById("e1")).thenReturn(exam);
        doNothing().when(examMapper).deleteById("e1");

        assertDoesNotThrow(() -> examService.delete("e1", "userA"));
        verify(examMapper).deleteById("e1");
    }

    @Test
    void deleteByNonOwnerThrows() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("userA");

        when(examMapper.findById("e1")).thenReturn(exam);

        var ex = assertThrows(RuntimeException.class,
                () -> examService.delete("e1", "userB"));
        assertEquals("无权删除该考试", ex.getMessage());
        verify(examMapper, never()).deleteById(anyString());
    }

    @Test
    void deleteNonExistentExamThrows() {
        when(examMapper.findById("gone")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class,
                () -> examService.delete("gone", "userA"));
        assertEquals("考试不存在", ex.getMessage());
    }

    // ---------- list ----------

    @Test
    void listReturnsUserExams() {
        List<Exam> exams = new ArrayList<>();
        Exam e = new Exam();
        e.setId("e1");
        exams.add(e);
        when(examMapper.findByUserId("u1")).thenReturn(exams);

        assertEquals(1, examService.list("u1").size());
        assertEquals("e1", examService.list("u1").get(0).getId());
    }

    // ---------- submit：选择题/填空题/判断题精确评分 ----------

    private Question makeQuestion(String id, String type, String answer, String explanation) {
        Question q = new Question();
        q.setId(id);
        q.setType(type);
        q.setContent("题目" + id);
        q.setAnswer(answer);
        q.setExplanation(explanation);
        return q;
    }

    @Test
    void submitAllCorrectGetsFullScore() {
        List<Question> questions = List.of(
                makeQuestion("q1", "choice", "B", ""),
                makeQuestion("q2", "fill", "Spring", ""),
                makeQuestion("q3", "truefalse", "正确", "")
        );
        when(questionMapper.findByExamId("e1")).thenReturn(questions);

        Map<String, String> answers = Map.of(
                "q1", "B",
                "q2", "Spring",
                "q3", "正确"
        );

        Map<String, Object> result = examService.submit("e1", "u1", answers);

        assertEquals(3, result.get("score"));
        assertEquals(3, result.get("total"));
        @SuppressWarnings("unchecked")
        Map<String, Object> graded = (Map<String, Object>) result.get("answers");
        for (String qid : List.of("q1", "q2", "q3")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> g = (Map<String, Object>) graded.get(qid);
            assertTrue((Boolean) g.get("correct"), qid + " 应为正确");
            assertEquals(1.0, g.get("hitRatio"));
        }
    }

    @Test
    void submitAllWrongGetsZeroScoreAndRecordsWrongQuestions() {
        List<Question> questions = List.of(
                makeQuestion("q1", "choice", "A", ""),
                makeQuestion("q2", "fill", "Java", "")
        );
        when(questionMapper.findByExamId("e2")).thenReturn(questions);

        Map<String, String> answers = Map.of("q1", "C", "q2", "Python");

        Map<String, Object> result = examService.submit("e2", "u2", answers);

        assertEquals(0, result.get("score"));
        assertEquals(2, result.get("total"));

        // 验证写入错题本
        verify(wqMapper, times(2)).insert(any());
        // 验证保存考试记录
        verify(attemptMapper).insert(any());
    }

    @Test
    void submitUnansweredQuestionMarkedWrong() {
        Question q = makeQuestion("q1", "choice", "A", "");
        when(questionMapper.findByExamId("e3")).thenReturn(List.of(q));

        Map<String, Object> result = examService.submit("e3", "u1", Map.of());

        assertEquals(0, result.get("score"));
        @SuppressWarnings("unchecked")
        Map<String, Object> graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> g = (Map<String, Object>) graded.get("q1");
        assertEquals("", g.get("userAnswer"));
        assertFalse((Boolean) g.get("correct"));
    }

    @Test
    void submitAnswerWithWhitespaceIsTrimmed() {
        Question q = makeQuestion("q1", "fill", "correct", "");
        when(questionMapper.findByExamId("e4")).thenReturn(List.of(q));

        // 用户答案含前后空格，应被trim后匹配
        Map<String, Object> result = examService.submit("e4", "u1", Map.of("q1", "  correct  "));

        assertEquals(1, result.get("score"));
    }

    // ---------- submit：简答题模糊评分 ----------

    @Test
    void shortAnswerExactMatchReturnsCorrect() {
        String explanation = "【采分点】封装、继承、多态";
        Question q = makeQuestion("q1", "shortanswer", "面向对象三大特性：封装、继承、多态", explanation);
        when(questionMapper.findByExamId("e5")).thenReturn(List.of(q));

        // 完全命中三个关键词
        Map<String, Object> result = examService.submit("e5", "u1",
                Map.of("q1", "封装、继承和多态是三大特性"));

        assertEquals(1, result.get("score"));
        @SuppressWarnings("unchecked")
        Map<String, Object> graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> g = (Map<String, Object>) graded.get("q1");
        assertTrue((Boolean) g.get("correct"));
        assertEquals(1.0, g.get("hitRatio"));
    }

    @Test
    void shortAnswer60PercentHitIsFuzzyCorrect() {
        // 5 个采分点，命中 3 个 = 60%，刚好达到阈值
        String explanation = "【采分点】封装、继承、多态、抽象、接口";
        Question q = makeQuestion("q1", "shortanswer", "详细说明", explanation);
        when(questionMapper.findByExamId("e6")).thenReturn(List.of(q));

        // 命中 3/5 = 0.6 → 正确
        Map<String, Object> result = examService.submit("e6", "u1",
                Map.of("q1", "答案涉及封装、继承、多态三种特性"));

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
    void shortAnswerBelow60PercentIsWrong() {
        // 4 个采分点，命中 2 个 = 50%，低于阈值
        String explanation = "【采分点】封装、继承、多态、抽象";
        Question q = makeQuestion("q1", "shortanswer", "详细说明", explanation);
        when(questionMapper.findByExamId("e7")).thenReturn(List.of(q));

        Map<String, Object> result = examService.submit("e7", "u1",
                Map.of("q1", "封装和继承是重要概念"));

        assertEquals(0, result.get("score"));
        @SuppressWarnings("unchecked")
        Map<String, Object> graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> g = (Map<String, Object>) graded.get("q1");
        assertFalse((Boolean) g.get("correct"));
        assertEquals(0.5, g.get("hitRatio"));
    }

    @Test
    void shortAnswerWithNoKeywordsFallsBackToExactMatch() {
        // 没有【采分点】标记，回退到精确匹配
        Question q = makeQuestion("q1", "shortanswer", "42", "答案就是42，没有采分点");
        when(questionMapper.findByExamId("e8")).thenReturn(List.of(q));

        // 精确匹配 → 正确
        Map<String, Object> r1 = examService.submit("e8", "u1", Map.of("q1", "42"));
        assertEquals(1, r1.get("score"));

        // 不匹配 → 错误
        Map<String, Object> r2 = examService.submit("e8", "u1", Map.of("q1", "答案是42"));
        assertEquals(0, r2.get("score"));
    }

    @Test
    void shortAnswerEmptyExplanationFallsBackToExactMatch() {
        Question q = makeQuestion("q1", "shortanswer", "OK", "");
        when(questionMapper.findByExamId("e9")).thenReturn(List.of(q));

        Map<String, Object> r = examService.submit("e9", "u1", Map.of("q1", "OK"));
        assertEquals(1, r.get("score"));
    }

    @Test
    void shortAnswerKeywordsAfterNewlineIgnored() {
        // 【采分点】只取到行尾，后续行不应解析为关键词
        String explanation = "这是解析内容\n【采分点】关键词1、关键词2\n【其他标记】不要解析这里、的内容";
        Question q = makeQuestion("q1", "shortanswer", "参考", explanation);
        when(questionMapper.findByExamId("e10")).thenReturn(List.of(q));

        // 2个关键词，全中 → 1.0
        Map<String, Object> r = examService.submit("e10", "u1",
                Map.of("q1", "这里包含关键词1和关键词2的答案"));
        @SuppressWarnings("unchecked")
        Map<String, Object> graded = (Map<String, Object>) r.get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> g = (Map<String, Object>) graded.get("q1");
        assertEquals(1.0, g.get("hitRatio"));
    }

    @Test
    void shortAnswerKeywordCharacterCoverageMatch() {
        // 子串不直接命中但字符覆盖率≥50% → 判定命中（同义词场景）
        String explanation = "【采分点】数据隐藏";
        Question q = makeQuestion("q1", "shortanswer", "参考", explanation);
        when(questionMapper.findByExamId("e11")).thenReturn(List.of(q));

        // "信息隐藏" 与 "数据隐藏" 共享"隐藏"两字 → 覆盖率 >= 0.5
        Map<String, Object> r = examService.submit("e11", "u1",
                Map.of("q1", "这个概念体现了信息隐藏的思想"));
        assertEquals(1, r.get("score"));
    }

    @Test
    void shortAnswerNoPartialMatchForLongKeywords() {
        // 关键词长度>6 时，字符覆盖率路径被跳过（防误判）
        String explanation = "【采分点】依赖注入控制反转"; // >6字符
        Question q = makeQuestion("q1", "shortanswer", "参考", explanation);
        when(questionMapper.findByExamId("e12")).thenReturn(List.of(q));

        Map<String, Object> r = examService.submit("e12", "u1",
                Map.of("q1", "反转控制")); // 部分字重叠但长度>6，不走覆盖率
        assertEquals(0, r.get("score"));
    }

    @Test
    void shortAnswerZeroHitRatio() {
        String explanation = "【采分点】线程安全、锁机制、原子性";
        Question q = makeQuestion("q1", "shortanswer", "参考", explanation);
        when(questionMapper.findByExamId("e13")).thenReturn(List.of(q));

        // 完全不相关的答案
        Map<String, Object> r = examService.submit("e13", "u1",
                Map.of("q1", "香蕉苹果橘子水果"));
        assertEquals(0, r.get("score"));
        @SuppressWarnings("unchecked")
        Map<String, Object> graded = (Map<String, Object>) r.get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> g = (Map<String, Object>) graded.get("q1");
        assertEquals(0.0, g.get("hitRatio"));
    }

    // ---------- submit：attempt持久化验证 ----------

    @Test
    void submitPersistsAttemptWithScoreAndTotal() {
        Question q = makeQuestion("q1", "choice", "A", "");
        when(questionMapper.findByExamId("e14")).thenReturn(List.of(q));

        examService.submit("e14", "u1", Map.of("q1", "A"));

        ArgumentCaptor<com.exam.model.ExamAttempt> captor =
                ArgumentCaptor.forClass(com.exam.model.ExamAttempt.class);
        verify(attemptMapper).insert(captor.capture());
        assertEquals(1, captor.getValue().getScore());
        assertEquals(1, captor.getValue().getTotal());
        assertEquals("e14", captor.getValue().getExamId());
        assertEquals("u1", captor.getValue().getUserId());
    }
}
