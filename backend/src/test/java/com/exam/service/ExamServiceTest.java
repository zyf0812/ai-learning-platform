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
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
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
    private WrongQuestionMapper wrongQuestionMapper;
    @Mock
    private ExamAttemptMapper attemptMapper;
    @Mock
    private TaskQueueService taskQueue;

    private ExamService examService;

    @BeforeEach
    void setUp() {
        examService = new ExamService(examMapper, questionMapper, docMapper, deepSeek,
            ragService, wrongQuestionMapper, attemptMapper, taskQueue);
    }

    @Test
    void submitGradesExactMatchQuestions() {
        when(questionMapper.findByExamId("exam1"))
            .thenReturn(List.of(
                question("q1", "choice", "B", "解析"),
                question("q2", "fill", "Spring", "解析")));

        Map<String, Object> result = examService.submit("exam1", "u1", Map.of("q1", "B", "q2", "Spring"));

        assertEquals(2, ((Integer) result.get("score")).intValue());
        assertEquals(2, ((Integer) result.get("total")).intValue());

        Map<String, Object> g1 = gradedOf(result, "q1");
        assertEquals(true, g1.get("correct"));
        assertEquals(1.0, (Double) g1.get("hitRatio"), 1e-9);
        assertEquals(false, g1.get("isFuzzyMatch"));

        verify(wrongQuestionMapper, never()).insert(any());
        ArgumentCaptor<ExamAttempt> attemptCaptor = ArgumentCaptor.forClass(ExamAttempt.class);
        verify(attemptMapper).insert(attemptCaptor.capture());
        assertEquals(2, attemptCaptor.getValue().getScore());
        assertEquals(2, attemptCaptor.getValue().getTotal());
        assertTrue(attemptCaptor.getValue().getAnswers().contains("\"q1\""));
    }

    @Test
    void submitShortAnswerAtSixtyPercentHitsThreshold() {
        when(questionMapper.findByExamId("exam1"))
            .thenReturn(List.of(question("q1", "shortanswer", "参考答案",
                "解析【采分点】封装、继承、多态、抽象、接口")));

        // 命中 3/5 = 0.6，正好达到判对阈值
        Map<String, Object> result = examService.submit("exam1", "u1",
            Map.of("q1", "封装、继承和多态是核心特性"));

        Map<String, Object> g = gradedOf(result, "q1");
        assertEquals(true, g.get("correct"));
        assertEquals(true, g.get("isFuzzyMatch"));
        assertEquals(0.6, (Double) g.get("hitRatio"), 1e-9);
        assertEquals(1, ((Integer) result.get("score")).intValue());
        verify(wrongQuestionMapper, never()).insert(any());
    }

    @Test
    void submitShortAnswerBelowThresholdIsWrongAndRecorded() {
        when(questionMapper.findByExamId("exam1"))
            .thenReturn(List.of(question("q1", "shortanswer", "参考答案",
                "解析【采分点】封装、继承、多态、抽象、接口")));

        // 命中 2/5 = 0.4，低于阈值
        Map<String, Object> result = examService.submit("exam1", "u1",
            Map.of("q1", "封装和继承"));

        Map<String, Object> g = gradedOf(result, "q1");
        assertEquals(false, g.get("correct"));
        assertEquals(false, g.get("isFuzzyMatch"));
        assertEquals(0.4, (Double) g.get("hitRatio"), 1e-9);
        assertEquals(0, ((Integer) result.get("score")).intValue());

        ArgumentCaptor<WrongQuestion> captor = ArgumentCaptor.forClass(WrongQuestion.class);
        verify(wrongQuestionMapper).insert(captor.capture());
        assertEquals("u1_q1", captor.getValue().getId());
        assertEquals("u1", captor.getValue().getUserId());
        assertEquals("封装和继承", captor.getValue().getUserAnswer());
        assertEquals(false, captor.getValue().getMastered());
        assertEquals(0, captor.getValue().getReviewCount());
    }

    @Test
    void submitShortAnswerWithoutKeywordsFallsBackToExactMatch() {
        when(questionMapper.findByExamId("exam1"))
            .thenReturn(List.of(question("q1", "shortanswer", "参考答案", "普通解析，没有采分点标记")));

        Map<String, Object> result = examService.submit("exam1", "u1", Map.of("q1", "参考答案"));

        Map<String, Object> g = gradedOf(result, "q1");
        assertEquals(true, g.get("correct"));
        assertEquals(false, g.get("isFuzzyMatch"));
        assertEquals(1.0, (Double) g.get("hitRatio"), 1e-9);
    }

    @Test
    void getRejectsExamOwnedByAnotherUser() {
        Exam exam = new Exam();
        exam.setId("exam1");
        exam.setUserId("owner");
        when(examMapper.findById("exam1")).thenReturn(exam);

        var ex = assertThrows(RuntimeException.class, () -> examService.get("exam1", "intruder"));

        assertEquals("无权访问该考试", ex.getMessage());
        verify(questionMapper, never()).findByExamId(anyString());
    }

    @Test
    void getReturnsNullForMissingExam() {
        when(examMapper.findById("missing")).thenReturn(null);

        assertNull(examService.get("missing", "u1"));
    }

    @Test
    void deleteRejectsExamOwnedByAnotherUser() {
        Exam exam = new Exam();
        exam.setId("exam1");
        exam.setUserId("owner");
        when(examMapper.findById("exam1")).thenReturn(exam);

        var ex = assertThrows(RuntimeException.class, () -> examService.delete("exam1", "intruder"));

        assertEquals("无权删除该考试", ex.getMessage());
        verify(examMapper, never()).deleteById(anyString());
    }

    @Test
    void deleteRejectsMissingExam() {
        when(examMapper.findById("missing")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class, () -> examService.delete("missing", "u1"));

        assertEquals("考试不存在", ex.getMessage());
    }

    @Test
    @SuppressWarnings("unchecked")
    void generateAsyncSubmitsBackgroundTaskAndDeduplicatesQuestions() throws Exception {
        when(ragService.getChunksRandom("doc1", 8)).thenReturn(List.of("知识点片段"));

        Map<String, Object> result = examService.generateAsync(
            "u1", "标题", List.of("doc1"), List.of("choice"), 2, null, false);

        Exam exam = (Exam) result.get("exam");
        assertNotNull(exam.getId());
        assertEquals(exam.getId(), result.get("taskId"));
        verify(examMapper).insert(exam);

        ArgumentCaptor<Supplier<String>> taskCaptor = ArgumentCaptor.forClass(Supplier.class);
        verify(taskQueue).submit(eq(exam.getId()), taskCaptor.capture(), eq("exam:task:"));

        when(deepSeek.chat(anyString(), anyString())).thenReturn(
            "[{\"type\":\"choice\",\"content\":\"Q1\",\"options\":[\"A|a\"],\"answer\":\"A\",\"explanation\":\"e\"},"
                + "{\"type\":\"choice\",\"content\":\"Q1\",\"options\":[\"A|a\"],\"answer\":\"A\",\"explanation\":\"e\"}]");

        String status = taskCaptor.getValue().get();

        assertEquals(exam.getId(), status);
        ArgumentCaptor<Question> questionCaptor = ArgumentCaptor.forClass(Question.class);
        verify(questionMapper, times(1)).insert(questionCaptor.capture());
        assertEquals("Q1", questionCaptor.getValue().getContent());
    }

    @Test
    @SuppressWarnings("unchecked")
    void generateAsyncReportsMissingDocumentContent() {
        Map<String, Object> result = examService.generateAsync(
            "u1", "标题", List.of("doc1"), List.of("choice"), 1, null, false);

        Exam exam = (Exam) result.get("exam");
        ArgumentCaptor<Supplier<String>> taskCaptor = ArgumentCaptor.forClass(Supplier.class);
        verify(taskQueue).submit(eq(exam.getId()), taskCaptor.capture(), eq("exam:task:"));

        assertEquals("ERROR:请先上传文档", taskCaptor.getValue().get());
        verifyNoInteractions(deepSeek);
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
}
