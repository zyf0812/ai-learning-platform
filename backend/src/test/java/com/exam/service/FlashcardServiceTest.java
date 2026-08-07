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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
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

    private FlashCard newCard(int reps, int interval, double ease) {
        FlashCard c = new FlashCard();
        c.setId("c1");
        c.setRepetitions(reps);
        c.setInterval(interval);
        c.setEaseFactor(ease);
        c.setNextReview(LocalDateTime.now());
        return c;
    }

    // ══════════════════════════════════════════════════════════════════
    // SM-2 review() algorithm
    // ══════════════════════════════════════════════════════════════════

    // ── quality < 3: failure → reps=0, interval=1 ────────────────────

    @Test
    void reviewQualityZeroResetsRepsAndSetsInterval1() {
        FlashCard c = newCard(5, 30, 2.5);
        when(mapper.findById("c1")).thenReturn(c);
        doNothing().when(mapper).updateReview(any());

        var res = flashcardService.review("c1", 0);
        assertEquals(0, res.get("repetitions"));
        assertEquals(1, res.get("interval"));
    }

    @Test
    void reviewQualityTwoStillFailsResets() {
        FlashCard c = newCard(3, 10, 2.0);
        when(mapper.findById("c1")).thenReturn(c);
        doNothing().when(mapper).updateReview(any());

        var res = flashcardService.review("c1", 2);
        assertEquals(0, res.get("repetitions"));
        assertEquals(1, res.get("interval"));
    }

    // ── quality = 3: pass (barely) ───────────────────────────────────

    @Test
    void reviewQualityThreeFirstRepSetsInterval1() {
        // reps=0 → after increment reps=1 → interval=1
        FlashCard c = newCard(0, 0, 2.5);
        when(mapper.findById("c1")).thenReturn(c);
        doNothing().when(mapper).updateReview(any());

        var res = flashcardService.review("c1", 3);
        assertEquals(1, res.get("repetitions"));
        assertEquals(1, res.get("interval"));
    }

    @Test
    void reviewQualityThreeSecondRepSetsInterval6() {
        // reps=1 → after increment reps=2 → interval=6
        FlashCard c = newCard(1, 1, 2.5);
        when(mapper.findById("c1")).thenReturn(c);
        doNothing().when(mapper).updateReview(any());

        var res = flashcardService.review("c1", 3);
        assertEquals(2, res.get("repetitions"));
        assertEquals(6, res.get("interval"));
    }

    @Test
    void reviewQualityThreeThirdRepComputesIntervalTimesEase() {
        // reps=2 → after increment reps=3
        // interval = round(prev_interval * ease) = round(6 * 2.5) = round(15.0) = 15
        FlashCard c = newCard(2, 6, 2.5);
        when(mapper.findById("c1")).thenReturn(c);
        doNothing().when(mapper).updateReview(any());

        var res = flashcardService.review("c1", 3);
        assertEquals(3, res.get("repetitions"));
        assertEquals(15, res.get("interval"));
    }

    // ── quality = 5: perfect response (ease goes up slightly) ────────

    @Test
    void reviewQuality5EaseFactorIncreases() {
        // ease_new = 2.5 + (0.1 - (5-5)*(0.08 + (5-5)*0.02)) = 2.5 + 0.1 = 2.6
        FlashCard c = newCard(5, 30, 2.5);
        when(mapper.findById("c1")).thenReturn(c);
        doNothing().when(mapper).updateReview(any());

        var res = flashcardService.review("c1", 5);
        assertEquals(2.6, (Double) res.get("easeFactor"), 1e-9);
        assertEquals(6, res.get("repetitions"));
        // new interval = round(30 * 2.6) = round(78.0) = 78
        assertEquals(78, res.get("interval"));
    }

    @Test
    void reviewQuality3EaseFactorDecreases() {
        // q=3: ease_new = 2.5 + (0.1 - 2*(0.08 + 2*0.02))
        //                = 2.5 + (0.1 - 2*(0.12))
        //                = 2.5 + (0.1 - 0.24)
        //                = 2.5 - 0.14 = 2.36
        FlashCard c = newCard(2, 6, 2.5);
        when(mapper.findById("c1")).thenReturn(c);
        doNothing().when(mapper).updateReview(any());

        var res = flashcardService.review("c1", 3);
        assertEquals(2.36, (Double) res.get("easeFactor"), 1e-9);
    }

    @Test
    void reviewEaseFactorHasLowerFloor1Point3() {
        // Start with low ease. Very bad quality should lower ease further, but floor at 1.3
        // q=0: ease_new = 1.5 + (0.1 - 5*(0.08 + 5*0.02))
        //           = 1.5 + (0.1 - 5*0.18)
        //           = 1.5 + (0.1 - 0.90)
        //           = 1.5 - 0.80 = 0.70 → floor at 1.3
        FlashCard c = newCard(5, 30, 1.5);
        when(mapper.findById("c1")).thenReturn(c);
        doNothing().when(mapper).updateReview(any());

        var res = flashcardService.review("c1", 0);
        assertEquals(1.3, (Double) res.get("easeFactor"), 1e-9,
            "ease factor should be floored at 1.3");
    }

    // ── edge: nonexistent card ───────────────────────────────────────

    @Test
    void reviewMissingCardThrows() {
        when(mapper.findById("ghost")).thenReturn(null);
        assertThrows(RuntimeException.class,
            () -> flashcardService.review("ghost", 4));
    }

    // ── persistence: updateReview called ─────────────────────────────

    @Test
    void reviewPersistsUpdatedCard() {
        FlashCard c = newCard(2, 6, 2.5);
        when(mapper.findById("c1")).thenReturn(c);
        doNothing().when(mapper).updateReview(any());

        flashcardService.review("c1", 5);
        verify(mapper).updateReview(argMatch ->
            argMatch.getEaseFactor() == 2.6 &&
            argMatch.getInterval() == (int) Math.round(6 * 2.6) &&
            argMatch.getRepetitions() == 3 &&
            argMatch.getNextReview() != null
        );
    }

    // ══════════════════════════════════════════════════════════════════
    // getTodayCards()
    // ══════════════════════════════════════════════════════════════════

    @Test
    void getTodayCardsReturnsCardsAndStats() {
        var cards = List.of(new FlashCard());
        when(mapper.findDueCards("u1", 20)).thenReturn(cards);
        when(mapper.countByUserId("u1")).thenReturn(100);
        when(mapper.countDue("u1")).thenReturn(15);

        var res = flashcardService.getTodayCards("u1");
        assertSame(cards, res.get("cards"));
        @SuppressWarnings("unchecked")
        Map<String, Object> stats = (Map<String, Object>) res.get("stats");
        assertEquals(100, stats.get("total"));
        assertEquals(15, stats.get("due"));
    }

    // ══════════════════════════════════════════════════════════════════
    // generateForDocument()
    // ══════════════════════════════════════════════════════════════════

    @Test
    void generateForDocumentCreatesFlashcardsForEachKnowledgePoint() {
        KnowledgePoint kp1 = new KnowledgePoint();
        kp1.setId("kp1"); kp1.setTitle("What is OOP?"); kp1.setContent("OOP stands for...");
        KnowledgePoint kp2 = new KnowledgePoint();
        kp2.setId("kp2"); kp2.setTitle("Polymorphism"); kp2.setContent("Many forms");
        when(knowledgeMapper.findByDocumentId("d1")).thenReturn(List.of(kp1, kp2));
        doNothing().when(mapper).insert(any());

        int count = flashcardService.generateForDocument("d1");
        assertEquals(2, count);
        verify(mapper, times(2)).insert(any());
    }

    @Test
    void generateForDocumentNoPointsCreatesZeroCards() {
        when(knowledgeMapper.findByDocumentId("d1")).thenReturn(List.of());

        assertEquals(0, flashcardService.generateForDocument("d1"));
        verify(mapper, never()).insert(any());
    }

    // ══════════════════════════════════════════════════════════════════
    // delete()
    // ══════════════════════════════════════════════════════════════════

    @Test
    void deleteDelegatesToMapper() {
        doNothing().when(mapper).deleteById("c1");
        assertDoesNotThrow(() -> flashcardService.delete("c1"));
        verify(mapper).deleteById("c1");
    }
}
