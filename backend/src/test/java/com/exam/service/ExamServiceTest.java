package com.exam.service;

import com.exam.mapper.DocumentMapper;
import com.exam.mapper.ExamAttemptMapper;
import com.exam.mapper.ExamMapper;
import com.exam.mapper.QuestionMapper;
import com.exam.mapper.WrongQuestionMapper;
import com.exam.model.Exam;
import com.exam.model.ExamAttempt;
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
        examService = new ExamService(examMapper, questionMapper, docMapper, deepSeek,
                ragService, wqMapper, attemptMapper, taskQueue);
    }

    private static Question question(String id, String type, String answer, String explanation) {
        Question q = new Question();
        q.setId(id);
        q.setExamId("exam1");
        q.setType(type);
        q.setContent("题目" + id);
        q.setAnswer(answer);
        q.setExplanation(explanation);
        return q;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> gradedOf(Map<String, Object> result, String questionId) {
        return (Map<String, Object>) ((Map<String, Object>) result.get("answers")).get(questionId);
    }

    @Test
    void exactAnswerIsTrimmedAndScored() {
        when(questionMapper.findByExamId("exam1"))
                .thenReturn(List.of(question("q1", "choice", "B", "解析")));

        Map<String, Object> result = examService.submit("exam1", "u1", Map.of("q1", "  B  "));

        assertEquals(1, result.get("score"));
        assertEquals(1, result.get("total"));
        Map<String, Object> graded = gradedOf(result, "q1");
        assertEquals(true, graded.get("correct"));
        assertEquals(1.0, graded.get("hitRatio"));
        assertEquals(false, graded.get("isFuzzyMatch"));
        verifyNoInteractions(wqMapper);
        verify(attemptMapper).insert(any(ExamAttempt.class));
    }

    @Test
    void wrongAnswerIsRecordedToWrongQuestionBook() {
        when(questionMapper.findByExamId("exam1"))
                .thenReturn(List.of(question("q1", "truefalse", "正确", "")));

        Map<String, Object> result = examService.submit("exam1", "u1", Map.of("q1", "错误"));

        assertEquals(0, result.get("score"));
        ArgumentCaptor<WrongQuestion> captor = ArgumentCaptor.forClass(WrongQuestion.class);
        verify(wqMapper).insert(captor.capture());
        WrongQuestion wq = captor.getValue();
        assertEquals("u1_q1", wq.getId());
        assertEquals("u1", wq.getUserId());
        assertEquals("q1", wq.getQuestionId());
        assertEquals("exam1", wq.getExamId());
        assertEquals("错误", wq.getUserAnswer());
        assertEquals(false, wq.getMastered());
        assertEquals(0, wq.getReviewCount());
    }

    @Test
    void shortAnswerHittingExactlySixtyPercentIsCorrectAndFuzzy() {
        String explanation = "解析【采分点】封装、继承、多态、接口、抽象";
        when(questionMapper.findByExamId("exam1"))
                .thenReturn(List.of(question("q1", "shortanswer", "参考答案", explanation)));

        // 命中 3/5 = 0.6，恰好达到阈值
        Map<String, Object> result = examService.submit("exam1", "u1",
                Map.of("q1", "封装 继承 多态"));

        Map<String, Object> graded = gradedOf(result, "q1");
        assertEquals(true, graded.get("correct"));
        assertEquals(true, graded.get("isFuzzyMatch"));
        assertEquals(0.6, graded.get("hitRatio"));
        assertEquals(1, result.get("score"));
        verifyNoInteractions(wqMapper);
    }

    @Test
    void shortAnswerBelowThresholdIsIncorrect() {
        String explanation = "解析【采分点】封装、继承、多态、接口、抽象";
        when(questionMapper.findByExamId("exam1"))
                .thenReturn(List.of(question("q1", "shortanswer", "参考答案", explanation)));

        Map<String, Object> result = examService.submit("exam1", "u1", Map.of("q1", "封装"));

        Map<String, Object> graded = gradedOf(result, "q1");
        assertEquals(false, graded.get("correct"));
        assertEquals(false, graded.get("isFuzzyMatch"));
        assertEquals(0.2, graded.get("hitRatio"));
        verify(wqMapper).insert(any(WrongQuestion.class));
    }

    @Test
    void shortAnswerWithoutScoringMarkerFallsBackToExactMatch() {
        when(questionMapper.findByExamId("exam1"))
                .thenReturn(List.of(question("q1", "shortanswer", "Spring", "普通解析")));

        Map<String, Object> result = examService.submit("exam1", "u1", Map.of("q1", "Spring"));

        assertEquals(true, gradedOf(result, "q1").get("correct"));
        assertEquals(1, result.get("score"));
    }

    @Test
    void longKeywordRequiresExactSubstringInsteadOfCharacterCoverage() {
        String explanation = "解析【采分点】8种基本数据类型";
        when(questionMapper.findByExamId("exam1"))
                .thenReturn(List.of(question("q1", "shortanswer", "参考答案", explanation)));

        Map<String, Object> result = examService.submit("exam1", "u1", Map.of("q1", "8种数据"));

        // 关键词超过 6 字符时不做字符覆盖率模糊匹配，必须完整出现
        assertEquals(false, gradedOf(result, "q1").get("correct"));
    }

    @Test
    void shortKeywordUsesCharacterCoverageForSynonyms() {
        String explanation = "解析【采分点】数据隐藏";
        when(questionMapper.findByExamId("exam1"))
                .thenReturn(List.of(question("q1", "shortanswer", "参考答案", explanation)));

        // 与"数据隐藏"共享"隐藏"两字，字符覆盖率 2/4 = 0.5，判定命中
        Map<String, Object> result = examService.submit("exam1", "u1", Map.of("q1", "信息隐藏"));

        assertEquals(true, gradedOf(result, "q1").get("correct"));
        assertEquals(1, result.get("score"));
    }

    @Test
    void getExamRejectsUserWhoDoesNotOwnIt() {
        Exam exam = new Exam();
        exam.setId("exam1");
        exam.setUserId("owner");
        when(examMapper.findById("exam1")).thenReturn(exam);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> examService.get("exam1", "intruder"));
        assertEquals("无权访问该考试", ex.getMessage());
        verifyNoInteractions(questionMapper);
    }

    @Test
    void getMissingExamReturnsNull() {
        when(examMapper.findById("missing")).thenReturn(null);
        assertNull(examService.get("missing", "u1"));
    }

    @Test
    void deleteExamRejectsNonOwner() {
        Exam exam = new Exam();
        exam.setId("exam1");
        exam.setUserId("owner");
        when(examMapper.findById("exam1")).thenReturn(exam);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> examService.delete("exam1", "intruder"));
        assertEquals("无权删除该考试", ex.getMessage());
        verify(examMapper, never()).deleteById(any());
    }
}
