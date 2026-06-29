package com.exam.controller;

import com.exam.service.ExportService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/exams")
public class ExportController {
    private final ExportService service;
    public ExportController(ExportService s) { this.service = s; }

    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable String id) {
        try {
            byte[] data = service.exportDocx(id);
            return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .header("Content-Disposition", "attachment; filename=\"exam.docx\"")
                .body(data);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
