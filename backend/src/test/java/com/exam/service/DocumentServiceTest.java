package com.exam.service;

import com.exam.mapper.DocumentMapper;
import com.exam.model.Document;
import com.exam.util.DocumentParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock
    private DocumentMapper docMapper;

    @Mock
    private DocumentParser parser;

    @Mock
    private RAGService ragService;

    private DocumentService service;

    @BeforeEach
    void setUp() {
        service = new DocumentService(docMapper, parser, ragService);
    }

    // ========== list() ==========

    @Test
    void listReturnsAllForUser() {
        Document d1 = new Document(); d1.setId("d1");
        Document d2 = new Document(); d2.setId("d2");
        when(docMapper.findByUserId("u1")).thenReturn(List.of(d1, d2));

        var result = service.list("u1");
        assertEquals(2, result.size());
    }

    // ========== get(id, userId): 权限校验 ==========

    @Test
    void getWithCorrectUserReturnsDoc() {
        Document d = new Document();
        d.setId("d1");
        d.setUserId("owner");

        when(docMapper.findById("d1")).thenReturn(d);

        Document result = service.get("d1", "owner");
        assertEquals("d1", result.getId());
    }

    @Test
    void getWithWrongUserThrows() {
        Document d = new Document();
        d.setId("d1");
        d.setUserId("owner");

        when(docMapper.findById("d1")).thenReturn(d);

        var ex = assertThrows(RuntimeException.class,
            () -> service.get("d1", "other"));
        assertTrue(ex.getMessage().contains("无权"));
    }

    @Test
    void getNotFoundReturnsNull() {
        when(docMapper.findById("nope")).thenReturn(null);
        assertNull(service.get("nope", "u1"));
    }

    // ========== delete(id, userId) ==========

    @Test
    void deleteOwnerCanDelete() {
        Document d = new Document();
        d.setId("d1");
        d.setUserId("owner");

        when(docMapper.findById("d1")).thenReturn(d);
        doNothing().when(docMapper).deleteById("d1");

        service.delete("d1", "owner");

        verify(docMapper).deleteById("d1");
    }

    @Test
    void deleteWrongUserThrows() {
        Document d = new Document();
        d.setId("d1");
        d.setUserId("owner");

        when(docMapper.findById("d1")).thenReturn(d);

        var ex = assertThrows(RuntimeException.class,
            () -> service.delete("d1", "other"));
        assertTrue(ex.getMessage().contains("无权"));
    }

    @Test
    void deleteNonexistentThrows() {
        when(docMapper.findById("nope")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> service.delete("nope", "u1"));
        assertEquals("文档不存在", ex.getMessage());
    }
}
