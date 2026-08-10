package com.exam.service;

import com.exam.mapper.*;
import com.exam.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

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

    // === submit() grading tests ===

    @Test
    void submitChoiceQuestionCorrectAnswer() {
        Question q = new Question();
        q.setId("q1");
        q.setExamId("e1");
        q.setType("choice");
        q.setContent("What is Java?");
        q.setAnswer("A");
        q.setOptions("[\"A|Language\",\"B|Framework\",\"C|Database\",\"D|OS\"]");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, String> answers = Map.of("q1", "A");
        var result = examService.submit("e1", "u1", answers);

        assertEquals(1, result.get("score"));
        assertEquals(1, result.get("total"));

        @SuppressWarnings("unchecked")
        var answersMap = (Map<String, Map<String, Object>>) result.get("answers");
        assertTrue((Boolean) answersMap.get("q1").get("correct"));
        assertEquals("A", answersMap.get("q1").get("correctAnswer"));
    }

    @Test
    void submitChoiceQuestionWrongAnswerCreatesWrongQuestion() {
        Question q = new Question();
        q.setId("q1");
        q.setExamId("e1");
        q.setType("choice");
        q.setAnswer("B");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, String> answers = Map.of("q1", "A");
        var result = examService.submit("e1", "u1", answers);

        assertEquals(0, result.get("score"));

        @SuppressWarnings("unchecked")
        var answersMap = (Map<String, Map<String, Object>>) result.get("answers");
        assertFalse((Boolean) answersMap.get("q1").get("correct"));

        verify(wqMapper).insert(any(WrongQuestion.class));
    }

    @Test
    void submitTrueFalseQuestionCorrect() {
        Question q = new Question();
        q.setId("q1");
        q.setType("truefalse");
        q.setAnswer("正确");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1", Map.of("q1", "正确"));
        assertEquals(1, result.get("score"));
    }

    @Test
    void submitTrueFalseQuestionWrong() {
        Question q = new Question();
        q.setId("q1");
        q.setType("truefalse");
        q.setAnswer("错误");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1", Map.of("q1", "正确"));
        assertEquals(0, result.get("score"));
        verify(wqMapper).insert(any(WrongQuestion.class));
    }

    @Test
    void submitFillQuestionExactMatch() {
        Question q = new Question();
        q.setId("q1");
        q.setType("fill");
        q.setAnswer("Spring Framework");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1", Map.of("q1", "Spring Framework"));
        assertEquals(1, result.get("score"));
    }

    @Test
    void submitFillQuestionWrongAnswer() {
        Question q = new Question();
        q.setId("q1");
        q.setType("fill");
        q.setAnswer("Spring Framework");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1", Map.of("q1", "Struts"));
        assertEquals(0, result.get("score"));
    }

    // === Short answer fuzzy matching tests ===

    @Test
    void submitShortAnswerWithKeywordsAllMatch() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("Java的核心特性包括封装、继承和多态");
        q.setExplanation("解析：Java三大特性【采分点】封装、继承、多态");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1",
                Map.of("q1", "Java的核心特性有封装、继承、多态"));

        assertEquals(1, result.get("score"));

        @SuppressWarnings("unchecked")
        var answersMap = (Map<String, Map<String, Object>>) result.get("answers");
        assertTrue((Boolean) answersMap.get("q1").get("isFuzzyMatch"));
        assertEquals(1.0, answersMap.get("q1").get("hitRatio"));
    }

    @Test
    void submitShortAnswerWithKeywordsPartialMatchAboveThreshold() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("Java的核心特性包括封装、继承和多态");
        q.setExplanation("解析：Java三大特性【采分点】封装、继承、多态");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1",
                Map.of("q1", "Java有封装和继承特性"));

        assertEquals(1, result.get("score"));

        @SuppressWarnings("unchecked")
        var answersMap = (Map<String, Map<String, Object>>) result.get("answers");
        assertTrue((Boolean) answersMap.get("q1").get("isFuzzyMatch"));
    }

    @Test
    void submitShortAnswerWithKeywordsBelowThreshold() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("Java的核心特性包括封装、继承和多态");
        q.setExplanation("解析：Java三大特性【采分点】封装、继承、多态");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1",
                Map.of("q1", "Java是一种编程语言"));

        assertEquals(0, result.get("score"));
    }

    @Test
    void submitShortAnswerNoKeywordsFallsBackToExact() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("Java的核心特性包括封装、继承和多态");
        q.setExplanation("解析：Java三大特性，没有采分点标记");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1",
                Map.of("q1", "Java的核心特性包括封装、继承和多态"));
        assertEquals(1, result.get("score"));

        @SuppressWarnings("unchecked")
        var answersMap = (Map<String, Map<String, Object>>) result.get("answers");
        assertFalse((Boolean) answersMap.get("q1").get("isFuzzyMatch"));
        assertEquals(1.0, answersMap.get("q1").get("hitRatio"));
    }

    @Test
    void submitShortAnswerEmptyExplanationFallsBackToExact() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("正确答案");
        q.setExplanation("");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1", Map.of("q1", "错误答案"));
        assertEquals(0, result.get("score"));
    }

    @Test
    void submitShortAnswerFuzzyMatchNotPerfectButAboveThreshold() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("封装是将数据和操作包装在一起");
        q.setExplanation("【采分点】封装、数据隐藏、访问控制");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1",
                Map.of("q1", "封装就是把数据包装起来"));

        assertEquals(1, result.get("score"));
    }

    @Test
    void submitMultipleQuestionsMixedTypes() {
        Question q1 = new Question();
        q1.setId("q1"); q1.setType("choice"); q1.setAnswer("A");

        Question q2 = new Question();
        q2.setId("q2"); q2.setType("truefalse"); q2.setAnswer("正确");

        Question q3 = new Question();
        q3.setId("q3"); q3.setType("shortanswer");
        q3.setAnswer("数据抽象");
        q3.setExplanation("【采分点】数据、抽象");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q1, q2, q3));

        Map<String, String> answers = Map.of("q1", "A", "q2", "正确", "q3", "数据抽象");
        var result = examService.submit("e1", "u1", answers);

        assertEquals(3, result.get("score"));
        assertEquals(3, result.get("total"));
    }

    @Test
    void submitEmptyAnswersAllWrong() {
        Question q = new Question();
        q.setId("q1"); q.setType("choice"); q.setAnswer("A");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1", Map.of());
        assertEquals(0, result.get("score"));
    }

    @Test
    void submitMissingAnswerUsesEmptyStringDefaultsToWrong() {
        Question q1 = new Question();
        q1.setId("q1"); q1.setType("choice"); q1.setAnswer("A");

        Question q2 = new Question();
        q2.setId("q2"); q2.setType("choice"); q2.setAnswer("A");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q1, q2));

        var result = examService.submit("e1", "u1", Map.of("q1", "A"));
        assertEquals(1, result.get("score"));
    }

    @Test
    void submitPersistsExamAttempt() {
        Question q = new Question();
        q.setId("q1"); q.setType("choice"); q.setAnswer("A");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        examService.submit("e1", "u1", Map.of("q1", "A"));

        verify(attemptMapper).insert(any(ExamAttempt.class));
    }

    // === delete / get permission tests ===

    @Test
    void deleteExamOwnedByUserSucceeds() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("u1");

        when(examMapper.findById("e1")).thenReturn(exam);

        assertDoesNotThrow(() -> examService.delete("e1", "u1"));
        verify(examMapper).deleteById("e1");
    }

    @Test
    void deleteExamNotOwnedByUserThrows() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("other_user");

        when(examMapper.findById("e1")).thenReturn(exam);

        assertThrows(RuntimeException.class,
                () -> examService.delete("e1", "u1"));
    }

    @Test
    void deleteNonExistingExamThrows() {
        when(examMapper.findById("e1")).thenReturn(null);

        assertThrows(RuntimeException.class,
                () -> examService.delete("e1", "u1"));
    }

    @Test
    void getExamOwnedByUserReturnsData() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("u1");
        exam.setTitle("Test Exam");

        Question q = new Question();
        q.setId("q1");

        when(examMapper.findById("e1")).thenReturn(exam);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.get("e1", "u1");

        assertNotNull(result);
        assertEquals("e1", result.get("id"));
        assertEquals("Test Exam", result.get("title"));
        assertNotNull(result.get("questions"));
    }

    @Test
    void getExamNotOwnedByUserThrows() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("other_user");

        when(examMapper.findById("e1")).thenReturn(exam);

        assertThrows(RuntimeException.class,
                () -> examService.get("e1", "u1"));
    }

    @Test
    void getNonExistingExamReturnsNull() {
        when(examMapper.findById("e1")).thenReturn(null);

        assertNull(examService.get("e1", "u1"));
    }

    // === list ===

    @Test
    void listExamsReturnsUserExams() {
        Exam e1 = new Exam();
        Exam e2 = new Exam();

        when(examMapper.findByUserId("u1")).thenReturn(List.of(e1, e2));

        var result = examService.list("u1");
        assertEquals(2, result.size());
    }
}