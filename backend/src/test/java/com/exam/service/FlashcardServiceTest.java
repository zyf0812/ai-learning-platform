package com.exam.service;

import com.exam.mapper.FlashCardMapper;
import com.exam.mapper.KnowledgeMapper;
import com.exam.model.FlashCard;
import com.exam.model.KnowledgePoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
    private FlashCardMapper flashCardMapper;

    @Mock
    private KnowledgeMapper knowledgeMapper;

    private FlashcardService flashcardService;

    @BeforeEach
    void setUp() {
        flashcardService = new FlashcardService(flashCardMapper, knowledgeMapper);
    }

    private FlashCard createCard(double easeFactor, int interval, int repetitions) {
        FlashCard card = new FlashCard();
        card.setId("card1");
        card.setEaseFactor(easeFactor);
        card.setInterval(interval);
        card.setRepetitions(repetitions);
        card.setNextReview(LocalDateTime.now());
        return card;
    }

    @Test
    void reviewNonexistentCardThrowsException() {
        when(flashCardMapper.findById("nonexistent")).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> flashcardService.review("nonexistent", 3));

        assertEquals("闪卡不存在", ex.getMessage());
    }

    @Test
    void reviewQualityZeroResetsRepetitionsAndInterval() {
        FlashCard card = createCard(2.5, 10, 5);
        when(flashCardMapper.findById("card1")).thenReturn(card);

        Map<String, Object> result = flashcardService.review("card1", 0);

        assertEquals(0, result.get("repetitions"));
        assertEquals(1, result.get("interval"));
        verify(flashCardMapper).updateReview(any(FlashCard.class));
    }

    @Test
    void reviewQualityTwoResetsRepetitions() {
        FlashCard card = createCard(2.5, 10, 5);
        when(flashCardMapper.findById("card1")).thenReturn(card);

        Map<String, Object> result = flashcardService.review("card1", 2);

        assertEquals(0, result.get("repetitions"));
        assertEquals(1, result.get("interval"));
    }

    @Test
    void reviewQualityThreeFirstRepetition() {
        FlashCard card = createCard(2.5, 0, 0);
        when(flashCardMapper.findById("card1")).thenReturn(card);

        Map<String, Object> result = flashcardService.review("card1", 3);

        assertEquals(1, result.get("repetitions"));
        assertEquals(1, result.get("interval"));
    }

    @Test
    void reviewQualityFiveFirstRepetition() {
        FlashCard card = createCard(2.5, 0, 0);
        when(flashCardMapper.findById("card1")).thenReturn(card);

        Map<String, Object> result = flashcardService.review("card1", 5);

        assertEquals(1, result.get("repetitions"));
        assertEquals(1, result.get("interval"));
    }

    @Test
    void reviewQualityThreeSecondRepetition() {
        FlashCard card = createCard(2.5, 1, 1);
        when(flashCardMapper.findById("card1")).thenReturn(card);

        Map<String, Object> result = flashcardService.review("card1", 3);

        assertEquals(2, result.get("repetitions"));
        assertEquals(6, result.get("interval"));
    }

    @Test
    void reviewQualityFourSecondRepetition() {
        FlashCard card = createCard(2.5, 1, 1);
        when(flashCardMapper.findById("card1")).thenReturn(card);

        Map<String, Object> result = flashcardService.review("card1", 4);

        assertEquals(2, result.get("repetitions"));
        assertEquals(6, result.get("interval"));
    }

    @Test
    void reviewQualityFiveThirdRepetitionIncreasesInterval() {
        FlashCard card = createCard(2.5, 6, 2);
        when(flashCardMapper.findById("card1")).thenReturn(card);

        Map<String, Object> result = flashcardService.review("card1", 5);

        assertEquals(3, result.get("repetitions"));
        int interval = (int) result.get("interval");
        assertTrue(interval > 6, "Interval should increase after successful review");
    }

    @Test
    void reviewQualityThreeThirdRepetitionLowerEase() {
        FlashCard card = createCard(2.5, 6, 2);
        when(flashCardMapper.findById("card1")).thenReturn(card);

        Map<String, Object> result = flashcardService.review("card1", 3);

        double ease = (double) result.get("easeFactor");
        assertTrue(ease < 2.5, "Ease factor should decrease with quality 3");
        assertEquals(3, result.get("repetitions"));
    }

    @Test
    void easeFactorNeverGoesBelowMinimum() {
        FlashCard card = createCard(1.3, 10, 5);
        when(flashCardMapper.findById("card1")).thenReturn(card);

        Map<String, Object> result = flashcardService.review("card1", 0);

        double ease = (double) result.get("easeFactor");
        assertTrue(ease >= 1.3, "Ease factor should not drop below 1.3");
    }

    @Test
    void easeFactorAtMinimumStaysAtMinimum() {
        FlashCard card = createCard(1.3, 5, 3);
        when(flashCardMapper.findById("card1")).thenReturn(card);

        Map<String, Object> result = flashcardService.review("card1", 1);

        double ease = (double) result.get("easeFactor");
        assertEquals(1.3, ease, 0.001);
    }

    @Test
    void qualityFiveIncreasesEaseFactor() {
        FlashCard card = createCard(2.5, 10, 5);
        when(flashCardMapper.findById("card1")).thenReturn(card);

        Map<String, Object> result = flashcardService.review("card1", 5);

        double ease = (double) result.get("easeFactor");
        assertTrue(ease > 2.5, "Ease factor should increase with quality 5");
    }

    @Test
    void qualityFourSlightlyIncreasesEaseFactor() {
        FlashCard card = createCard(2.5, 10, 5);
        when(flashCardMapper.findById("card1")).thenReturn(card);

        Map<String, Object> result5 = flashcardService.review("card1", 5);
        double ease5 = (double) result5.get("easeFactor");

        card.setEaseFactor(2.5);
        Map<String, Object> result4 = flashcardService.review("card1", 4);
        double ease4 = (double) result4.get("easeFactor");

        assertTrue(ease5 > ease4, "Quality 5 should increase ease more than quality 4");
    }

    @Test
    void reviewUpdatesNextReviewDate() {
        FlashCard card = createCard(2.5, 6, 2);
        when(flashCardMapper.findById("card1")).thenReturn(card);

        flashcardService.review("card1", 4);

        ArgumentCaptor<FlashCard> captor = ArgumentCaptor.forClass(FlashCard.class);
        verify(flashCardMapper).updateReview(captor.capture());

        FlashCard updated = captor.getValue();
        assertNotNull(updated.getNextReview());
        assertTrue(updated.getNextReview().isAfter(LocalDateTime.now().minusMinutes(1)));
    }

    @Test
    void getTodayCardsReturnsCardsAndStats() {
        FlashCard card = createCard(2.5, 1, 1);
        when(flashCardMapper.findDueCards("user1", 20)).thenReturn(List.of(card));
        when(flashCardMapper.countByUserId("user1")).thenReturn(10);
        when(flashCardMapper.countDue("user1")).thenReturn(3);

        Map<String, Object> result = flashcardService.getTodayCards("user1");

        assertNotNull(result.get("cards"));
        assertNotNull(result.get("stats"));

        @SuppressWarnings("unchecked")
        Map<String, Object> stats = (Map<String, Object>) result.get("stats");
        assertEquals(10, stats.get("total"));
        assertEquals(3, stats.get("due"));
    }

    @Test
    void generateForDocumentCreatesCardsFromKnowledgePoints() {
        KnowledgePoint kp1 = new KnowledgePoint();
        kp1.setId("kp1");
        kp1.setTitle("What is Java?");
        kp1.setContent("A programming language");

        KnowledgePoint kp2 = new KnowledgePoint();
        kp2.setId("kp2");
        kp2.setTitle("What is Spring?");
        kp2.setContent("A framework");

        when(knowledgeMapper.findByDocumentId("doc1")).thenReturn(List.of(kp1, kp2));

        int count = flashcardService.generateForDocument("doc1");

        assertEquals(2, count);
        verify(flashCardMapper, times(2)).insert(any(FlashCard.class));
    }

    @Test
    void generateForDocumentWithNoKnowledgePointsReturnsZero() {
        when(knowledgeMapper.findByDocumentId("doc1")).thenReturn(List.of());

        int count = flashcardService.generateForDocument("doc1");

        assertEquals(0, count);
        verify(flashCardMapper, never()).insert(any(FlashCard.class));
    }

    @Test
    void deleteCardCallsMapperDelete() {
        flashcardService.delete("card1");

        verify(flashCardMapper).deleteById("card1");
    }

    @Test
    void qualityOneResetsRepetitions() {
        FlashCard card = createCard(2.5, 10, 5);
        when(flashCardMapper.findById("card1")).thenReturn(card);

        Map<String, Object> result = flashcardService.review("card1", 1);

        assertEquals(0, result.get("repetitions"));
        assertEquals(1, result.get("interval"));
    }

    @Test
    void highRepetitionQualityFiveIntervalScales() {
        FlashCard card = createCard(2.5, 30, 10);
        when(flashCardMapper.findById("card1")).thenReturn(card);

        Map<String, Object> result = flashcardService.review("card1", 5);

        int interval = (int) result.get("interval");
        assertTrue(interval > 30, "Interval should continue to scale at high repetitions");
    }
}
