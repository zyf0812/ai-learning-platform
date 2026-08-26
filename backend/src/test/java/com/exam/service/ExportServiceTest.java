package com.exam.service;

import com.exam.mapper.ExamMapper;
import com.exam.mapper.QuestionMapper;
import com.exam.model.Exam;
import com.exam.model.Question;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

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
    void exportDocx_withQuestions_returnsValidDocxBytes() throws Exception {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setTitle("期末考试");

        Question q1 = new Question();
        q1.setId("q1");
        q1.setType("choice");
        q1.setContent("Java之父是谁？");
        q1.setAnswer("B");
        q1.setExplanation("James Gosling");

        Question q2 = new Question();
        q2.setId("q2");
        q2.setType("fill");
        q2.setContent("JVM的全称是___");
        q2.setAnswer("Java虚拟机");
        q2.setExplanation("");  // 空解析，不写入

        when(examMapper.findById("e1")).thenReturn(exam);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q1, q2));

        byte[] bytes = exportService.exportDocx("e1");

        assertNotNull(bytes);
        assertTrue(bytes.length > 0);

        // 验证生成的字节确实是有效的 DOCX（用 POI 重新加载）
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            // 至少有 title + 每道题2段（题目 + 答案），q2 空解析不额外占段
            // title(1) + q1题目(1) + q1答案(1) + q1解析(1) + q2题目(1) + q2答案(1) = 6
            assertTrue(doc.getParagraphs().size() >= 5);
            String titleText = doc.getParagraphs().get(0).getText();
            assertTrue(titleText.contains("期末考试"));
        }
    }

    @Test
    void exportDocx_emptyQuestions_stillProducesDocWithTitle() throws Exception {
        Exam exam = new Exam();
        exam.setId("e-empty");
        exam.setTitle("空白考试");

        when(examMapper.findById("e-empty")).thenReturn(exam);
        when(questionMapper.findByExamId("e-empty")).thenReturn(List.of());

        byte[] bytes = exportService.exportDocx("e-empty");

        assertNotNull(bytes);
        assertTrue(bytes.length > 0);

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            assertEquals(1, doc.getParagraphs().size());
            assertEquals("空白考试", doc.getParagraphs().get(0).getText());
        }
    }

    @Test
    void exportDocx_questionWithNullExplanation_handlesGracefully() throws Exception {
        Exam exam = new Exam();
        exam.setTitle("Test");
        Question q = new Question();
        q.setId("q1");
        q.setContent("Q");
        q.setAnswer("A");
        q.setExplanation(null);

        when(examMapper.findById("e1")).thenReturn(exam);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        byte[] bytes = exportService.exportDocx("e1");
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);
    }
}
