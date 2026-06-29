package com.exam.util;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class DocumentParser {

    public String parse(byte[] data, String filename) {
        String ext = getExt(filename);
        try {
            return switch (ext) {
                case "pdf" -> parsePdf(data);
                case "docx", "doc" -> parseWord(data);
                case "pptx", "ppt" -> parsePpt(data);
                case "md", "markdown", "txt" -> {
                    // 先试 UTF-8，失败降级 GBK
                    String s = new String(data, StandardCharsets.UTF_8);
                    // 检查是否有乱码特征（连续 ? 或不可打印字符占比高）
                    int bad = 0;
                    for (int i = 0; i < s.length(); i++) {
                        char c = s.charAt(i);
                        if (c == '\ufffd' || (c < 0x20 && c != '\n' && c != '\r' && c != '\t')) bad++;
                    }
                    if (bad > s.length() / 10) {
                        yield new String(data, java.nio.charset.Charset.forName("GBK"));
                    }
                    yield s;
                }
                default -> throw new RuntimeException("不支持的文件格式: ." + ext);
            };
        } catch (IOException e) {
            throw new RuntimeException("文件解析失败: " + e.getMessage());
        }
    }

    private String parsePdf(byte[] data) throws IOException {
        try (PDDocument doc = Loader.loadPDF(data)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private String parseWord(byte[] data) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(data))) {
            return new XWPFWordExtractor(doc).getText();
        }
    }

    private String parsePpt(byte[] data) throws IOException {
        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(data))) {
            var sb = new StringBuilder();
            for (var slide : ppt.getSlides()) {
                slide.getShapes().forEach(shape -> {
                    if (shape instanceof org.apache.poi.xslf.usermodel.XSLFTextShape textShape) {
                        sb.append(textShape.getText()).append("\n");
                    }
                });
            }
            return sb.toString();
        }
    }

    public String getFileType(String filename) {
        return getExt(filename);
    }

    private String getExt(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(dot + 1).toLowerCase() : "txt";
    }
}
