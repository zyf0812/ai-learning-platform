package com.exam.service;

import com.exam.mapper.FlashCardMapper;
import com.exam.mapper.KnowledgeMapper;
import com.exam.model.FlashCard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FlashcardServiceTest {

    @Mock
    private FlashCardMapper cardMapper;

    @Mock
    private KnowledgeMapper knowledgeMapper;

    @Test
    void reviewQuality5IncreasesInterval() {
        FlashCard card = new FlashCard();
        card.setId("card1");
        card.setEaseFactor(2.5);
        card.setInterval(1);
        card.setRepetitions(1);

        when(cardMapper.findById("card1")).thenReturn(card);

        FlashcardService service = new FlashcardService(cardMapper, knowledgeMapper);
        Map<String, Object> result = service.review("card1", 5);

        assertEquals(2.6, result.get("easeFactor"));
        assertEquals(6, result.get("interval"));
        assertEquals(2, result.get("repetitions"));
        verify(cardMapper).updateReview(any(FlashCard.class));
    }

    @Test
    void reviewQuality3MaintainsIntervalGrowth() {
        FlashCard card = new FlashCard();
        card.setId("card1");
        card.setEaseFactor(2.5);
        card.setInterval(6);
        card.setRepetitions(2);

        when(cardMapper.findById("card1")).thenReturn(card);

        FlashcardService service = new FlashcardService(cardMapper, knowledgeMapper);
        Map<String, Object> result = service.review("card1", 3);

        assertEquals(2.36, result.get("easeFactor"));
        assertEquals(14, result.get("interval"));
        assertEquals(3, result.get("repetitions"));
    }

    @Test
    void reviewQuality2ResetsInterval() {
        FlashCard card = new FlashCard();
        card.setId("card1");
        card.setEaseFactor(2.5);
        card.setInterval(10);
        card.setRepetitions(5);

        when(cardMapper.findById("card1")).thenReturn(card);

        FlashcardService service = new FlashcardService(cardMapper, knowledgeMapper);
        Map<String, Object> result = service.review("card1", 2);

        assertEquals(2.26, result.get("easeFactor"));
        assertEquals(1, result.get("interval"));
        assertEquals(0, result.get("repetitions"));
    }

    @Test
    void reviewQuality0ResetsEverything() {
        FlashCard card = new FlashCard();
        card.setId("card1");
        card.setEaseFactor(2.5);
        card.setInterval(20);
        card.setRepetitions(10);

        when(cardMapper.findById("card1")).thenReturn(card);

        FlashcardService service = new FlashcardService(cardMapper, knowledgeMapper);
        Map<String, Object> result = service.review("card1", 0);

        assertEquals(1.7, result.get("easeFactor"));
        assertEquals(1, result.get("interval"));
        assertEquals(0, result.get("repetitions"));
    }

    @Test
    void reviewFirstTimeSetsInterval1() {
        FlashCard card = new FlashCard();
        card.setId("card1");
        card.setEaseFactor(2.5);
        card.setInterval(0);
        card.setRepetitions(0);

        when(cardMapper.findById("card1")).thenReturn(card);

        FlashcardService service = new FlashcardService(cardMapper, knowledgeMapper);
        Map<String, Object> result = service.review("card1", 5);

        assertEquals(2.6, result.get("easeFactor"));
        assertEquals(1, result.get("interval"));
        assertEquals(1, result.get("repetitions"));
    }

    @Test
    void reviewSecondTimeSetsInterval6() {
        FlashCard card = new FlashCard();
        card.setId("card1");
        card.setEaseFactor(2.5);
        card.setInterval(1);
        card.setRepetitions(1);

        when(cardMapper.findById("card1")).thenReturn(card);

        FlashcardService service = new FlashcardService(cardMapper, knowledgeMapper);
        Map<String, Object> result = service.review("card1", 5);

        assertEquals(2.6, result.get("easeFactor"));
        assertEquals(6, result.get("interval"));
        assertEquals(2, result.get("repetitions"));
    }

    @Test
    void reviewEaseFactorNotBelow13() {
        FlashCard card = new FlashCard();
        card.setId("card1");
        card.setEaseFactor(1.3);
        card.setInterval(1);
        card.setRepetitions(1);

        when(cardMapper.findById("card1")).thenReturn(card);

        FlashcardService service = new FlashcardService(cardMapper, knowledgeMapper);
        Map<String, Object> result = service.review("card1", 0);

        assertEquals(1.3, result.get("easeFactor"));
    }

    @Test
    void reviewNonExistentCardThrows() {
        when(cardMapper.findById("nonexistent")).thenReturn(null);

        FlashcardService service = new FlashcardService(cardMapper, knowledgeMapper);

        var ex = assertThrows(RuntimeException.class, () -> service.review("nonexistent", 5));
        assertEquals("闪卡不存在", ex.getMessage());
    }

    @Test
    void generateForDocumentCreatesCards() {
        com.exam.model.KnowledgePoint p1 = new com.exam.model.KnowledgePoint();
        p1.setId("kp1");
        p1.setTitle("标题1");
        p1.setContent("内容1");

        com.exam.model.KnowledgePoint p2 = new com.exam.model.KnowledgePoint();
        p2.setId("kp2");
        p2.setTitle("标题2");
        p2.setContent("内容2");

        when(knowledgeMapper.findByDocumentId("doc1")).thenReturn(List.of(p1, p2));

        FlashcardService service = new FlashcardService(cardMapper, knowledgeMapper);
        int count = service.generateForDocument("doc1");

        assertEquals(2, count);
        verify(cardMapper, times(2)).insert(any(FlashCard.class));
    }

    @Test
    void generateForDocumentWithNoPointsReturnsZero() {
        when(knowledgeMapper.findByDocumentId("doc1")).thenReturn(List.of());

        FlashcardService service = new FlashcardService(cardMapper, knowledgeMapper);
        int count = service.generateForDocument("doc1");

        assertEquals(0, count);
        verify(cardMapper, never()).insert(any(FlashCard.class));
    }

    @Test
    void deleteRemovesCard() {
        FlashcardService service = new FlashcardService(cardMapper, knowledgeMapper);
        service.delete("card1");

        verify(cardMapper).deleteById("card1");
    }
}