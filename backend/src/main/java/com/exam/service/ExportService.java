package com.exam.service;

import com.exam.mapper.ExamMapper;
import com.exam.mapper.QuestionMapper;
import com.exam.model.*;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.List;

@Service
public class ExportService {
    private final ExamMapper examMapper;
    private final QuestionMapper questionMapper;

    public ExportService(ExamMapper e, QuestionMapper q) { this.examMapper = e; this.questionMapper = q; }

    public byte[] exportDocx(String examId) throws Exception {
        Exam exam = examMapper.findById(examId);
        List<Question> questions = questionMapper.findByExamId(examId);

        XWPFDocument doc = new XWPFDocument();
        XWPFParagraph title = doc.createParagraph();
        XWPFRun run = title.createRun();
        run.setText(exam.getTitle());
        run.setBold(true);
        run.setFontSize(16);

        int i = 1;
        for (Question q : questions) {
            XWPFParagraph p = doc.createParagraph();
            p.createRun().setText(i + ". " + q.getContent());
            p.createRun().setBold(true);

            XWPFParagraph a = doc.createParagraph();
            a.createRun().setText("答案: " + q.getAnswer());
            a.getRuns().get(0).setColor("2E7D32");

            if (q.getExplanation() != null && !q.getExplanation().isEmpty()) {
                XWPFParagraph e = doc.createParagraph();
                e.createRun().setText("解析: " + q.getExplanation());
            }
            i++;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.write(out);
        doc.close();
        return out.toByteArray();
    }
}
