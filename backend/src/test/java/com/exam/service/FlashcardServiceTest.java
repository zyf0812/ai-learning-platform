package com.exam.service;

import com.exam.mapper.FlashCardMapper;
import com.exam.mapper.KnowledgeMapper;
import com.exam.model.FlashCard;
import com.exam.model.KnowledgePoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class FlashcardServiceTest {

    @Mock
    private FlashCardMapper flashCardMapper;

    @Mock
    private KnowledgeMapper knowledgeMapper;

    @Test
    void reviewQuality5FirstTimeSetsInterval1() {
        FlashCard card = new FlashCard();
        card.setId("card1");
        card.setEaseFactor(2.5);
        card.setInterval(0);
        card.setRepetitions(0);
        when(flashCardMapper.findById("card1")).thenReturn(card);

        FlashcardService service = new FlashcardService(flashCardMapper, knowledgeMapper);
        Map<String, Object> result = service.review("card1", 5);

        assertEquals(1, result.get("interval"));
        assertEquals(1, result.get("repetitions"));
        assertEquals(2.6, result.get("easeFactor"));
        verify(flashCardMapper).updateReview(any(FlashCard.class));
    }

    @Test
    void reviewQuality5SecondTimeSetsInterval6() {
        FlashCard card = new FlashCard();
        card.setId("card1");
        card.setEaseFactor(2.5);
        card.setInterval(1);
        card.setRepetitions(1);
        when(flashCardMapper.findById("card1")).thenReturn(card);

        FlashcardService service = new FlashcardService(flashCardMapper, knowledgeMapper);
        Map<String, Object> result = service.review("card1", 5);

        assertEquals(6, result.get("interval"));
        assertEquals(2, result.get("repetitions"));
        assertEquals(2.7, result.get("easeFactor"));
    }

    @Test
    void reviewQuality5ThirdTimeAppliesEaseFactor() {
        FlashCard card = new FlashCard();
        card.setId("card1");
        card.setEaseFactor(2.5);
        card.setInterval(6);
        card.setRepetitions(2);
        when(flashCardMapper.findById("card1")).thenReturn(card);

        FlashcardService service = new FlashcardService(flashCardMapper, knowledgeMapper);
        Map<String, Object> result = service.review("card1", 5);

        assertEquals(15, result.get("interval"));
        assertEquals(3, result.get("repetitions"));
        assertEquals(2.6, result.get("easeFactor"));
    }

    @Test
    void reviewQuality2ResetsInterval() {
        FlashCard card = new FlashCard();
        card.setId("card1");
        card.setEaseFactor(2.5);
        card.setInterval(10);
        card.setRepetitions(3);
        when(flashCardMapper.findById("card1")).thenReturn(card);

        FlashcardService service = new FlashcardService(flashCardMapper, knowledgeMapper);
        Map<String, Object> result = service.review("card1", 2);

        assertEquals(1, result.get("interval"));
        assertEquals(0, result.get("repetitions"));
        assertTrue((Double) result.get("easeFactor") < 2.5);
    }

    @Test
    void reviewQuality0DecreasesEaseFactor() {
        FlashCard card = new FlashCard();
        card.setId("card1");
        card.setEaseFactor(2.5);
        card.setInterval(10);
        card.setRepetitions(3);
        when(flashCardMapper.findById("card1")).thenReturn(card);

        FlashcardService service = new FlashcardService(flashCardMapper, knowledgeMapper);
        Map<String, Object> result = service.review("card1", 0);

        assertEquals(1, result.get("interval"));
        assertEquals(0, result.get("repetitions"));
        assertEquals(1.3, result.get("easeFactor"));
    }

    @Test
    void easeFactorNeverDropsBelow1Point3() {
        FlashCard card = new FlashCard();
        card.setId("card1");
        card.setEaseFactor(1.3);
        card.setInterval(1);
        card.setRepetitions(1);
        when(flashCardMapper.findById("card1")).thenReturn(card);

        FlashcardService service = new FlashcardService(flashCardMapper, knowledgeMapper);
        Map<String, Object> result = service.review("card1", 0);

        assertEquals(1.3, result.get("easeFactor"));
    }

    @Test
    void reviewNonExistentCardThrows() {
        when(flashCardMapper.findById("nonexistent")).thenReturn(null);

        FlashcardService service = new FlashcardService(flashCardMapper, knowledgeMapper);

        assertThrows(RuntimeException.class, () -> service.review("nonexistent", 5));
    }

    @Test
    void generateForDocumentCreatesCards() {
        KnowledgePoint p1 = new KnowledgePoint();
        p1.setId("kp1");
        p1.setTitle("问题1");
        p1.setContent("答案1");

        KnowledgePoint p2 = new KnowledgePoint();
        p2.setId("kp2");
        p2.setTitle("问题2");
        p2.setContent("答案2");

        when(knowledgeMapper.findByDocumentId("doc1")).thenReturn(List.of(p1, p2));

        FlashcardService service = new FlashcardService(flashCardMapper, knowledgeMapper);
        int count = service.generateForDocument("doc1");

        assertEquals(2, count);
        verify(flashCardMapper, times(2)).insert(any(FlashCard.class));
    }

    @Test
    void generateForDocumentWithNoKnowledgePoints() {
        when(knowledgeMapper.findByDocumentId("doc1")).thenReturn(List.of());

        FlashcardService service = new FlashcardService(flashCardMapper, knowledgeMapper);
        int count = service.generateForDocument("doc1");

        assertEquals(0, count);
        verify(flashCardMapper, never()).insert(any(FlashCard.class));
    }

    @Test
    void deleteCardCallsMapper() {
        FlashcardService service = new FlashcardService(flashCardMapper, knowledgeMapper);
        service.delete("card1");

        verify(flashCardMapper).deleteById("card1");
    }
}