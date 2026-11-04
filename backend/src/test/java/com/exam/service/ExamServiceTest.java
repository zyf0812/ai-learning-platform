package com.exam.service;

import com.exam.mapper.DocumentMapper;
import com.exam.mapper.ExamAttemptMapper;
import com.exam.mapper.ExamMapper;
import com.exam.mapper.QuestionMapper;
import com.exam.mapper.WrongQuestionMapper;
import com.exam.model.Document;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
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

    private Question question(String id, String type, String answer, String explanation) {
        Question q = new Question();
        q.setId(id);
        q.setExamId("e1");
        q.setType(type);
        q.setAnswer(answer);
        q.setExplanation(explanation);
        return q;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> gradeOf(Map<String, Object> result, String questionId) {
        return (Map<String, Object>) ((Map<String, Object>) result.get("answers")).get(questionId);
    }

    @Test
    void submitScoresExactMatchesAndTreatsMissingAnswerAsWrong() {
        Question choice = question("q1", "choice", "B", "解析");
        Question trueFalse = question("q2", "truefalse", "正确", "解析");
        Question unanswered = question("q3", "choice", "C", "解析");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(choice, trueFalse, unanswered));

        Map<String, Object> result = examService.submit("e1", "u1",
            Map.of("q1", "B", "q2", " 正确 "));

        assertEquals(2, result.get("score"));
        assertEquals(3, result.get("total"));
        assertTrue((Boolean) gradeOf(result, "q1").get("correct"));
        assertTrue((Boolean) gradeOf(result, "q2").get("correct"));
        assertFalse((Boolean) gradeOf(result, "q3").get("correct"));
        assertEquals("", gradeOf(result, "q3").get("userAnswer"));

        ArgumentCaptor<WrongQuestion> wq = ArgumentCaptor.forClass(WrongQuestion.class);
        verify(wqMapper, times(1)).insert(wq.capture());
        assertEquals("q3", wq.getValue().getQuestionId());

        ArgumentCaptor<ExamAttempt> attempt = ArgumentCaptor.forClass(ExamAttempt.class);
        verify(attemptMapper).insert(attempt.capture());
        assertEquals(2, attempt.getValue().getScore());
        assertEquals(3, attempt.getValue().getTotal());
        assertTrue(attempt.getValue().getAnswers().contains("\"q1\""));
    }

    @Test
    void submitGradesShortAnswerAsCorrectWhenSixtyPercentKeywordsHit() {
        Question q = question("q1", "shortanswer", "标准答案",
            "解析【采分点】封装、继承、多态");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, Object> result = examService.submit("e1", "u1", Map.of("q1", "封装和继承是特性"));

        assertEquals(1, result.get("score"));
        assertTrue((Boolean) gradeOf(result, "q1").get("correct"));
        assertTrue((Boolean) gradeOf(result, "q1").get("isFuzzyMatch"));
        assertEquals(0.67, gradeOf(result, "q1").get("hitRatio"));
        verify(wqMapper, never()).insert(any(WrongQuestion.class));
    }

    @Test
    void submitGradesShortAnswerBelowThresholdAsWrong() {
        Question q = question("q1", "shortanswer", "标准答案",
            "解析【采分点】封装、继承、多态");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, Object> result = examService.submit("e1", "u1", Map.of("q1", "只提到封装"));

        assertEquals(0, result.get("score"));
        assertFalse((Boolean) gradeOf(result, "q1").get("correct"));
        assertEquals(0.33, gradeOf(result, "q1").get("hitRatio"));
        verify(wqMapper).insert(any(WrongQuestion.class));
    }

    @Test
    void submitShortAnswerWithoutKeywordsFallsBackToExactMatch() {
        Question exact = question("q1", "shortanswer", "abc", "普通解析，无采分点");
        Question partial = question("q2", "shortanswer", "abc", null);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(exact, partial));

        Map<String, Object> result = examService.submit("e1", "u1",
            Map.of("q1", "abc", "q2", "abcd"));

        assertTrue((Boolean) gradeOf(result, "q1").get("correct"));
        assertEquals(1.0, gradeOf(result, "q1").get("hitRatio"));
        assertFalse((Boolean) gradeOf(result, "q1").get("isFuzzyMatch"));
        assertFalse((Boolean) gradeOf(result, "q2").get("correct"));
    }

    @Test
    void submitShortKeywordUsesCharacterCoverageForSynonymTolerance() {
        Question q = question("q1", "shortanswer", "数据隐藏",
            "【采分点】数据隐藏");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, Object> result = examService.submit("e1", "u1", Map.of("q1", "信息隐藏"));

        // "数据隐藏" 中「隐藏」两字命中，字符覆盖率 50% 判定为命中
        assertTrue((Boolean) gradeOf(result, "q1").get("correct"));
        assertEquals(1.0, gradeOf(result, "q1").get("hitRatio"));
    }

    @Test
    void getRejectsExamOwnedByAnotherUser() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("owner");
        when(examMapper.findById("e1")).thenReturn(exam);

        var ex = assertThrows(RuntimeException.class,
            () -> examService.get("e1", "intruder"));
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
        exam.setId("e1");
        exam.setUserId("owner");
        when(examMapper.findById("e1")).thenReturn(exam);

        var ex = assertThrows(RuntimeException.class,
            () -> examService.delete("e1", "intruder"));
        assertEquals("无权删除该考试", ex.getMessage());
        verify(examMapper, never()).deleteById(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void generateAsyncDeduplicatesQuestionsReturnedByModel() throws Exception {
        doNothing().when(examMapper).insert(any(Exam.class));
        when(ragService.getChunksRandom("d1", 8)).thenReturn(List.of("知识点片段"));
        Document doc = new Document();
        doc.setId("d1");
        doc.setUserId("u1");
        doc.setIsQuestionBank(false);
        when(docMapper.findById("d1")).thenReturn(doc);
        when(deepSeek.chat(anyString(), anyString())).thenReturn(
            "[{\"type\":\"choice\",\"content\":\"重复题目\",\"options\":[\"A|a\",\"B|b\"],\"answer\":\"B\",\"explanation\":\"e\"},"
          + "{\"type\":\"choice\",\"content\":\"重复题目\",\"options\":[\"A|a\",\"B|b\"],\"answer\":\"B\",\"explanation\":\"e\"}]");

        AtomicReference<String> taskResult = new AtomicReference<>();
        doAnswer(inv -> {
            taskResult.set(((Supplier<String>) inv.getArgument(1)).get());
            return null;
        }).when(taskQueue).submit(anyString(), any(), anyString());

        Map<String, Object> result = examService.generateAsync(
            "u1", "测试考试", List.of("d1"), List.of("choice"), 2, null, false);

        assertEquals(result.get("taskId"), taskResult.get());
        verify(questionMapper, times(1)).insert(any(Question.class));
        verify(questionMapper, times(1)).insert(argThat(q -> "重复题目".equals(q.getContent())));

        ArgumentCaptor<Exam> examCaptor = ArgumentCaptor.forClass(Exam.class);
        verify(examMapper).insert(examCaptor.capture());
        assertEquals("choice", examCaptor.getValue().getQuestionTypes());
        assertEquals(2, examCaptor.getValue().getQuestionCount());
        assertEquals("u1", examCaptor.getValue().getUserId());
    }

    @Test
    void generateAsyncReportsErrorWhenNoDocumentContent() throws Exception {
        doNothing().when(examMapper).insert(any(Exam.class));
        when(ragService.getChunksRandom("d1", 8)).thenReturn(List.of());
        Document empty = new Document();
        empty.setId("d1");
        empty.setUserId("u1");
        when(docMapper.findById("d1")).thenReturn(empty);

        AtomicReference<String> taskResult = new AtomicReference<>();
        doAnswer(inv -> {
            taskResult.set(((Supplier<String>) inv.getArgument(1)).get());
            return null;
        }).when(taskQueue).submit(anyString(), any(), anyString());

        examService.generateAsync("u1", "空文档", List.of("d1"), List.of("choice"), 1, null, false);

        assertEquals("ERROR:请先上传文档", taskResult.get());
        verify(questionMapper, never()).insert(any(Question.class));
        verifyNoInteractions(deepSeek);
    }
}
