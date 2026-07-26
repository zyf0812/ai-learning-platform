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
    void reviewWithQuality5PerfectIncreasesInterval() {
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
    void reviewWithQuality2PoorResetsToInitial() {
        FlashCard card = new FlashCard();
        card.setId("card1");
        card.setEaseFactor(2.5);
        card.setInterval(10);
        card.setRepetitions(5);

        when(cardMapper.findById("card1")).thenReturn(card);

        FlashcardService service = new FlashcardService(cardMapper, knowledgeMapper);
        Map<String, Object> result = service.review("card1", 2);

        assertEquals(2.5, result.get("easeFactor"));
        assertEquals(1, result.get("interval"));
        assertEquals(0, result.get("repetitions"));
    }

    @Test
    void reviewWithQuality3GoodFirstReview() {
        FlashCard card = new FlashCard();
        card.setId("card1");
        card.setEaseFactor(2.5);
        card.setInterval(0);
        card.setRepetitions(0);

        when(cardMapper.findById("card1")).thenReturn(card);

        FlashcardService service = new FlashcardService(cardMapper, knowledgeMapper);
        Map<String, Object> result = service.review("card1", 3);

        assertEquals(2.36, result.get("easeFactor"));
        assertEquals(1, result.get("interval"));
        assertEquals(1, result.get("repetitions"));
    }

    @Test
    void reviewWithQuality3GoodSecondReview() {
        FlashCard card = new FlashCard();
        card.setId("card1");
        card.setEaseFactor(2.5);
        card.setInterval(1);
        card.setRepetitions(1);

        when(cardMapper.findById("card1")).thenReturn(card);

        FlashcardService service = new FlashcardService(cardMapper, knowledgeMapper);
        Map<String, Object> result = service.review("card1", 3);

        assertEquals(2.36, result.get("easeFactor"));
        assertEquals(6, result.get("interval"));
        assertEquals(2, result.get("repetitions"));
    }

    @Test
    void easeFactorNeverDropsBelow13() {
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
    void reviewWithQuality1DecreasesEaseFactor() {
        FlashCard card = new FlashCard();
        card.setId("card1");
        card.setEaseFactor(2.5);
        card.setInterval(5);
        card.setRepetitions(3);

        when(cardMapper.findById("card1")).thenReturn(card);

        FlashcardService service = new FlashcardService(cardMapper, knowledgeMapper);
        Map<String, Object> result = service.review("card1", 1);

        assertEquals(2.28, result.get("easeFactor"));
        assertEquals(1, result.get("interval"));
        assertEquals(0, result.get("repetitions"));
    }

    @Test
    void generateForDocumentCreatesCardsFromKnowledgePoints() {
        KnowledgePoint p1 = new KnowledgePoint();
        p1.setId("kp1");
        p1.setTitle("title1");
        p1.setContent("content1");

        KnowledgePoint p2 = new KnowledgePoint();
        p2.setId("kp2");
        p2.setTitle("title2");
        p2.setContent("content2");

        when(knowledgeMapper.findByDocumentId("doc1")).thenReturn(List.of(p1, p2));

        FlashcardService service = new FlashcardService(cardMapper, knowledgeMapper);
        int count = service.generateForDocument("doc1");

        assertEquals(2, count);
        verify(cardMapper, times(2)).insert(any(FlashCard.class));
    }

    @Test
    void generateForDocumentWithEmptyKnowledgeReturnsZero() {
        when(knowledgeMapper.findByDocumentId("doc1")).thenReturn(List.of());

        FlashcardService service = new FlashcardService(cardMapper, knowledgeMapper);
        int count = service.generateForDocument("doc1");

        assertEquals(0, count);
        verify(cardMapper, never()).insert(any(FlashCard.class));
    }

    @Test
    void reviewNonExistentCardThrows() {
        when(cardMapper.findById("nonexistent")).thenReturn(null);

        FlashcardService service = new FlashcardService(cardMapper, knowledgeMapper);

        assertThrows(RuntimeException.class, () -> service.review("nonexistent", 3));
    }

    @Test
    void deleteRemovesCard() {
        FlashcardService service = new FlashcardService(cardMapper, knowledgeMapper);
        service.delete("card1");

        verify(cardMapper).deleteById("card1");
    }

    private void assertEquals(double expected, Object actual) {
        assertEquals(expected, (Double) actual, 0.01);
    }

    private void assertEquals(int expected, Object actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, (Integer) actual);
    }
}