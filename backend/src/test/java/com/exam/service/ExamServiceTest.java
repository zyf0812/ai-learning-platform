package com.exam.service;

import com.exam.mapper.*;
import com.exam.model.ExamAttempt;
import com.exam.model.Question;
import com.exam.model.WrongQuestion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ExamService 核心判分逻辑测试。
 *
 * 重点覆盖：
 * 1. 选择题/填空题/判断题 精确匹配判分
 * 2. 简答题基于【采分点】的模糊匹配算法（hitRatio >= 0.6 判对）
 * 3. 简答题无采分点时回退到精确匹配
 * 4. parseKeywords 从 explanation 中正确解析【采分点】关键词（多种分隔符）
 * 5. keywordMatch 字符覆盖率匹配（解决同义词如"数据隐藏"vs"信息隐藏"）
 * 6. 错题自动写入错题本（重复错题 insert 吞异常不中断）
 * 7. ExamAttempt 持久化 + JSON 序列化
 * 8. get/delete 权限校验（非本人访问抛异常）
 * 9. 空答案 / 空输入边界条件
 *
 * 这是业务最关键的逻辑之一：判分错误直接影响用户成绩和体验，
 * 采分点解析缺陷会导致简答题大面积误判。
 */
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

    // ========== 选择题精确匹配 ==========

    @Test
    void submit_choiceQuestion_correctAnswer_scoresPoint() {
        Question q = buildQuestion("q1", "choice", "B", null);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, Object> result = examService.submit("e1", "u1", Map.of("q1", "B"));

        assertEquals(1, result.get("score"));
        assertEquals(1, result.get("total"));
        @SuppressWarnings("unchecked")
        Map<String, Object> answers = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> g = (Map<String, Object>) answers.get("q1");
        assertTrue((Boolean) g.get("correct"));
        assertEquals(1.0, g.get("hitRatio"));
    }

    @Test
    void submit_choiceQuestion_wrongAnswer_noScoreAndWrittenToWrongBook() {
        Question q = buildQuestion("q1", "choice", "B", null);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, Object> result = examService.submit("e1", "u1", Map.of("q1", "A"));

        assertEquals(0, result.get("score"));
        ArgumentCaptor<WrongQuestion> captor = ArgumentCaptor.forClass(WrongQuestion.class);
        verify(wqMapper).insert(captor.capture());
        WrongQuestion wq = captor.getValue();
        assertEquals("u1_q1", wq.getId());
        assertEquals("A", wq.getUserAnswer());
        assertFalse(wq.getMastered());
        assertEquals(0, wq.getReviewCount());
    }

    // ========== 填空题精确匹配 ==========

    @Test
    void submit_fillBlank_caseSensitiveExactMatch() {
        Question q = buildQuestion("q1", "fill", "Spring", null);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        // 完全匹配
        Map<String, Object> r1 = examService.submit("e1", "u1", Map.of("q1", "Spring"));
        assertEquals(1, r1.get("score"));

        // 大小写不同，不匹配
        Map<String, Object> r2 = examService.submit("e1", "u1", Map.of("q1", "spring"));
        assertEquals(0, r2.get("score"));
    }

    // ========== 判断题精确匹配 ==========

    @Test
    void submit_trueFalse_acceptsChineseTrueFalse() {
        Question q1 = buildQuestion("q1", "truefalse", "正确", null);
        Question q2 = buildQuestion("q2", "truefalse", "错误", null);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q1, q2));

        Map<String, String> ans = new HashMap<>();
        ans.put("q1", "正确");
        ans.put("q2", "错误");
        Map<String, Object> result = examService.submit("e1", "u1", ans);

        assertEquals(2, result.get("score"));
    }

    @Test
    void submit_trueFalse_wrongLiteralIsWrong() {
        Question q = buildQuestion("q1", "truefalse", "正确", null);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        // "true" 不是正确答案（答案是中文"正确"）
        Map<String, Object> r = examService.submit("e1", "u1", Map.of("q1", "true"));
        assertEquals(0, r.get("score"));
    }

    // ========== 简答题：【采分点】模糊匹配 ==========

    @Test
    void submit_shortAnswer_allKeywordsHit_isCorrect() {
        String expl = "面向对象有三大特性。【采分点】封装、继承、多态";
        Question q = buildQuestion("q1", "shortanswer", "三大特性是封装、继承和多态", expl);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        String userAns = "面向对象的三大特性包括封装、继承、多态";
        Map<String, Object> result = examService.submit("e1", "u1", Map.of("q1", userAns));

        assertEquals(1, result.get("score"));
        @SuppressWarnings("unchecked")
        Map<String, Object> g = getGraded(result, "q1");
        assertTrue((Boolean) g.get("correct"));
        assertEquals(1.0, g.get("hitRatio"));
        assertFalse((Boolean) g.get("isFuzzyMatch")); // 100% 命中不算模糊
    }

    @Test
    void submit_shortAnswer_partialHitOver60pct_isFuzzyCorrect() {
        // 5 个采分点：命中 3 个 = 60% -> 刚好达到 FUZZY_THRESHOLD=0.6
        String expl = "解释。【采分点】封装、继承、多态、抽象、接口";
        Question q = buildQuestion("q1", "shortanswer", "参考", expl);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        // 命中：封装、继承、多态 (3/5=0.6)
        String userAns = "主要特点有封装、继承、多态这几个";
        Map<String, Object> result = examService.submit("e1", "u1", Map.of("q1", userAns));

        assertEquals(1, result.get("score"), "60% 命中应判对");
        @SuppressWarnings("unchecked")
        Map<String, Object> g = getGraded(result, "q1");
        assertTrue((Boolean) g.get("correct"));
        assertTrue((Boolean) g.get("isFuzzyMatch"), "非100%命中应标记为模糊匹配");
        assertEquals(0.6, g.get("hitRatio"));
    }

    @Test
    void submit_shortAnswer_partialHitBelow60pct_isWrong() {
        // 5 个采分点，只命中 2 个 = 40% < 0.6
        String expl = "xxx【采分点】封装、继承、多态、抽象、接口";
        Question q = buildQuestion("q1", "shortanswer", "参考", expl);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        String userAns = "主要特点有封装和继承";
        Map<String, Object> result = examService.submit("e1", "u1", Map.of("q1", userAns));

        assertEquals(0, result.get("score"), "40% 命中不应判对");
        @SuppressWarnings("unchecked")
        Map<String, Object> g = getGraded(result, "q1");
        assertFalse((Boolean) g.get("correct"));
        assertEquals(0.4, g.get("hitRatio"));
    }

    @Test
    void submit_shortAnswer_keywordMatchByCharCoverage_forLongKeywords() {
        // keywordMatch 的字符覆盖率分支：
        // "数据隐藏" vs "信息隐藏" -> 共享"隐藏"两字，"数据隐藏"共4字，覆盖率2/4=0.5 -> 命中
        String expl = "xxx【采分点】数据隐藏";
        Question q = buildQuestion("q1", "shortanswer", "参考", expl);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        String userAns = "这体现了信息隐藏的原则";
        Map<String, Object> result = examService.submit("e1", "u1", Map.of("q1", userAns));

        assertEquals(1, result.get("score"),
            "字符覆盖率 >= 50% 时同义词应判对（数据隐藏 vs 信息隐藏共享'隐藏'二字）");
    }

    @Test
    void submit_shortAnswer_noScorePointMarker_fallbackExactMatch() {
        // 无【采分点】标记，回退到精确匹配
        String expl = "这是一段没有采分点标记的解释文本";
        Question q = buildQuestion("q1", "shortanswer", "精确答案", expl);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        // 答案不完全匹配 -> 错
        Map<String, Object> r1 = examService.submit("e1", "u1", Map.of("q1", "精确答案1"));
        assertEquals(0, r1.get("score"));

        // 完全匹配 -> 对
        Map<String, Object> r2 = examService.submit("e1", "u1", Map.of("q1", "精确答案"));
        assertEquals(1, r2.get("score"));
        @SuppressWarnings("unchecked")
        Map<String, Object> g = getGraded(r2, "q1");
        assertEquals(1.0, g.get("hitRatio"));
    }

    @Test
    void submit_shortAnswer_keywordsAcceptMultipleSeparators() {
        // 解析支持 顿号、逗号、中文逗号、空格 混合分隔
        String expl = "【采分点】封装,继承 多态，抽象";
        Question q = buildQuestion("q1", "shortanswer", "ref", expl);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        String userAns = "封装、继承、多态、抽象";
        Map<String, Object> result = examService.submit("e1", "u1", Map.of("q1", userAns));

        assertEquals(1, result.get("score"), "4 种分隔符混合的采分点应全部被正确解析并命中");
    }

    @Test
    void submit_shortAnswer_scorePointStopsAtNewline() {
        // 【采分点】后只解析到行尾，后续内容不被误识别为关键词
        String expl = "解释段。【采分点】封装、继承\n后面的段落是详细说明，不应被当关键词";
        Question q = buildQuestion("q1", "shortanswer", "ref", expl);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        String userAns = "包含封装和继承";
        Map<String, Object> result = examService.submit("e1", "u1", Map.of("q1", userAns));

        @SuppressWarnings("unchecked")
        Map<String, Object> g = getGraded(result, "q1");
        // 2 个关键词 2 个命中 = 1.0
        assertEquals(1.0, g.get("hitRatio"), "采分点只解析到行尾，不应混入后续段落");
        assertTrue((Boolean) g.get("correct"));
    }

    // ========== 边界条件 ==========

    @Test
    void submit_missingAnswer_isTreatedAsEmptyString() {
        Question q = buildQuestion("q1", "choice", "A", null);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        // 答案中完全没有 q1 这个 key
        Map<String, Object> result = examService.submit("e1", "u1", Map.of());

        assertEquals(0, result.get("score"));
        @SuppressWarnings("unchecked")
        Map<String, Object> g = getGraded(result, "q1");
        assertEquals("", g.get("userAnswer"));
        assertFalse((Boolean) g.get("correct"));
    }

    @Test
    void submit_whitespaceAnswer_isTrimmedBeforeCompare() {
        Question q = buildQuestion("q1", "choice", "B", null);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, Object> r = examService.submit("e1", "u1", Map.of("q1", "  B  "));
        assertEquals(1, r.get("score"), "用户答案前后空白应被 trim");
    }

    @Test
    void submit_duplicateWrongQuestion_swallowExceptionNotInterrupt() {
        // 同一用户同一题再次做错，wqMapper.insert 由于主键 (userId_questionId) 冲突抛异常
        // Service 应该吞异常，继续后续判分和持久化
        Question q1 = buildQuestion("q1", "choice", "A", null);
        Question q2 = buildQuestion("q2", "choice", "C", null);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q1, q2));
        doThrow(new RuntimeException("主键冲突")).when(wqMapper).insert(any(WrongQuestion.class));

        Map<String, String> ans = Map.of("q1", "B", "q2", "D"); // 两道都错
        Map<String, Object> result = examService.submit("e1", "u1", ans);

        assertEquals(0, result.get("score"));
        assertEquals(2, result.get("total"));
        verify(attemptMapper).insert(any(ExamAttempt.class)); // 尽管 insert wq 报错，attempt 仍应保存
    }

    @Test
    void submit_attemptRecord_isPersistedWithCorrectFields() {
        Question q = buildQuestion("q1", "choice", "A", null);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        examService.submit("e1", "u1", Map.of("q1", "A"));

        ArgumentCaptor<ExamAttempt> captor = ArgumentCaptor.forClass(ExamAttempt.class);
        verify(attemptMapper).insert(captor.capture());
        ExamAttempt attempt = captor.getValue();
        assertEquals("e1", attempt.getExamId());
        assertEquals("u1", attempt.getUserId());
        assertEquals(1, attempt.getScore());
        assertEquals(1, attempt.getTotal());
        assertNotNull(attempt.getAnswers(), "answers JSON 不应为 null");
        assertTrue(attempt.getAnswers().contains("\"correct\":true"), "answers 应包含 graded JSON");
    }

    // ========== 权限校验 ==========

    @Test
    void get_withUserId_notOwner_throwsException() {
        var exam = new com.exam.model.Exam();
        exam.setId("e1");
        exam.setUserId("owner");
        exam.setTitle("t");
        when(examMapper.findById("e1")).thenReturn(exam);

        var ex = assertThrows(RuntimeException.class,
            () -> examService.get("e1", "otherUser"));
        assertEquals("无权访问该考试", ex.getMessage());
    }

    @Test
    void get_withUserId_notFound_returnsNull() {
        when(examMapper.findById("nonexist")).thenReturn(null);
        assertNull(examService.get("nonexist", "any"));
    }

    @Test
    void delete_notOwner_throwsAccessDenied() {
        var exam = new com.exam.model.Exam();
        exam.setId("e1");
        exam.setUserId("owner");
        when(examMapper.findById("e1")).thenReturn(exam);

        var ex = assertThrows(RuntimeException.class,
            () -> examService.delete("e1", "intruder"));
        assertEquals("无权删除该考试", ex.getMessage());
        verify(examMapper, never()).deleteById(anyString());
    }

    @Test
    void delete_notExist_throwsNotFound() {
        when(examMapper.findById("e1")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class,
            () -> examService.delete("e1", "u1"));
        assertEquals("考试不存在", ex.getMessage());
    }

    @Test
    void delete_adminOverload_noPermissionCheck_deletesDirectly() {
        // delete(String id) 无 userId 的重载，供管理员使用，直接删
        examService.delete("e1");
        verify(examMapper).deleteById("e1");
    }

    // ========== hitRatio 舍入精度 ==========

    @Test
    void submit_shortAnswer_hitRatioRoundedToTwoDecimals() {
        // 3 个关键词命中 1 个 = 0.3333... -> 应舍入为 0.33
        String expl = "【采分点】封装、继承、多态";
        Question q = buildQuestion("q1", "shortanswer", "ref", expl);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        String userAns = "只有封装这一个概念"; // 仅命中"封装"
        Map<String, Object> result = examService.submit("e1", "u1", Map.of("q1", userAns));

        @SuppressWarnings("unchecked")
        Map<String, Object> g = getGraded(result, "q1");
        double ratio = (Double) g.get("hitRatio");
        assertEquals(0.33, ratio, 0.001, "hitRatio 保留 2 位小数");
    }

    // ========== 辅助 ==========

    @SuppressWarnings("unchecked")
    private Map<String, Object> getGraded(Map<String, Object> result, String qid) {
        Map<String, Object> answers = (Map<String, Object>) result.get("answers");
        return (Map<String, Object>) answers.get(qid);
    }

    private Question buildQuestion(String id, String type, String answer, String explanation) {
        Question q = new Question();
        q.setId(id);
        q.setExamId("e1");
        q.setType(type);
        q.setContent(type + " 示例题目");
        q.setAnswer(answer);
        q.setExplanation(explanation != null ? explanation : "");
        q.setOptions("[]");
        return q;
    }
}
