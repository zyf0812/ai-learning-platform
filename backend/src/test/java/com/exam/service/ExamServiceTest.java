package com.exam.service;

import com.exam.mapper.*;
import com.exam.model.Exam;
import com.exam.model.Question;
import com.exam.model.WrongQuestion;
import com.exam.model.ExamAttempt;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

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
                                    String answer, String explanation) {
        Question q = new Question();
        q.setId(id);
        q.setExamId("exam-1");
        q.setType(type);
        q.setContent(content);
        q.setAnswer(answer);
        q.setExplanation(explanation);
        q.setOptions("[]");
        return q;
    }

    @Nested
    @DisplayName("submit() tests")
    class SubmitTests {

        @Test
        @DisplayName("correct choice answer scores 1")
        void correctChoiceAnswer() {
            Question q = createQuestion("q1", "choice", "Java is?", "A", null);
            when(questionMapper.findByExamId("exam-1")).thenReturn(List.of(q));

            Map<String, String> answers = Map.of("q1", "A");
            var result = examService.submit("exam-1", "user-1", answers);

            assertEquals(1, result.get("score"));
            assertEquals(1, result.get("total"));
        }

        @Test
        @DisplayName("wrong answer scores 0 and creates wrong question")
        void wrongAnswerCreatesWrongQuestion() {
            Question q = createQuestion("q1", "choice", "Java is?", "A", null);
            when(questionMapper.findByExamId("exam-1")).thenReturn(List.of(q));

            Map<String, String> answers = Map.of("q1", "B");
            var result = examService.submit("exam-1", "user-1", answers);

            assertEquals(0, result.get("score"));

            ArgumentCaptor<WrongQuestion> captor = ArgumentCaptor.forClass(WrongQuestion.class);
            verify(wqMapper).insert(captor.capture());
            WrongQuestion wq = captor.getValue();
            assertEquals("user-1", wq.getUserId());
            assertEquals("q1", wq.getQuestionId());
            assertEquals("exam-1", wq.getExamId());
            assertEquals("B", wq.getUserAnswer());
        }

        @Test
        @DisplayName("fuzzy match for shortanswer with threshold >= 60%")
        void fuzzyMatchShortAnswerAboveThreshold() {
            String explanation = "解析内容 【采分点】封装、数据隐藏、多态";
            Question q = createQuestion("q1", "shortanswer", "OOP核心?", "封装 数据隐藏 多态", explanation);
            when(questionMapper.findByExamId("exam-1")).thenReturn(List.of(q));

            Map<String, String> answers = Map.of("q1", "封装 数据隐藏");
            var result = examService.submit("exam-1", "user-1", answers);

            assertEquals(1, result.get("score"));
        }

        @Test
        @DisplayName("fuzzy match for shortanswer below threshold fails")
        void fuzzyMatchShortAnswerBelowThreshold() {
            String explanation = "解析 【采分点】封装、数据隐藏、多态、继承";
            Question q = createQuestion("q1", "shortanswer", "OOP?", "封装 数据隐藏 多态 继承", explanation);
            when(questionMapper.findByExamId("exam-1")).thenReturn(List.of(q));

            Map<String, String> answers = Map.of("q1", "封装");
            var result = examService.submit("exam-1", "user-1", answers);

            assertEquals(0, result.get("score"));
        }

        @Test
        @DisplayName("shortanswer without 采分点 falls back to exact match")
        void shortAnswerWithoutKeywordsFallsBackToExact() {
            Question q = createQuestion("q1", "shortanswer", "首都?", "北京", "普通解析无采分点");
            when(questionMapper.findByExamId("exam-1")).thenReturn(List.of(q));

            Map<String, String> answersExact = Map.of("q1", "北京");
            var resultExact = examService.submit("exam-1", "user-1", answersExact);
            assertEquals(1, resultExact.get("score"));

            Map<String, String> answersPartial = Map.of("q1", "京");
            var resultPartial = examService.submit("exam-1", "user-1", answersPartial);
            assertEquals(0, resultPartial.get("score"));
        }

        @Test
        @DisplayName("keywordMatch handles substring match")
        void keywordMatchSubstringMatch() {
            String explanation = "解析 【采分点】数据隐藏";
            Question q = createQuestion("q1", "shortanswer", "封装?", "数据隐藏", explanation);
            when(questionMapper.findByExamId("exam-1")).thenReturn(List.of(q));

            Map<String, String> answers = Map.of("q1", "数据隐藏是面向对象的特性");
            var result = examService.submit("exam-1", "user-1", answers);
            assertEquals(1, result.get("score"));
        }

        @Test
        @DisplayName("keywordMatch handles character coverage for synonyms")
        void keywordMatchCharacterCoverage() {
            String explanation = "解析 【采分点】数据隐藏";
            Question q = createQuestion("q1", "shortanswer", "封装?", "数据隐藏", explanation);
            when(questionMapper.findByExamId("exam-1")).thenReturn(List.of(q));

            Map<String, String> answers = Map.of("q1", "信息隐藏");
            var result = examService.submit("exam-1", "user-1", answers);
            assertEquals(1, result.get("score"));
        }

        @Test
        @DisplayName("empty answer is handled gracefully")
        void emptyAnswer() {
            Question q = createQuestion("q1", "choice", "Java?", "A", null);
            when(questionMapper.findByExamId("exam-1")).thenReturn(List.of(q));

            Map<String, String> answers = Map.of("q1", "");
            var result = examService.submit("exam-1", "user-1", answers);

            assertEquals(0, result.get("score"));
        }

        @Test
        @DisplayName("missing question id in answers defaults to empty string")
        void missingQuestionId() {
            Question q = createQuestion("q1", "choice", "Java?", "A", null);
            when(questionMapper.findByExamId("exam-1")).thenReturn(List.of(q));

            Map<String, String> answers = Map.of();
            var result = examService.submit("exam-1", "user-1", answers);

            assertEquals(0, result.get("score"));
        }

        @Test
        @DisplayName("multiple questions mixed correct and wrong")
        void multipleQuestionsMixed() {
            Question q1 = createQuestion("q1", "choice", "Q1?", "A", null);
            Question q2 = createQuestion("q2", "choice", "Q2?", "B", null);
            Question q3 = createQuestion("q3", "truefalse", "Q3?", "正确", null);
            when(questionMapper.findByExamId("exam-1")).thenReturn(List.of(q1, q2, q3));

            Map<String, String> answers = Map.of("q1", "A", "q2", "C", "q3", "正确");
            var result = examService.submit("exam-1", "user-1", answers);

            assertEquals(2, result.get("score"));
            assertEquals(3, result.get("total"));
        }

        @Test
        @DisplayName("exam attempt is persisted")
        void examAttemptPersisted() {
            Question q = createQuestion("q1", "choice", "Q?", "A", null);
            when(questionMapper.findByExamId("exam-1")).thenReturn(List.of(q));

            Map<String, String> answers = Map.of("q1", "A");
            examService.submit("exam-1", "user-1", answers);

            verify(attemptMapper).insert(any(ExamAttempt.class));
        }

        @Test
        @DisplayName("wrong question insert failure is handled gracefully")
        void wrongQuestionInsertFailureHandled() {
            Question q = createQuestion("q1", "choice", "Q?", "A", null);
            when(questionMapper.findByExamId("exam-1")).thenReturn(List.of(q));
            doThrow(new RuntimeException("DB error")).when(wqMapper).insert(any(WrongQuestion.class));

            Map<String, String> answers = Map.of("q1", "wrong");
            assertDoesNotThrow(() -> examService.submit("exam-1", "user-1", answers));
        }

        @Test
        @DisplayName("parseKeywords handles empty explanation")
        void parseKeywordsEmptyExplanation() {
            Question q = createQuestion("q1", "shortanswer", "Q?", "ans", "");
            when(questionMapper.findByExamId("exam-1")).thenReturn(List.of(q));

            Map<String, String> answers = Map.of("q1", "ans");
            var result = examService.submit("exam-1", "user-1", answers);
            assertEquals(1, result.get("score"));
        }

        @Test
        @DisplayName("parseKeywords handles null explanation")
        void parseKeywordsNullExplanation() {
            Question q = createQuestion("q1", "shortanswer", "Q?", "ans", null);
            when(questionMapper.findByExamId("exam-1")).thenReturn(List.of(q));

            Map<String, String> answers = Map.of("q1", "ans");
            var result = examService.submit("exam-1", "user-1", answers);
            assertEquals(1, result.get("score"));
        }

        @Test
        @DisplayName("parseKeywords with new line after markers")
        void parseKeywordsWithNewlineAfterMarker() {
            String explanation = "解析 【采分点】封装、数据隐藏\n下一段内容";
            Question q = createQuestion("q1", "shortanswer", "Q?", "封装 数据隐藏", explanation);
            when(questionMapper.findByExamId("exam-1")).thenReturn(List.of(q));

            Map<String, String> answers = Map.of("q1", "封装 数据隐藏");
            var result = examService.submit("exam-1", "user-1", answers);
            assertEquals(1, result.get("score"));
        }

        @Test
        @DisplayName("keywordMatch returns false for long keywords without coverage")
        void keywordMatchLongKeywordNoCoverage() {
            String explanation = "解析 【采分点】面向对象程序设计的三大特性";
            Question q = createQuestion("q1", "shortanswer", "Q?", "面向对象程序设计的三大特性", explanation);
            when(questionMapper.findByExamId("exam-1")).thenReturn(List.of(q));

            Map<String, String> answers = Map.of("q1", "封装");
            var result = examService.submit("exam-1", "user-1", answers);
            assertEquals(0, result.get("score"));
        }

        @Test
        @DisplayName("isFuzzyMatch flag is set for partial matches")
        void fuzzyMatchFlagSet() {
            String explanation = "解析 【采分点】封装、数据隐藏、多态、继承";
            Question q = createQuestion("q1", "shortanswer", "Q?", "封装 数据隐藏 多态 继承", explanation);
            when(questionMapper.findByExamId("exam-1")).thenReturn(List.of(q));

            Map<String, String> answers = Map.of("q1", "封装 数据隐藏 多态");
            var result = examService.submit("exam-1", "user-1", answers);

            @SuppressWarnings("unchecked")
            Map<String, Object> answersMap = (Map<String, Object>) result.get("answers");
            @SuppressWarnings("unchecked")
            Map<String, Object> q1Result = (Map<String, Object>) answersMap.get("q1");
            assertEquals(true, q1Result.get("isFuzzyMatch"));
        }

        @Test
        @DisplayName("fuzzy match exactly at threshold (60%) passes")
        void fuzzyMatchExactlyAtThreshold() {
            String explanation = "解析 【采分点】封装、数据隐藏、多态、继承、封装性";
            Question q = createQuestion("q1", "shortanswer", "Q?", "封装 数据隐藏 多态 继承 封装性", explanation);
            when(questionMapper.findByExamId("exam-1")).thenReturn(List.of(q));

            // 3/5 = 60% exactly
            Map<String, String> answers = Map.of("q1", "封装 数据隐藏 多态");
            var result = examService.submit("exam-1", "user-1", answers);
            assertEquals(1, result.get("score"));
        }
    }

    @Nested
    @DisplayName("get() tests")
    class GetTests {

        @Test
        @DisplayName("returns exam with questions when found")
        void returnsExamWithQuestions() {
            Exam exam = new Exam();
            exam.setId("exam-1");
            exam.setTitle("Test Exam");
            exam.setUserId("user-1");
            when(examMapper.findById("exam-1")).thenReturn(exam);
            when(questionMapper.findByExamId("exam-1")).thenReturn(List.of());

            var result = examService.get("exam-1", "user-1");

            assertNotNull(result);
            assertEquals("exam-1", result.get("id"));
            assertEquals("Test Exam", result.get("title"));
        }

        @Test
        @DisplayName("returns null when exam not found")
        void returnsNullWhenNotFound() {
            when(examMapper.findById("unknown")).thenReturn(null);

            var result = examService.get("unknown", "user-1");
            assertNull(result);
        }

        @Test
        @DisplayName("throws when accessing other user's exam")
        void throwsWhenAccessingOtherUsersExam() {
            Exam exam = new Exam();
            exam.setId("exam-1");
            exam.setUserId("other-user");
            when(examMapper.findById("exam-1")).thenReturn(exam);

            assertThrows(RuntimeException.class,
                () -> examService.get("exam-1", "user-1"));
        }

        @Test
        @DisplayName("get by id without user check")
        void getByIdNoUserCheck() {
            Exam exam = new Exam();
            exam.setId("exam-1");
            exam.setUserId("any-user");
            when(examMapper.findById("exam-1")).thenReturn(exam);
            when(questionMapper.findByExamId("exam-1")).thenReturn(List.of());

            var result = examService.get("exam-1");
            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("delete() tests")
    class DeleteTests {

        @Test
        @DisplayName("deletes own exam successfully")
        void deletesOwnExam() {
            Exam exam = new Exam();
            exam.setId("exam-1");
            exam.setUserId("user-1");
            when(examMapper.findById("exam-1")).thenReturn(exam);

            assertDoesNotThrow(() -> examService.delete("exam-1", "user-1"));
            verify(examMapper).deleteById("exam-1");
        }

        @Test
        @DisplayName("throws when deleting nonexistent exam")
        void throwsWhenExamNotFound() {
            when(examMapper.findById("unknown")).thenReturn(null);

            assertThrows(RuntimeException.class,
                () -> examService.delete("unknown", "user-1"));
        }

        @Test
        @DisplayName("throws when deleting other user's exam")
        void throwsWhenDeletingOtherUsersExam() {
            Exam exam = new Exam();
            exam.setId("exam-1");
            exam.setUserId("other-user");
            when(examMapper.findById("exam-1")).thenReturn(exam);

            assertThrows(RuntimeException.class,
                () -> examService.delete("exam-1", "user-1"));
        }

        @Test
        @DisplayName("delete without user check")
        void deleteNoUserCheck() {
            assertDoesNotThrow(() -> examService.delete("exam-1"));
            verify(examMapper).deleteById("exam-1");
        }
    }

    @Nested
    @DisplayName("list() tests")
    class ListTests {

        @Test
        @DisplayName("returns exams for user")
        void returnsUserExams() {
            when(examMapper.findByUserId("user-1")).thenReturn(List.of(new Exam()));

            var result = examService.list("user-1");
            assertNotNull(result);
            assertEquals(1, result.size());
        }
    }
}