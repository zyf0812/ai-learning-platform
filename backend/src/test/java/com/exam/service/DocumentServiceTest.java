package com.exam.service;

import com.exam.mapper.DocumentMapper;
import com.exam.model.Document;
import com.exam.util.DocumentParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock
    private DocumentMapper docMapper;

    @Mock
    private RAGService ragService;

    private DocumentService documentService;

    @BeforeEach
    void setUp() {
        documentService = new DocumentService(docMapper, new DocumentParser(), ragService);
    }

    private Document document(String id, String userId) {
        Document doc = new Document();
        doc.setId(id);
        doc.setUserId(userId);
        doc.setTitle("文档");
        return doc;
    }

    @Test
    void getThrowsWhenDocumentBelongsToAnotherUser() {
        when(docMapper.findById("d1")).thenReturn(document("d1", "owner"));

        var ex = assertThrows(RuntimeException.class, () -> documentService.get("d1", "intruder"));
        assertEquals("无权访问该文档", ex.getMessage());
    }

    @Test
    void getReturnsNullWhenDocumentMissing() {
        when(docMapper.findById("missing")).thenReturn(null);

        assertNull(documentService.get("missing", "u1"));
    }

    @Test
    void getWithoutUserScopeSkipsOwnershipCheck() {
        when(docMapper.findById("d1")).thenReturn(document("d1", "owner"));

        assertEquals("d1", documentService.get("d1").getId());
    }

    @Test
    void deleteThrowsWhenDocumentMissing() {
        when(docMapper.findById("missing")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class, () -> documentService.delete("missing", "u1"));
        assertEquals("文档不存在", ex.getMessage());
    }

    @Test
    void deleteThrowsWhenDocumentBelongsToAnotherUser() {
        when(docMapper.findById("d1")).thenReturn(document("d1", "owner"));

        var ex = assertThrows(RuntimeException.class, () -> documentService.delete("d1", "intruder"));
        assertEquals("无权删除该文档", ex.getMessage());
    }
}
