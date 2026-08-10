package com.exam.service;

import com.exam.mapper.DocumentMapper;
import com.exam.model.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock private DocumentMapper docMapper;
    @Mock private DocumentParser parser;
    @Mock private RAGService ragService;

    private DocumentService documentService;

    @BeforeEach
    void setUp() {
        documentService = new DocumentService(docMapper, parser, ragService);
    }

    @Test
    void getOwnedDocumentReturnsDoc() {
        Document doc = new Document();
        doc.setId("d1");
        doc.setUserId("u1");

        when(docMapper.findById("d1")).thenReturn(doc);

        var result = documentService.get("d1", "u1");
        assertNotNull(result);
    }

    @Test
    void getOtherUsersDocumentThrows() {
        Document doc = new Document();
        doc.setId("d1");
        doc.setUserId("other_user");

        when(docMapper.findById("d1")).thenReturn(doc);

        assertThrows(RuntimeException.class,
                () -> documentService.get("d1", "u1"));
    }

    @Test
    void deleteOwnedDocumentSucceeds() {
        Document doc = new Document();
        doc.setId("d1");
        doc.setUserId("u1");

        when(docMapper.findById("d1")).thenReturn(doc);

        assertDoesNotThrow(() -> documentService.delete("d1", "u1"));
        verify(docMapper).deleteById("d1");
    }

    @Test
    void deleteOtherUsersDocumentThrows() {
        Document doc = new Document();
        doc.setId("d1");
        doc.setUserId("other_user");

        when(docMapper.findById("d1")).thenReturn(doc);

        assertThrows(RuntimeException.class,
                () -> documentService.delete("d1", "u1"));
    }

    @Test
    void deleteNonExistingDocumentThrows() {
        when(docMapper.findById("d1")).thenReturn(null);

        assertThrows(RuntimeException.class,
                () -> documentService.delete("d1", "u1"));
    }

    @Test
    void listReturnsUserDocuments() {
        Document d = new Document();
        when(docMapper.findByUserId("u1")).thenReturn(java.util.List.of(d));

        var result = documentService.list("u1");
        assertEquals(1, result.size());
    }
}