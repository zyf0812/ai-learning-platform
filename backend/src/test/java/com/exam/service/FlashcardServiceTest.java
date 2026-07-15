package com.exam.service;

import com.exam.mapper.FlashCardMapper;
import com.exam.mapper.KnowledgeMapper;
import com.exam.model.FlashCard;
import com.exam.model.KnowledgePoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FlashcardServiceTest {

    @Mock
    private FlashCardMapper cardMapper;

    @Mock
    private KnowledgeMapper knowledgeMapper;

    private final FlashcardService service = new FlashcardService(cardMapper, knowledgeMapper);

    @Test
    void reviewWithQuality5Perfect() {
        FlashCard card = new FlashCard();
        card.setId("card1");
        card.setEaseFactor(2.5);
        card.setInterval(1);
        card.setRepetitions(0);
        when(cardMapper.findById("card1")).thenReturn(card);

        var result = service.review("card1", 5);

        assertEquals(2.6, result.get("easeFactor"));
        assertEquals(1, result.get("interval"));
        assertEquals(1, result.get("repetitions"));
        verify(cardMapper).updateReview(card);
    }

    @Test
    void reviewWithQuality3Good() {
        FlashCard card = new FlashCard();
        card.setId("card1");
        card.setEaseFactor(2.5);
        card.setInterval(1);
        card.setRepetitions(1);
        when(cardMapper.findById("card1")).thenReturn(card);

        var result = service.review("card1", 3);

        assertEquals(2.4, result.get("easeFactor"));
        assertEquals(6, result.get("interval"));
        assertEquals(2, result.get("repetitions"));
    }

    @Test
    void reviewWithQuality2Forget() {
        FlashCard card = new FlashCard();
        card.setId("card1");
        card.setEaseFactor(2.5);
        card.setInterval(10);
        card.setRepetitions(5);
        when(cardMapper.findById("card1")).thenReturn(card);

        var result = service.review("card1", 2);

        assertEquals(1.7, result.get("easeFactor"));
        assertEquals(1, result.get("interval"));
        assertEquals(0, result.get("repetitions"));
    }

    @Test
    void reviewWithQuality0ResetsEaseFactor() {
        FlashCard card = new FlashCard();
        card.setId("card1");
        card.setEaseFactor(1.5);
        card.setInterval(5);
        card.setRepetitions(3);
        when(cardMapper.findById("card1")).thenReturn(card);

        var result = service.review("card1", 0);

        assertEquals(1.3, result.get("easeFactor"));
        assertEquals(1, result.get("interval"));
        assertEquals(0, result.get("repetitions"));
    }

    @Test
    void reviewWithThirdRepetitionCalculatesInterval() {
        FlashCard card = new FlashCard();
        card.setId("card1");
        card.setEaseFactor(2.5);
        card.setInterval(6);
        card.setRepetitions(2);
        when(cardMapper.findById("card1")).thenReturn(card);

        var result = service.review("card1", 5);

        assertEquals(2.6, result.get("easeFactor"));
        assertEquals(15, result.get("interval"));
        assertEquals(3, result.get("repetitions"));
    }

    @Test
    void reviewNonExistentCardThrows() {
        when(cardMapper.findById("nonexistent")).thenReturn(null);
        assertThrows(RuntimeException.class, () -> service.review("nonexistent", 5));
    }

    @Test
    void generateForDocumentCreatesCards() {
        KnowledgePoint p1 = new KnowledgePoint();
        p1.setId("kp1");
        p1.setTitle("知识点1");
        p1.setContent("内容1");

        KnowledgePoint p2 = new KnowledgePoint();
        p2.setId("kp2");
        p2.setTitle("知识点2");
        p2.setContent("内容2");

        when(knowledgeMapper.findByDocumentId("doc1")).thenReturn(List.of(p1, p2));

        int count = service.generateForDocument("doc1");

        assertEquals(2, count);
        verify(cardMapper, times(2)).insert(any(FlashCard.class));
    }

    @Test
    void generateForDocumentWithEmptyKnowledgePoints() {
        when(knowledgeMapper.findByDocumentId("doc1")).thenReturn(List.of());
        int count = service.generateForDocument("doc1");
        assertEquals(0, count);
        verify(cardMapper, never()).insert(any(FlashCard.class));
    }

    @Test
    void deleteCard() {
        service.delete("card1");
        verify(cardMapper).deleteById("card1");
    }
}