package com.exam.service;

import com.exam.mapper.*;
import com.exam.model.Question;
import com.exam.model.WrongQuestion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 覆盖 ExamService 的判分逻辑（简答题模糊匹配阈值边界）与权限校验。
 * 这两类逻辑属于业务关键流程中的数据验证 / 权限校验，原仓库无任何覆盖。
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

    // ===== 权限校验 =====

    @Test
    void getBelongingToOtherUserThrows() {
        com.exam.model.Exam exam = new com.exam.model.Exam();
        exam.setId("e1");
        exam.setUserId("owner-u1");
        when(examMapper.findById("e1")).thenReturn(exam);

        var ex = assertThrows(RuntimeException.class,
            () -> examService.get("e1", "intruder-u2"));
        assertEquals("无权访问该考试", ex.getMessage());
        verify(questionMapper, never()).findByExamId(anyString());
    }

    @Test
    void getNonexistentExamReturnsNull() {
        when(examMapper.findById("missing")).thenReturn(null);

        assertNull(examService.get("missing", "u1"));
    }

    @Test
    void deleteBelongingToOtherUserThrows() {
        com.exam.model.Exam exam = new com.exam.model.Exam();
        exam.setId("e1");
        exam.setUserId("owner-u1");
        when(examMapper.findById("e1")).thenReturn(exam);

        var ex = assertThrows(RuntimeException.class,
            () -> examService.delete("e1", "intruder-u2"));
        assertEquals("无权删除该考试", ex.getMessage());
        verify(examMapper, never()).deleteById(anyString());
    }

    @Test
    void deleteNonexistentExamThrows() {
        when(examMapper.findById("missing")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> examService.delete("missing", "u1"));
        assertEquals("考试不存在", ex.getMessage());
    }

    // ===== 判分：客观题精确匹配 =====

    @Test
    void submitChoiceCorrectAnswerScores() {
        Question q = question("q1", "choice", "1+1=?", "A", "解析");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1", Map.of("q1", "A"));

        assertEquals(1, result.get("score"));
        assertEquals(1, result.get("total"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertEquals(Boolean.TRUE, graded.get("q1").get("correct"));
        assertEquals(1.0, graded.get("q1").get("hitRatio"));
        // 正确不应写错题本
        verify(wqMapper, never()).insert(any());
    }

    @Test
    void submitChoiceWrongAnswerRecordsToWrongQuestionBook() {
        Question q = question("q1", "choice", "1+1=?", "A", "解析");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1", Map.of("q1", "B"));

        assertEquals(0, result.get("score"));
        ArgumentCaptor<WrongQuestion> captor = ArgumentCaptor.forClass(WrongQuestion.class);
        verify(wqMapper).insert(captor.capture());
        WrongQuestion wq = captor.getValue();
        assertEquals("u1_q1", wq.getId());
        assertEquals("u1", wq.getUserId());
        assertEquals("q1", wq.getQuestionId());
        assertEquals("B", wq.getUserAnswer());
        assertFalse(wq.getMastered());
    }

    @Test
    void submitMissingAnswerTreatedAsWrong() {
        Question q = question("q1", "truefalse", "天空是蓝的", "正确", "解析");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1", Map.of());

        assertEquals(0, result.get("score"));
        verify(wqMapper).insert(any(WrongQuestion.class));
    }

    // ===== 判分：简答题模糊匹配阈值边界（FUZZY_THRESHOLD=0.6）=====

    @Test
    void shortAnswerHitRatioAtThresholdIsCorrectAndFuzzy() {
        // 5 个采分点，命中 3 个 → hitRatio=0.6，刚好达阈值 → 正确且为模糊匹配
        Question q = question("q1", "shortanswer", "说明面向对象特性",
                "参考答案", "解析【采分点】封装、继承、多态、抽象、接口");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1", Map.of("q1", "封装 继承 多态"));

        assertEquals(1, result.get("score"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertEquals(Boolean.TRUE, graded.get("q1").get("correct"));
        assertEquals(Boolean.TRUE, graded.get("q1").get("isFuzzyMatch"));
        assertEquals(0.6, graded.get("q1").get("hitRatio"));
    }

    @Test
    void shortAnswerHitRatioBelowThresholdIsIncorrect() {
        // 5 个采分点，命中 2 个 → hitRatio=0.4 < 0.6 → 错误
        Question q = question("q1", "shortanswer", "说明面向对象特性",
                "参考答案", "解析【采分点】封装、继承、多态、抽象、接口");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1", Map.of("q1", "封装 继承"));

        assertEquals(0, result.get("score"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertEquals(Boolean.FALSE, graded.get("q1").get("correct"));
        assertEquals(0.4, graded.get("q1").get("hitRatio"));
        verify(wqMapper).insert(any(WrongQuestion.class));
    }

    @Test
    void shortAnswerFullMatchIsCorrectButNotFuzzy() {
        Question q = question("q1", "shortanswer", "说明面向对象特性",
                "参考答案", "解析【采分点】封装、继承、多态、抽象、接口");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1",
            Map.of("q1", "封装 继承 多态 抽象 接口"));

        assertEquals(1, result.get("score"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertEquals(Boolean.TRUE, graded.get("q1").get("correct"));
        assertEquals(Boolean.FALSE, graded.get("q1").get("isFuzzyMatch"));
        assertEquals(1.0, graded.get("q1").get("hitRatio"));
    }

    @Test
    void shortAnswerWithoutKeywordsFallsBackToExactMatch() {
        // explanation 无【采分点】标记 → 回退精确匹配（区分大小写）
        Question q = question("q1", "shortanswer", "Spring 是什么",
                "Spring", "这是一个普通解析，无采分点");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        // 大小写不一致 → 错误
        var result = examService.submit("e1", "u1", Map.of("q1", "spring"));
        assertEquals(0, result.get("score"));

        // 完全一致 → 正确
        var result2 = examService.submit("e1", "u1", Map.of("q1", "Spring"));
        assertEquals(1, result2.get("score"));
    }

    @Test
    void shortAnswerKeywordCharCoverageBoundary() {
        // 关键词未被子串包含，但字符覆盖率 >= 0.5 仍判命中（关键词长度 <= 6）
        // 关键词 "隐藏"(2字)：答案含 "隐" 不含 "藏" → 覆盖率 1/2=0.5 → 命中
        // 关键词 "多态"(2字)：答案含 "态" 不含 "多" → 覆盖率 1/2=0.5 → 命中
        Question q = question("q1", "shortanswer", "说明特性",
                "参考答案", "解析【采分点】隐藏、多态");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1", Map.of("q1", "隐态"));

        // 两个关键词均通过字符覆盖命中 → hitRatio=1.0 → 正确
        assertEquals(1, result.get("score"));
    }

    @Test
    void shortAnswerLongKeywordOnlyMatchesBySubstring() {
        // 关键词长度 > 6 时不再走字符覆盖，仅子串匹配
        String longKw = "这是一个超过六字符的关键词"; // 12 字
        Question q = question("q1", "shortanswer", "说明",
                "参考答案", "解析【采分点】" + longKw + "、短词");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        // 仅命中 "短词"，长关键词因未子串包含且 >6 字符 → 不命中 → 0.5 < 0.6 → 错误
        var result = examService.submit("e1", "u1", Map.of("q1", "短词 答案中部分字符"));
        assertEquals(0, result.get("score"));

        // 长关键词被完整包含 → 全部命中 → 正确
        var result2 = examService.submit("e1", "u1",
            Map.of("q1", "短词 " + longKw));
        assertEquals(1, result2.get("score"));
    }

    @Test
    void submitPersistsAttemptRecord() {
        Question q = question("q1", "choice", "1+1=?", "A", "解析");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        examService.submit("e1", "u1", Map.of("q1", "A"));

        ArgumentCaptor<com.exam.model.ExamAttempt> captor =
            ArgumentCaptor.forClass(com.exam.model.ExamAttempt.class);
        verify(attemptMapper).insert(captor.capture());
        assertEquals("e1", captor.getValue().getExamId());
        assertEquals("u1", captor.getValue().getUserId());
        assertEquals(1, captor.getValue().getScore());
        assertEquals(1, captor.getValue().getTotal());
    }

    private static Question question(String id, String type, String content,
                                     String answer, String explanation) {
        Question q = new Question();
        q.setId(id);
        q.setType(type);
        q.setContent(content);
        q.setAnswer(answer);
        q.setExplanation(explanation);
        q.setOptions("[]");
        return q;
    }
}
