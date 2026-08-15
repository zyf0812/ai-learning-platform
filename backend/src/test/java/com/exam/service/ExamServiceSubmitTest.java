package com.exam.service;

import com.exam.mapper.*;
import com.exam.model.Exam;
import com.exam.model.Question;
import com.exam.testutil.FakeTaskQueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExamServiceSubmitTest {

    @Mock private ExamMapper examMapper;
    @Mock private QuestionMapper questionMapper;
    @Mock private DocumentMapper docMapper;
    @Mock private DocumentChunkMapper chunkMapper; // 用于构造 RAGService
    @Mock private WrongQuestionMapper wqMapper;
    @Mock private ExamAttemptMapper attemptMapper;

    private FakeTaskQueueService taskQueue;

    private ExamService service;

    @BeforeEach
    void setUp() {
        // 具体类不使用 Mockito mock，改为构造真实/哑实例（submit 方法不调用这些服务）
        DeepSeekService deepSeek = new DeepSeekService("", "", "");
        RAGService ragService = new RAGService(chunkMapper, "", "", "", 1024);
        taskQueue = new FakeTaskQueueService();
        service = new ExamService(examMapper, questionMapper, docMapper,
            deepSeek, ragService, wqMapper, attemptMapper, taskQueue);
    }

    private Question choice(String id, String answer) {
        Question q = new Question();
        q.setId(id); q.setType("choice"); q.setAnswer(answer);
        q.setContent("选择题内容");
        return q;
    }

    private Question fill(String id, String answer) {
        Question q = new Question();
        q.setId(id); q.setType("fill"); q.setAnswer(answer);
        q.setContent("填空题内容");
        return q;
    }

    private Question tf(String id, String answer) {
        Question q = new Question();
        q.setId(id); q.setType("truefalse"); q.setAnswer(answer);
        q.setContent("判断题内容");
        return q;
    }

    private Question shortAnswer(String id, String answer, String explanation) {
        Question q = new Question();
        q.setId(id); q.setType("shortanswer"); q.setAnswer(answer);
        q.setExplanation(explanation);
        q.setContent("简答题内容");
        return q;
    }

    // ---------- 选择题/填空题/判断题 精确匹配 ----------

    @Test
    void submitChoiceCorrectGetsPoint() {
        Question q = choice("q1", "B");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        Map<String, String> answers = Map.of("q1", "B");
        var res = service.submit("e1", "u1", answers);

        assertEquals(1, res.get("score"));
        assertEquals(1, res.get("total"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Map<String, Object>>) res.get("answers");
        assertTrue((Boolean) graded.get("q1").get("correct"));
        assertEquals(1.0, (Double) graded.get("q1").get("hitRatio"));
        assertFalse((Boolean) graded.get("q1").get("isFuzzyMatch"));
        verify(wqMapper, never()).insert(any());
    }

    @Test
    void submitChoiceWrongGetsNoPointAndWritesWrongBook() {
        Question q = choice("q1", "A");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        Map<String, String> answers = Map.of("q1", "C");
        var res = service.submit("e1", "u1", answers);

        assertEquals(0, res.get("score"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Map<String, Object>>) res.get("answers");
        assertFalse((Boolean) graded.get("q1").get("correct"));
        assertEquals("C", graded.get("q1").get("userAnswer"));
        verify(wqMapper).insert(argThat(wq ->
            "u1_q1".equals(wq.getId()) &&
            "u1".equals(wq.getUserId()) &&
            "q1".equals(wq.getQuestionId()) &&
            "C".equals(wq.getUserAnswer()) &&
            Boolean.FALSE.equals(wq.getMastered()) &&
            Integer.valueOf(0).equals(wq.getReviewCount())));
    }

    @Test
    void submitFillExactMatch() {
        Question q = fill("q1", "Spring Framework");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        Map<String, String> answers = Map.of("q1", "Spring Framework");
        var res = service.submit("e1", "u1", answers);
        assertEquals(1, res.get("score"));
    }

    @Test
    void submitFillAnswerTrimmedBeforeCompare() {
        Question q = fill("q1", "IoC");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        Map<String, String> answers = Map.of("q1", "  IoC  ");
        var res = service.submit("e1", "u1", answers);
        assertEquals(1, res.get("score"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Map<String, Object>>) res.get("answers");
        assertEquals("IoC", graded.get("q1").get("userAnswer"));
    }

    @Test
    void submitTrueFalseCorrectChinese() {
        Question q = tf("q1", "正确");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        var res = service.submit("e1", "u1", Map.of("q1", "正确"));
        assertEquals(1, res.get("score"));
    }

    @Test
    void submitTrueFalseWrong() {
        Question q = tf("q1", "错误");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        var res = service.submit("e1", "u1", Map.of("q1", "正确"));
        assertEquals(0, res.get("score"));
    }

    @Test
    void submitMissingAnswerTreatedAsEmptyString() {
        Question q = choice("q1", "B");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        Map<String, String> answers = new HashMap<>();
        var res = service.submit("e1", "u1", answers);

        assertEquals(0, res.get("score"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Map<String, Object>>) res.get("answers");
        assertEquals("", graded.get("q1").get("userAnswer"));
    }

    @Test
    void submitMultipleQuestionsScoresCorrectly() {
        Question q1 = choice("q1", "A");
        Question q2 = fill("q2", "答案");
        Question q3 = tf("q3", "正确");
        Question q4 = choice("q4", "C");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q1, q2, q3, q4));
        doNothing().when(attemptMapper).insert(any());

        Map<String, String> answers = Map.of(
            "q1", "A",
            "q2", "答案",
            "q3", "正确",
            "q4", "A"
        );
        var res = service.submit("e1", "u1", answers);
        assertEquals(3, res.get("score"));
        assertEquals(4, res.get("total"));
    }

    // ---------- 简答题 模糊匹配 ----------

    @Test
    void submitShortAnswerWithKeywordsExactSubstringMatch() {
        Question q = shortAnswer("q1", "标准答案",
            "解析内容...\n【采分点】封装、继承、多态");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        var res = service.submit("e1", "u1",
            Map.of("q1", "面向对象三大特性：封装、继承和多态。"));
        assertEquals(1, res.get("score"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Map<String, Object>>) res.get("answers");
        assertEquals(1.0, (Double) graded.get("q1").get("hitRatio"));
        assertFalse((Boolean) graded.get("q1").get("isFuzzyMatch"));
    }

    @Test
    void submitShortAnswerPartialKeywordsAbove60PercentCountsCorrect() {
        Question q = shortAnswer("q1", "参考答案",
            "解释...\n【采分点】封装、继承、多态、抽象、接口");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        var res = service.submit("e1", "u1",
            Map.of("q1", "主要包括封装、继承和多态。"));
        assertEquals(1, res.get("score"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Map<String, Object>>) res.get("answers");
        assertTrue((Boolean) graded.get("q1").get("correct"));
        assertEquals(0.6, (Double) graded.get("q1").get("hitRatio"));
        assertTrue((Boolean) graded.get("q1").get("isFuzzyMatch"));
    }

    @Test
    void submitShortAnswerBelow60PercentIsWrong() {
        Question q = shortAnswer("q1", "参考答案",
            "...\n【采分点】高内聚、低耦合、模块化、分层设计");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        var res = service.submit("e1", "u1",
            Map.of("q1", "我们要做到高内聚即可。"));
        assertEquals(0, res.get("score"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Map<String, Object>>) res.get("answers");
        assertFalse((Boolean) graded.get("q1").get("correct"));
        assertEquals(0.25, (Double) graded.get("q1").get("hitRatio"));
    }

    @Test
    void submitShortAnswerNoScorePointMarkerFallsBackExactMatch() {
        Question q = shortAnswer("q1", "标准答案文本",
            "普通解释，没有采分点标记");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        var r1 = service.submit("e1", "u1", Map.of("q1", "标准答案文本"));
        assertEquals(1, r1.get("score"));

        var r2 = service.submit("e1", "u1", Map.of("q1", "不一样的答案"));
        assertEquals(0, r2.get("score"));
    }

    @Test
    void submitShortAnswerEmptyExplanationFallsBackExact() {
        Question q = shortAnswer("q1", "对", "");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        var r = service.submit("e1", "u1", Map.of("q1", "对"));
        assertEquals(1, r.get("score"));
    }

    @Test
    void submitShortAnswerKeywordCoverageFallbackForSynonyms() {
        Question q = shortAnswer("q1", "信息隐藏是关键",
            "...\n【采分点】封装、数据隐藏");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        var res = service.submit("e1", "u1",
            Map.of("q1", "这个原则讲的是信息隐藏。"));
        assertEquals(0, res.get("score"));
    }

    @Test
    void submitShortAnswerScorePointMarkersInMiddleOfLine() {
        // 【采分点】出现在行中间，后面到换行符为止（换行是截断信号）
        Question q = shortAnswer("q1", "答案",
            "先讲一些东西 【采分点】Java、JVM、字节码\n后续的东西被忽略");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        var res = service.submit("e1", "u1",
            Map.of("q1", "Java JVM 字节码实现跨平台。"));
        assertEquals(1, res.get("score"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Map<String, Object>>) res.get("answers");
        assertEquals(1.0, (Double) graded.get("q1").get("hitRatio"));
    }

    @Test
    void submitShortAnswerKeywordsSeparatedByCommaAndSpace() {
        Question q = shortAnswer("q1", "答案",
            "...\n【采分点】A, B C，D");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        var res = service.submit("e1", "u1", Map.of("q1", "A B C D"));
        assertEquals(1, res.get("score"));
    }

    // ---------- 权限校验 ----------

    @Test
    void getExamWithWrongUserIdThrows() {
        Exam e = new Exam();
        e.setId("e1");
        e.setUserId("owner-u");
        e.setTitle("T");
        when(examMapper.findById("e1")).thenReturn(e);

        var ex = assertThrows(RuntimeException.class,
            () -> service.get("e1", "other-user"));
        assertEquals("无权访问该考试", ex.getMessage());
    }

    @Test
    void getExamWithRightUserIdSucceeds() {
        Exam e = new Exam();
        e.setId("e1");
        e.setUserId("owner-u");
        e.setTitle("Exam");
        when(examMapper.findById("e1")).thenReturn(e);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of());

        var res = service.get("e1", "owner-u");
        assertEquals("e1", res.get("id"));
        assertEquals("owner-u", res.get("userId"));
    }

    @Test
    void getExamAdminVersionBypassesPermission() {
        Exam e = new Exam();
        e.setId("e1");
        e.setUserId("u-else");
        e.setTitle("T");
        when(examMapper.findById("e1")).thenReturn(e);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of());

        var res = service.get("e1");
        assertNotNull(res);
        assertEquals("e1", res.get("id"));
    }

    @Test
    void deleteExamWithWrongUserIdThrows() {
        Exam e = new Exam();
        e.setId("e1");
        e.setUserId("owner");
        when(examMapper.findById("e1")).thenReturn(e);

        var ex = assertThrows(RuntimeException.class,
            () -> service.delete("e1", "intruder"));
        assertEquals("无权删除该考试", ex.getMessage());
        verify(examMapper, never()).deleteById("e1");
    }

    @Test
    void deleteExamNotExistThrows() {
        when(examMapper.findById("missing")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> service.delete("missing", "u1"));
        assertEquals("考试不存在", ex.getMessage());
    }

    @Test
    void deleteExamWithOwnerSucceeds() {
        Exam e = new Exam();
        e.setId("e1");
        e.setUserId("owner");
        when(examMapper.findById("e1")).thenReturn(e);
        doNothing().when(examMapper).deleteById("e1");

        service.delete("e1", "owner");
        verify(examMapper).deleteById("e1");
    }

    // ---------- list ----------

    @Test
    void listDelegatesToMapper() {
        Exam e1 = new Exam(); e1.setId("e1");
        Exam e2 = new Exam(); e2.setId("e2");
        when(examMapper.findByUserId("u1")).thenReturn(List.of(e1, e2));

        var list = service.list("u1");
        assertEquals(2, list.size());
    }

    // ---------- 考试尝试记录 ----------

    @Test
    void submitPersistsAttemptRecord() {
        Question q = choice("q1", "A");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        var res = service.submit("e1", "u1", Map.of("q1", "A"));

        assertNotNull(res.get("id"));
        ArgumentCaptor<com.exam.model.ExamAttempt> captor =
            ArgumentCaptor.forClass(com.exam.model.ExamAttempt.class);
        verify(attemptMapper).insert(captor.capture());
        var saved = captor.getValue();
        assertEquals("e1", saved.getExamId());
        assertEquals("u1", saved.getUserId());
        assertEquals(Integer.valueOf(1), saved.getScore());
        assertEquals(Integer.valueOf(1), saved.getTotal());
        assertEquals(res.get("id"), saved.getId());
    }

    // ---------- 错题本已存在时重复插入不抛异常 ----------

    @Test
    void submitWrongQuestionDuplicateIdSilentlyIgnored() {
        Question q = choice("q1", "A");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());
        doThrow(new RuntimeException("duplicate key")).when(wqMapper).insert(any());

        var res = service.submit("e1", "u1", Map.of("q1", "B"));
        assertEquals(0, res.get("score"));
    }

    // ---------- getTaskStatus ----------

    @Test
    void getTaskStatusDelegatesToTaskQueue() {
        // taskQueue.getStatus(prefix, taskId) 返回 store.get(prefix + taskId)
        taskQueue.store.put("exam:task:abc123", "DONE:result");

        var s = service.getTaskStatus("abc123");
        assertEquals("DONE:result", s);
    }
}
