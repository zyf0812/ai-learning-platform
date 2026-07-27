package com.exam.service;

import com.exam.mapper.*;
import com.exam.model.Question;
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
        examService = new ExamService(examMapper, questionMapper, docMapper,
            deepSeek, ragService, wqMapper, attemptMapper, taskQueue);
    }

    private Question createQuestion(String id, String type, String content,
                                    String options, String answer, String explanation) {
        Question q = new Question();
        q.setId(id);
        q.setType(type);
        q.setContent(content);
        q.setOptions(options);
        q.setAnswer(answer);
        q.setExplanation(explanation);
        return q;
    }

    @Test
    void submitChoiceQuestionCorrectAnswer() {
        Question q = createQuestion("q1", "choice", "Java是?",
            "[\"A|语言\",\"B|框架\",\"C|系统\",\"D|工具\"]", "A", "");
        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));

        Map<String, String> answers = Map.of("q1", "A");
        Map<String, Object> result = examService.submit("exam1", "user1", answers);

        assertEquals(1, result.get("score"));
        assertEquals(1, result.get("total"));

        @SuppressWarnings("unchecked")
        Map<String, Object> graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> gq1 = (Map<String, Object>) graded.get("q1");
        assertTrue((boolean) gq1.get("correct"));
        assertEquals("A", gq1.get("correctAnswer"));
    }

    @Test
    void submitChoiceQuestionWrongAnswer() {
        Question q = createQuestion("q1", "choice", "Java是?",
            "[\"A|语言\",\"B|框架\",\"C|系统\",\"D|工具\"]", "A", "");
        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));

        Map<String, String> answers = Map.of("q1", "B");
        Map<String, Object> result = examService.submit("exam1", "user1", answers);

        assertEquals(0, result.get("score"));

        @SuppressWarnings("unchecked")
        Map<String, Object> graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> gq1 = (Map<String, Object>) graded.get("q1");
        assertFalse((boolean) gq1.get("correct"));
    }

    @Test
    void submitFillQuestionExactMatch() {
        Question q = createQuestion("q1", "fill", "___是Java的核心框架",
            "[]", "Spring", "");
        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));

        Map<String, String> answers = Map.of("q1", "Spring");
        Map<String, Object> result = examService.submit("exam1", "user1", answers);

        assertEquals(1, result.get("score"));
    }

    @Test
    void submitTrueFalseCorrect() {
        Question q = createQuestion("q1", "truefalse", "Java是面向对象的",
            "[]", "正确", "");
        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));

        Map<String, String> answers = Map.of("q1", "正确");
        Map<String, Object> result = examService.submit("exam1", "user1", answers);

        assertEquals(1, result.get("score"));
    }

    @Test
    void submitTrueFalseWrong() {
        Question q = createQuestion("q1", "truefalse", "Java是面向对象的",
            "[]", "正确", "");
        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));

        Map<String, String> answers = Map.of("q1", "错误");
        Map<String, Object> result = examService.submit("exam1", "user1", answers);

        assertEquals(0, result.get("score"));
    }

    @Test
    void submitShortAnswerExactMatch() {
        Question q = createQuestion("q1", "shortanswer", "什么是封装?",
            "[]", "封装是面向对象的特性",
            "封装的核心是信息隐藏【采分点】封装、信息隐藏、面向对象");
        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));

        Map<String, String> answers = Map.of("q1", "封装是面向对象的特性");
        Map<String, Object> result = examService.submit("exam1", "user1", answers);

        assertEquals(1, result.get("score"));

        @SuppressWarnings("unchecked")
        Map<String, Object> graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> gq1 = (Map<String, Object>) graded.get("q1");
        assertTrue((boolean) gq1.get("correct"));
        assertEquals(1.0, gq1.get("hitRatio"));
    }

    @Test
    void submitShortAnswerWithKeywordMatch() {
        Question q = createQuestion("q1", "shortanswer", "什么是封装?",
            "[]", "封装是面向对象的特性",
            "封装的核心是信息隐藏【采分点】封装、信息隐藏、面向对象");
        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));

        String userAnswer = "封装就是把数据和方法打包在一起，实现信息隐藏，是面向对象的重要特性";
        Map<String, String> answers = Map.of("q1", userAnswer);
        Map<String, Object> result = examService.submit("exam1", "user1", answers);

        assertEquals(1, result.get("score"));

        @SuppressWarnings("unchecked")
        Map<String, Object> graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> gq1 = (Map<String, Object>) graded.get("q1");
        assertTrue((boolean) gq1.get("correct"));
        assertTrue((boolean) gq1.get("isFuzzyMatch"));
        double hitRatio = (double) gq1.get("hitRatio");
        assertTrue(hitRatio > 0.6 && hitRatio < 1.0);
    }

    @Test
    void submitShortAnswerNoKeywordsFallsBackToExact() {
        Question q = createQuestion("q1", "shortanswer", "什么是Java?",
            "[]", "一种编程语言", "");
        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));

        Map<String, String> answers = Map.of("q1", "一种编程语言");
        Map<String, Object> result = examService.submit("exam1", "user1", answers);

        assertEquals(1, result.get("score"));
    }

    @Test
    void submitShortAnswerNoKeywordsExactMismatch() {
        Question q = createQuestion("q1", "shortanswer", "什么是Java?",
            "[]", "一种编程语言", "");
        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));

        Map<String, String> answers = Map.of("q1", "完全不同的答案");
        Map<String, Object> result = examService.submit("exam1", "user1", answers);

        assertEquals(0, result.get("score"));
    }

    @Test
    void submitShortAnswerBelowThresholdIsWrong() {
        Question q = createQuestion("q1", "shortanswer", "什么是封装?",
            "[]", "封装是面向对象的特性",
            "封装的核心是信息隐藏【采分点】封装、信息隐藏、面向对象、数据隐藏、访问控制");
        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));

        Map<String, String> answers = Map.of("q1", "我不知道");
        Map<String, Object> result = examService.submit("exam1", "user1", answers);

        assertEquals(0, result.get("score"));
    }

    @Test
    void submitEmptyAnswerIsWrong() {
        Question q = createQuestion("q1", "choice", "题目",
            "[\"A|1\",\"B|2\"]", "A", "");
        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));

        Map<String, String> answers = Map.of();
        Map<String, Object> result = examService.submit("exam1", "user1", answers);

        assertEquals(0, result.get("score"));
    }

    @Test
    void submitWhitespaceAnswerIsTrimmed() {
        Question q = createQuestion("q1", "choice", "题目",
            "[\"A|1\",\"B|2\"]", "A", "");
        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));

        Map<String, String> answers = Map.of("q1", "  A  ");
        Map<String, Object> result = examService.submit("exam1", "user1", answers);

        assertEquals(1, result.get("score"));
    }

    @Test
    void wrongAnswerAddsToWrongQuestionBook() {
        Question q = createQuestion("q1", "choice", "题目",
            "[\"A|1\",\"B|2\"]", "A", "");
        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));

        Map<String, String> answers = Map.of("q1", "B");
        examService.submit("exam1", "user1", answers);

        verify(wqMapper).insert(any());
    }

    @Test
    void correctAnswerDoesNotAddToWrongQuestionBook() {
        Question q = createQuestion("q1", "choice", "题目",
            "[\"A|1\",\"B|2\"]", "A", "");
        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));

        Map<String, String> answers = Map.of("q1", "A");
        examService.submit("exam1", "user1", answers);

        verify(wqMapper, never()).insert(any());
    }

    @Test
    void submitSavesExamAttempt() {
        Question q = createQuestion("q1", "choice", "题目",
            "[\"A|1\",\"B|2\"]", "A", "");
        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));

        Map<String, String> answers = Map.of("q1", "A");
        examService.submit("exam1", "user1", answers);

        verify(attemptMapper).insert(any());
    }

    @Test
    void submitMultipleQuestionsMixedResults() {
        Question q1 = createQuestion("q1", "choice", "题1",
            "[\"A|1\",\"B|2\"]", "A", "");
        Question q2 = createQuestion("q2", "fill", "题2",
            "[]", "答案", "");
        Question q3 = createQuestion("q3", "truefalse", "题3",
            "[]", "正确", "");

        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q1, q2, q3));

        Map<String, String> answers = Map.of(
            "q1", "A",
            "q2", "答案",
            "q3", "错误"
        );
        Map<String, Object> result = examService.submit("exam1", "user1", answers);

        assertEquals(2, result.get("score"));
        assertEquals(3, result.get("total"));
    }

    @Test
    void deleteExamWithWrongUserThrowsException() {
        var exam = new com.exam.model.Exam();
        exam.setId("exam1");
        exam.setUserId("user2");
        when(examMapper.findById("exam1")).thenReturn(exam);

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> examService.delete("exam1", "user1"));

        assertEquals("无权删除该考试", ex.getMessage());
        verify(examMapper, never()).deleteById(anyString());
    }

    @Test
    void deleteNonexistentExamThrowsException() {
        when(examMapper.findById("exam1")).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> examService.delete("exam1", "user1"));

        assertEquals("考试不存在", ex.getMessage());
    }

    @Test
    void deleteExamWithCorrectUser() {
        var exam = new com.exam.model.Exam();
        exam.setId("exam1");
        exam.setUserId("user1");
        when(examMapper.findById("exam1")).thenReturn(exam);

        examService.delete("exam1", "user1");

        verify(examMapper).deleteById("exam1");
    }

    @Test
    void getExamWithWrongUserThrowsException() {
        var exam = new com.exam.model.Exam();
        exam.setId("exam1");
        exam.setUserId("user2");
        when(examMapper.findById("exam1")).thenReturn(exam);

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> examService.get("exam1", "user1"));

        assertEquals("无权访问该考试", ex.getMessage());
    }

    @Test
    void getNonexistentExamReturnsNull() {
        when(examMapper.findById("exam1")).thenReturn(null);

        assertNull(examService.get("exam1", "user1"));
    }

    @Test
    void getExamWithCorrectUserReturnsData() {
        var exam = new com.exam.model.Exam();
        exam.setId("exam1");
        exam.setUserId("user1");
        exam.setTitle("测试考试");
        when(examMapper.findById("exam1")).thenReturn(exam);
        when(questionMapper.findByExamId("exam1")).thenReturn(List.of());

        Map<String, Object> result = examService.get("exam1", "user1");

        assertNotNull(result);
        assertEquals("exam1", result.get("id"));
        assertEquals("测试考试", result.get("title"));
    }

    @Test
    void listExamsReturnsUserExams() {
        var exam = new com.exam.model.Exam();
        exam.setId("exam1");
        when(examMapper.findByUserId("user1")).thenReturn(List.of(exam));

        var result = examService.list("user1");

        assertEquals(1, result.size());
        assertEquals("exam1", result.get(0).getId());
    }

    @Test
    void submitShortAnswerKeywordsWithCommaSeparator() {
        Question q = createQuestion("q1", "shortanswer", "什么是OOP?",
            "[]", "面向对象编程",
            "【采分点】封装,继承,多态");
        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));

        String userAnswer = "OOP包括封装、继承和多态三大特性";
        Map<String, String> answers = Map.of("q1", userAnswer);
        Map<String, Object> result = examService.submit("exam1", "user1", answers);

        assertEquals(1, result.get("score"));
    }

    @Test
    void submitShortAnswerKeywordsWithChineseComma() {
        Question q = createQuestion("q1", "shortanswer", "什么是OOP?",
            "[]", "面向对象编程",
            "【采分点】封装，继承，多态");
        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));

        String userAnswer = "封装、继承、多态是面向对象的三大特性";
        Map<String, String> answers = Map.of("q1", userAnswer);
        Map<String, Object> result = examService.submit("exam1", "user1", answers);

        assertEquals(1, result.get("score"));
    }
}
