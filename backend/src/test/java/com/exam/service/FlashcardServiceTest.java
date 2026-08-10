package com.exam.service;

import com.exam.mapper.FlashCardMapper;
import com.exam.mapper.KnowledgeMapper;
import com.exam.model.FlashCard;
import com.exam.model.KnowledgePoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FlashcardServiceTest {

    @Mock private FlashCardMapper mapper;
    @Mock private KnowledgeMapper knowledgeMapper;

    private FlashcardService flashcardService;

    @BeforeEach
    void setUp() {
        flashcardService = new FlashcardService(mapper, knowledgeMapper);
    }

    // === review() tests ===

    @Test
    void reviewHighQualityIncreasesRepetition() {
        FlashCard card = createCard(2.5, 0.0, 0, 0);
        when(mapper.findById("c1")).thenReturn(card);

        var result = flashcardService.review("c1", 5);

        assertEquals(1, result.get("repetitions"));
        assertEquals(1, result.get("interval"));
        assertEquals(2.5, result.get("easeFactor"));
    }

    @Test
    void reviewQuality4CalculatesCorrectly() {
        FlashCard card = createCard(2.5, 1.0, 1, 1);
        when(mapper.findById("c1")).thenReturn(card);

        var result = flashcardService.review("c1", 4);

        double newEase = (Double) result.get("easeFactor");
        assertTrue(newEase >= 1.3);
        assertEquals(2, result.get("repetitions"));
        assertEquals(6, result.get("interval"));
    }

    @Test
    void reviewQuality3TransitionsFromReps1ToReps2() {
        FlashCard card = createCard(2.5, 1.0, 1, 1);
        when(mapper.findById("c1")).thenReturn(card);

        var result = flashcardService.review("c1", 3);

        assertEquals(2, result.get("repetitions"));
        assertEquals(6, result.get("interval"));
    }

    @Test
    void reviewQuality2ResetsInterval() {
        FlashCard card = createCard(2.5, 10.0, 5, 3);
        when(mapper.findById("c1")).thenReturn(card);

        var result = flashcardService.review("c1", 2);

        assertEquals(0, result.get("repetitions"));
        assertEquals(1, result.get("interval"));
    }

    @Test
    void reviewQuality1ResetsInterval() {
        FlashCard card = createCard(2.5, 10.0, 5, 3);
        when(mapper.findById("c1")).thenReturn(card);

        var result = flashcardService.review("c1", 1);

        assertEquals(0, result.get("repetitions"));
        assertEquals(1, result.get("interval"));
    }

    @Test
    void reviewQuality0ResetsInterval() {
        FlashCard card = createCard(2.5, 10.0, 5, 3);
        when(mapper.findById("c1")).thenReturn(card);

        var result = flashcardService.review("c1", 0);

        assertEquals(0, result.get("repetitions"));
        assertEquals(1, result.get("interval"));
    }

    @Test
    void reviewQuality3DecreasesEaseFactor() {
        FlashCard card = createCard(2.5, 10.0, 5, 3);
        when(mapper.findById("c1")).thenReturn(card);

        var result = flashcardService.review("c1", 3);

        double newEase = (Double) result.get("easeFactor");
        assertTrue(newEase < 2.5);
    }

    @Test
    void reviewQuality5KeepsEaseFactorSame() {
        FlashCard card = createCard(2.5, 10.0, 5, 3);
        when(mapper.findById("c1")).thenReturn(card);

        var result = flashcardService.review("c1", 5);

        double newEase = (Double) result.get("easeFactor");
        assertEquals(2.5, newEase, 0.001);
    }

    @Test
    void reviewEaseFactorMinimumIs1_3() {
        FlashCard card = createCard(1.2, 1.0, 0, 0);
        when(mapper.findById("c1")).thenReturn(card);

        var result = flashcardService.review("c1", 1);

        double ease = (Double) result.get("easeFactor");
        assertEquals(1.3, ease, 0.001);
    }

    @Test
    void reviewNonExistentCardThrows() {
        when(mapper.findById("c1")).thenReturn(null);

        assertThrows(RuntimeException.class, () -> flashcardService.review("c1", 5));
    }

    @Test
    void reviewCalculatesIntervalFromPrevious() {
        FlashCard card = createCard(2.5, 6.0, 3, 2);
        when(mapper.findById("c1")).thenReturn(card);

        var result = flashcardService.review("c1", 4);

        int interval = (Integer) result.get("interval");
        assertEquals(15, interval);
    }

    // === generateForDocument() tests ===

    @Test
    void generateForDocumentCreatesCards() {
        KnowledgePoint kp1 = new KnowledgePoint();
        kp1.setId("kp1");
        kp1.setTitle("Concept 1");
        kp1.setContent("Content 1");

        KnowledgePoint kp2 = new KnowledgePoint();
        kp2.setId("kp2");
        kp2.setTitle("Concept 2");
        kp2.setContent("Content 2");

        when(knowledgeMapper.findByDocumentId("d1")).thenReturn(List.of(kp1, kp2));

        int count = flashcardService.generateForDocument("d1");

        assertEquals(2, count);
        verify(mapper, times(2)).insert(any(FlashCard.class));
    }

    @Test
    void generateForDocumentEmptyKnowledgePoints() {
        when(knowledgeMapper.findByDocumentId("d1")).thenReturn(List.of());

        int count = flashcardService.generateForDocument("d1");

        assertEquals(0, count);
    }

    // === getTodayCards() tests ===

    @Test
    void getTodayCardsReturnsCardsAndStats() {
        FlashCard card = new FlashCard();
        when(mapper.findDueCards("u1", 20)).thenReturn(List.of(card));
        when(mapper.countByUserId("u1")).thenReturn(10);
        when(mapper.countDue("u1")).thenReturn(3);

        var result = flashcardService.getTodayCards("u1");

        assertNotNull(result.get("cards"));
        assertNotNull(result.get("stats"));
    }

    // === delete() tests ===

    @Test
    void deleteCardDelegatesToMapper() {
        flashcardService.delete("c1");
        verify(mapper).deleteById("c1");
    }

    // === helper ===

    private FlashCard createCard(double ease, int interval, int reps, int nextReview) {
        FlashCard card = new FlashCard();
        card.setId("c1");
        card.setEaseFactor(ease);
        card.setInterval(interval);
        card.setRepetitions(reps);
        return card;
    }
}