package com.exam.service;

import com.exam.mapper.ExamMapper;
import com.exam.mapper.QuestionMapper;
import com.exam.model.Exam;
import com.exam.model.Question;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExportServiceTest {

    @Mock
    private ExamMapper examMapper;

    @Mock
    private QuestionMapper questionMapper;

    private ExportService exportService;

    @BeforeEach
    void setUp() {
        exportService = new ExportService(examMapper, questionMapper);
    }

    @Test
    @DisplayName("exportDocx generates non-empty byte array")
    void exportDocxNonEmpty() throws Exception {
        Exam exam = new Exam();
        exam.setId("exam-1");
        exam.setTitle("Test Exam");

        Question q = new Question();
        q.setId("q1");
        q.setContent("What is Java?");
        q.setAnswer("A programming language");
        q.setExplanation("Java is a high-level programming language.");

        when(examMapper.findById("exam-1")).thenReturn(exam);
        when(questionMapper.findByExamId("exam-1")).thenReturn(java.util.List.of(q));

        byte[] result = exportService.exportDocx("exam-1");

        assertNotNull(result);
        assertTrue(result.length > 0);
    }

    @Test
    @DisplayName("exportDocx handles exam with no questions")
    void exportDocxEmptyExam() throws Exception {
        Exam exam = new Exam();
        exam.setId("exam-1");
        exam.setTitle("Empty Exam");

        when(examMapper.findById("exam-1")).thenReturn(exam);
        when(questionMapper.findByExamId("exam-1")).thenReturn(java.util.List.of());

        byte[] result = exportService.exportDocx("exam-1");

        assertNotNull(result);
        assertTrue(result.length > 0);
    }

    @Test
    @DisplayName("exportDocx handles null explanation")
    void exportDocxNullExplanation() throws Exception {
        Exam exam = new Exam();
        exam.setId("exam-1");
        exam.setTitle("Test");

        Question q = new Question();
        q.setId("q1");
        q.setContent("Q?");
        q.setAnswer("A");
        q.setExplanation(null);

        when(examMapper.findById("exam-1")).thenReturn(exam);
        when(questionMapper.findByExamId("exam-1")).thenReturn(java.util.List.of(q));

        byte[] result = exportService.exportDocx("exam-1");
        assertNotNull(result);
    }

    @Test
    @DisplayName("exportDocx handles multiple questions")
    void exportDocxMultipleQuestions() throws Exception {
        Exam exam = new Exam();
        exam.setId("exam-1");
        exam.setTitle("Multi Q Exam");

        Question q1 = new Question();
        q1.setId("q1");
        q1.setContent("Q1");
        q1.setAnswer("A1");
        q1.setExplanation("Exp1");

        Question q2 = new Question();
        q2.setId("q2");
        q2.setContent("Q2");
        q2.setAnswer("A2");
        q2.setExplanation("Exp2");

        when(examMapper.findById("exam-1")).thenReturn(exam);
        when(questionMapper.findByExamId("exam-1")).thenReturn(java.util.List.of(q1, q2));

        byte[] result = exportService.exportDocx("exam-1");
        assertNotNull(result);
        assertTrue(result.length > 0);
    }
}