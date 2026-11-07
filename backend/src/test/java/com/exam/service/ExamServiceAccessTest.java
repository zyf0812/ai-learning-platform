package com.exam.service;

import com.exam.mapper.DocumentMapper;
import com.exam.mapper.ExamAttemptMapper;
import com.exam.mapper.ExamMapper;
import com.exam.mapper.QuestionMapper;
import com.exam.mapper.WrongQuestionMapper;
import com.exam.model.Exam;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExamServiceAccessTest {

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
        examService = new ExamService(examMapper, questionMapper, docMapper, deepSeek, ragService,
            wqMapper, attemptMapper, taskQueue);
    }

    @Test
    void getReturnsNullForUnknownExam() {
        when(examMapper.findById("missing")).thenReturn(null);

        assertNull(examService.get("missing", "u1"));
        verify(questionMapper, never()).findByExamId(any());
    }

    @Test
    void getRejectsAnotherUsersExam() {
        when(examMapper.findById("e1")).thenReturn(exam("e1", "owner"));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> examService.get("e1", "intruder"));

        assertEquals("无权访问该考试", ex.getMessage());
        verify(questionMapper, never()).findByExamId(any());
    }

    @Test
    void getReturnsQuestionsForOwner() {
        when(examMapper.findById("e1")).thenReturn(exam("e1", "owner"));
        when(questionMapper.findByExamId("e1")).thenReturn(List.of());

        Map<String, Object> result = examService.get("e1", "owner");

        assertEquals("e1", result.get("id"));
        assertEquals("owner", result.get("userId"));
        assertEquals(List.of(), result.get("questions"));
    }

    @Test
    void deleteRejectsAnotherUsersExam() {
        when(examMapper.findById("e1")).thenReturn(exam("e1", "owner"));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> examService.delete("e1", "intruder"));

        assertEquals("无权删除该考试", ex.getMessage());
        verify(examMapper, never()).deleteById(any());
    }

    @Test
    void deleteRejectsUnknownExam() {
        when(examMapper.findById("missing")).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> examService.delete("missing", "u1"));

        assertEquals("考试不存在", ex.getMessage());
        verify(examMapper, never()).deleteById(any());
    }

    @Test
    void deleteRemovesOwnersExam() {
        when(examMapper.findById("e1")).thenReturn(exam("e1", "owner"));

        examService.delete("e1", "owner");

        verify(examMapper).deleteById("e1");
    }

    private static Exam exam(String id, String userId) {
        Exam exam = new Exam();
        exam.setId(id);
        exam.setTitle("考试");
        exam.setUserId(userId);
        return exam;
    }
}
