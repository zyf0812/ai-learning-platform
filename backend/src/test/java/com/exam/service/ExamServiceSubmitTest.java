package com.exam.service;

import com.exam.mapper.*;
import com.exam.model.ExamAttempt;
import com.exam.model.Question;
import com.exam.model.WrongQuestion;
import com.fasterxml.jackson.databind.ObjectMapper;
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

@ExtendWith(MockitoExtension.class)
class ExamServiceSubmitTest {

    // 接口 Mapper：安全使用 Mockito @Mock
    @Mock private ExamMapper examMapper;
    @Mock private QuestionMapper questionMapper;
    @Mock private WrongQuestionMapper wrongQuestionMapper;
    @Mock private ExamAttemptMapper attemptMapper;
    // DocumentMapper 也是 MyBatis 接口
    @Mock private DocumentMapper documentMapper;

    private ExamService service;

    @BeforeEach
    void setUp() {
        // submit() / get() / delete() 不调用 DeepSeekService、RAGService、TaskQueueService。
        // 由于 Java 25 下 Mockito 无法子类化这些具体类，直接传 null。
        service = new ExamService(
            examMapper, questionMapper, documentMapper,
            null, null,
            wrongQuestionMapper, attemptMapper, null
        );
    }

    private Question makeQuestion(String id, String type, String answer, String explanation) {
        Question q = new Question();
        q.setId(id);
        q.setType(type);
        q.setAnswer(answer);
        q.setExplanation(explanation);
        q.setContent(type + " 题目内容");
        return q;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getGraded(Map<String, Object> result, String qid) {
        return (Map<String, Object>) ((Map<String, Object>) result.get("answers")).get(qid);
    }

    // ==================== 选择题：精确匹配 ====================

    @Test
    void submitChoiceCorrectScoresPoint() {
        Question q = makeQuestion("q1", "choice", "B", "");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        Map<String, Object> result = service.submit("e1", "u1", Map.of("q1", "B"));

        assertEquals(1, result.get("score"));
        assertEquals(1, result.get("total"));
        assertTrue((Boolean) getGraded(result, "q1").get("correct"));
        assertEquals(1.0, getGraded(result, "q1").get("hitRatio"));
        assertFalse((Boolean) getGraded(result, "q1").get("isFuzzyMatch"));
    }

    @Test
    void submitChoiceWrongNoScore() {
        Question q = makeQuestion("q1", "choice", "B", "");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        Map<String, Object> result = service.submit("e1", "u1", Map.of("q1", "A"));

        assertEquals(0, result.get("score"));
        assertFalse((Boolean) getGraded(result, "q1").get("correct"));
    }

    // ==================== 判断题：精确匹配 ====================

    @Test
    void submitTrueFalseCorrectScores() {
        Question q = makeQuestion("q-tf", "truefalse", "正确", "");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        Map<String, Object> result = service.submit("e1", "u1", Map.of("q-tf", "正确"));
        assertEquals(1, result.get("score"));
    }

    @Test
    void submitTrueFalseChineseVariants() {
        // 精确匹配：错误 ≠ 正确
        Question q = makeQuestion("q-tf2", "truefalse", "错误", "");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        Map<String, Object> result = service.submit("e1", "u1", Map.of("q-tf2", "正确"));
        assertEquals(0, result.get("score"));
    }

    // ==================== 填空题：精确匹配 ====================

    @Test
    void submitFillBlankTrimsWhitespace() {
        Question q = makeQuestion("q-f", "fill", "Spring", "");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        // 前后空格被 trim，应该判正确
        Map<String, Object> result = service.submit("e1", "u1", Map.of("q-f", "  Spring  "));
        assertEquals(1, result.get("score"));
    }

    // ==================== 简答题：无采分点 → 精确匹配 ====================

    @Test
    void submitShortAnswerNoKeywordsExactMatch() {
        Question q = makeQuestion("q-sa", "shortanswer", "封装继承多态",
            "面向对象三大特性。"); // 没有【采分点】标记
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        Map<String, Object> r = service.submit("e1", "u1", Map.of("q-sa", "封装继承多态"));
        assertEquals(1, r.get("score"));
        assertEquals(1.0, getGraded(r, "q-sa").get("hitRatio"));
        assertFalse((Boolean) getGraded(r, "q-sa").get("isFuzzyMatch"));
    }

    @Test
    void submitShortAnswerNoKeywordsExactMismatch() {
        Question q = makeQuestion("q-sa", "shortanswer", "封装继承多态",
            "面向对象三大特性。");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        Map<String, Object> r = service.submit("e1", "u1", Map.of("q-sa", "完全不相关"));
        assertEquals(0, r.get("score"));
        assertEquals(0.0, getGraded(r, "q-sa").get("hitRatio"));
    }

    // ==================== 简答题：有采分点 → 模糊匹配 ====================

    @Test
    void submitShortAnswerAllKeywordsHit() {
        Question q = makeQuestion("q-sa", "shortanswer", "参考回答内容",
            "详细解析。【采分点】封装、继承、多态");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        // 用户答案包含全部3个关键词
        Map<String, Object> r = service.submit("e1", "u1",
            Map.of("q-sa", "面向对象的核心是封装、继承和多态"));

        assertEquals(1, r.get("score"));
        assertEquals(1.0, getGraded(r, "q-sa").get("hitRatio"));
        // 100% 命中不算 fuzzy
        assertFalse((Boolean) getGraded(r, "q-sa").get("isFuzzyMatch"));
    }

    @Test
    void submitShortAnswerExactlyAtThresholdBoundary() {
        // 5个关键词，命中3个 → 3/5 = 0.6 → 刚好等于阈值 FUZZY_THRESHOLD = 0.6 → 判正确
        Question q = makeQuestion("q-sa", "shortanswer", "参考",
            "解析【采分点】A、B、C、D、E");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        Map<String, Object> r = service.submit("e1", "u1",
            Map.of("q-sa", "包含 a 和 b 还有 c"));

        assertTrue((Boolean) getGraded(r, "q-sa").get("correct"));
        assertEquals(0.6, getGraded(r, "q-sa").get("hitRatio"));
        assertTrue((Boolean) getGraded(r, "q-sa").get("isFuzzyMatch")); // 命中但<1.0
    }

    @Test
    void submitShortAnswerBelowThresholdIsWrong() {
        // 5个关键词，命中2个 → 2/5 = 0.4 < 0.6 → 判错
        Question q = makeQuestion("q-sa", "shortanswer", "参考",
            "解析【采分点】A、B、C、D、E");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        Map<String, Object> r = service.submit("e1", "u1",
            Map.of("q-sa", "只有 a 和 b"));

        assertFalse((Boolean) getGraded(r, "q-sa").get("correct"));
        assertEquals(0.4, getGraded(r, "q-sa").get("hitRatio"));
    }

    @Test
    void submitShortAnswerPartialHitFuzzyMatch() {
        // 4个关键词，命中3个 → 0.75 ≥ 0.6 判对，且 isFuzzyMatch=true
        Question q = makeQuestion("q-sa", "shortanswer", "参考",
            "解析【采分点】封装、继承、多态、抽象");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        Map<String, Object> r = service.submit("e1", "u1",
            Map.of("q-sa", "OOP有封装、继承、多态三个特性"));

        assertTrue((Boolean) getGraded(r, "q-sa").get("correct"));
        assertEquals(0.75, getGraded(r, "q-sa").get("hitRatio"));
        assertTrue((Boolean) getGraded(r, "q-sa").get("isFuzzyMatch"));
    }

    // ==================== 关键词匹配：字符覆盖率回退（同义词） ====================

    @Test
    void submitShortAnswerCharacterCoverageFallbackForSynonyms() {
        // "数据隐藏" vs 用户答案"信息隐藏"：子串都不包含，但共享"隐藏"2字
        // keyword="数据隐藏" → 4字符，其中"隐藏"2字命中 → 2/4=0.5 → coverage>=0.5 → 命中
        Question q = makeQuestion("q-sa", "shortanswer", "参考",
            "解析【采分点】数据隐藏");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        Map<String, Object> r = service.submit("e1", "u1",
            Map.of("q-sa", "OOP原则包括信息隐藏"));

        assertTrue((Boolean) getGraded(r, "q-sa").get("correct"));
        assertEquals(1.0, getGraded(r, "q-sa").get("hitRatio"));
    }

    @Test
    void submitShortAnswerLongKeywordSkipsCharacterCoverage() {
        // 关键词长度 > 6 时，字符覆盖率回退被禁用（直接返回false）
        // 只能靠子串包含命中
        Question q = makeQuestion("q-sa", "shortanswer", "参考",
            "解析【采分点】Java虚拟机内存模型"); // 9字 > 6
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        // 用户答案只有"内存"2字，字符覆盖率 2/8（不重复字符）不够，且没有子串包含 → 不命中
        Map<String, Object> r = service.submit("e1", "u1",
            Map.of("q-sa", "内存管理很重要"));

        assertFalse((Boolean) getGraded(r, "q-sa").get("correct"));
        assertEquals(0.0, getGraded(r, "q-sa").get("hitRatio"));
    }

    // ==================== 采分点解析：分隔符与截断 ====================

    @Test
    void submitShortAnswerKeywordsSeparatedByComma() {
        Question q = makeQuestion("q-sa", "shortanswer", "参考",
            "解析【采分点】A, B, C，D E"); // 混合分隔符
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        Map<String, Object> r = service.submit("e1", "u1",
            Map.of("q-sa", "a b c d e"));

        assertTrue((Boolean) getGraded(r, "q-sa").get("correct"));
        assertEquals(1.0, getGraded(r, "q-sa").get("hitRatio"));
    }

    @Test
    void submitShortAnswerKeywordsTruncatedAtNewline() {
        // 采分点后有换行内容，应截断到行尾
        Question q = makeQuestion("q-sa", "shortanswer", "参考",
            "解析【采分点】封装、继承\n后续讲解更多内容...");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        // "后续"不应被当作关键词。只有封装、继承2个。命中2个 → 100%
        Map<String, Object> r = service.submit("e1", "u1",
            Map.of("q-sa", "封装和继承是核心"));

        assertTrue((Boolean) getGraded(r, "q-sa").get("correct"));
        assertEquals(1.0, getGraded(r, "q-sa").get("hitRatio"));
    }

    @Test
    void submitShortAnswerEmptyExplanationFallsBackExact() {
        Question q = makeQuestion("q-sa", "shortanswer", "精确答案", "");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        Map<String, Object> r = service.submit("e1", "u1", Map.of("q-sa", "精确答案"));
        assertEquals(1, r.get("score"));
    }

    // ==================== 未作答（空答案） ====================

    @Test
    void submitMissingAnswerDefaultEmptyString() {
        Question q = makeQuestion("q1", "choice", "A", "");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        Map<String, Object> r = service.submit("e1", "u1", Map.of()); // 没答这道题
        assertEquals(0, r.get("score"));
        assertEquals("", getGraded(r, "q1").get("userAnswer"));
    }

    // ==================== 错题写入错题本 ====================

    @Test
    void submitWrongAnswerInsertsWrongQuestion() {
        Question q = makeQuestion("q1", "choice", "A", "");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        service.submit("e1", "u1", Map.of("q1", "B"));

        ArgumentCaptor<WrongQuestion> captor = ArgumentCaptor.forClass(WrongQuestion.class);
        verify(wrongQuestionMapper).insert(captor.capture());
        WrongQuestion wq = captor.getValue();
        assertEquals("u1_q1", wq.getId());
        assertEquals("u1", wq.getUserId());
        assertEquals("q1", wq.getQuestionId());
        assertEquals("e1", wq.getExamId());
        assertEquals("B", wq.getUserAnswer());
        assertFalse(wq.getMastered());
        assertEquals(0, wq.getReviewCount());
    }

    @Test
    void submitCorrectAnswerDoesNotInsertWrongQuestion() {
        Question q = makeQuestion("q1", "choice", "A", "");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        service.submit("e1", "u1", Map.of("q1", "A"));

        verify(wrongQuestionMapper, never()).insert(any());
    }

    @Test
    void submitWrongQuestionInsertErrorSwallowed() {
        // wqMapper.insert 抛异常（如唯一键冲突）不应影响整体提交
        Question q = makeQuestion("q1", "choice", "A", "");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());
        doThrow(new RuntimeException("Duplicate key")).when(wrongQuestionMapper).insert(any());

        assertDoesNotThrow(() -> service.submit("e1", "u1", Map.of("q1", "B")));
    }

    // ==================== 考试记录持久化 ====================

    @Test
    void submitPersistsExamAttempt() {
        Question q = makeQuestion("q1", "choice", "A", "");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        Map<String, Object> result = service.submit("e1", "u1", Map.of("q1", "A"));

        ArgumentCaptor<ExamAttempt> captor = ArgumentCaptor.forClass(ExamAttempt.class);
        verify(attemptMapper).insert(captor.capture());
        ExamAttempt att = captor.getValue();
        assertEquals("e1", att.getExamId());
        assertEquals("u1", att.getUserId());
        assertEquals(1, att.getScore());
        assertEquals(1, att.getTotal());
        assertNotNull(att.getId());
        assertNotNull(att.getAnswers());
        // 返回的 attemptId 应与持久化的 ID 一致
        assertEquals(att.getId(), result.get("id"));
    }

    @Test
    void submitAttemptInsertErrorSwallowed() {
        Question q = makeQuestion("q1", "choice", "A", "");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doThrow(new RuntimeException("DB down")).when(attemptMapper).insert(any());

        assertDoesNotThrow(() -> {
            Map<String, Object> r = service.submit("e1", "u1", Map.of("q1", "A"));
            // result 仍然返回，因为异常被吞
            assertEquals(1, r.get("score"));
        });
    }

    // ==================== 多题混合场景 ====================

    @Test
    void submitMixedQuestionsPartialCorrectScore() {
        Question q1 = makeQuestion("q1", "choice", "A", "");
        Question q2 = makeQuestion("q2", "fill", "答案2", "");
        Question q3 = makeQuestion("q3", "shortanswer", "参考",
            "解析【采分点】关键词1、关键词2、关键词3");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q1, q2, q3));
        doNothing().when(attemptMapper).insert(any());

        // q1对 q2错 q3对(3个关键词全中)
        Map<String, Object> r = service.submit("e1", "u1", Map.of(
            "q1", "A",
            "q2", "错误答案",
            "q3", "答案包括关键词1、关键词2和关键词3"
        ));

        assertEquals(2, r.get("score"));
        assertEquals(3, r.get("total"));
    }

    // ==================== 权限校验（delete/get 方法） ====================

    @Test
    void deleteWithWrongOwnerThrows() {
        com.exam.model.Exam exam = new com.exam.model.Exam();
        exam.setId("e1");
        exam.setUserId("owner");
        when(examMapper.findById("e1")).thenReturn(exam);

        var ex = assertThrows(RuntimeException.class,
            () -> service.delete("e1", "hacker"));
        assertEquals("无权删除该考试", ex.getMessage());
    }

    @Test
    void deleteNonExistentThrows() {
        when(examMapper.findById("ghost")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> service.delete("ghost", "any"));
        assertEquals("考试不存在", ex.getMessage());
    }

    @Test
    void getNonOwnerThrows() {
        com.exam.model.Exam exam = new com.exam.model.Exam();
        exam.setId("e1");
        exam.setUserId("owner");
        when(examMapper.findById("e1")).thenReturn(exam);

        var ex = assertThrows(RuntimeException.class,
            () -> service.get("e1", "other"));
        assertEquals("无权访问该考试", ex.getMessage());
    }

    @Test
    void getNonExistentReturnsNull() {
        when(examMapper.findById("ghost")).thenReturn(null);

        assertNull(service.get("ghost", "any"));
    }
}
