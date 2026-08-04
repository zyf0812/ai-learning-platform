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

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FlashcardServiceTest {

    @Mock
    private FlashCardMapper flashCardMapper;

    @Mock
    private KnowledgeMapper knowledgeMapper;

    private FlashcardService service;

    @BeforeEach
    void setUp() {
        service = new FlashcardService(flashCardMapper, knowledgeMapper);
    }

    // ===== review: card not found =====

    @Test
    void reviewMissingCardThrows() {
        when(flashCardMapper.findById("nope")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class, () -> service.review("nope", 5));
        assertEquals("闪卡不存在", ex.getMessage());
    }

    // ===== review: quality < 3 resets reps and interval =====

    @Test
    void reviewQualityZeroResetsToDayOne() {
        FlashCard card = freshCard();
        card.setRepetitions(5);
        card.setInterval(30);
        card.setEaseFactor(2.5);
        when(flashCardMapper.findById("c1")).thenReturn(card);

        var result = service.review("c1", 0);

        assertEquals(0, result.get("repetitions"));
        assertEquals(1, result.get("interval"));
        // Ease still degrades per formula
        ArgumentCaptor<FlashCard> captor = ArgumentCaptor.forClass(FlashCard.class);
        verify(flashCardMapper).updateReview(captor.capture());
        FlashCard updated = captor.getValue();
        assertEquals(0, updated.getRepetitions());
        assertEquals(1, updated.getInterval());
        assertNotNull(updated.getNextReview());
    }

    @Test
    void reviewQualityTwoStillResets() {
        FlashCard card = freshCard();
        when(flashCardMapper.findById("c1")).thenReturn(card);

        var result = service.review("c1", 2);
        assertEquals(0, result.get("repetitions"));
        assertEquals(1, result.get("interval"));
    }

    // ===== review: quality >= 3 progressions =====

    @Test
    void reviewQualityThreeFirstSuccessSetsIntervalOne() {
        FlashCard card = freshCard();
        card.setRepetitions(0);
        card.setInterval(0);
        when(flashCardMapper.findById("c1")).thenReturn(card);

        var result = service.review("c1", 3);

        assertEquals(1, result.get("repetitions"));
        assertEquals(1, result.get("interval"));
    }

    @Test
    void reviewQualityFourSecondSuccessSetsIntervalSix() {
        FlashCard card = freshCard();
        card.setRepetitions(1);
        card.setInterval(1);
        card.setEaseFactor(2.5);
        when(flashCardMapper.findById("c1")).thenReturn(card);

        var result = service.review("c1", 4);

        assertEquals(2, result.get("repetitions"));
        assertEquals(6, result.get("interval"));
    }

    @Test
    void reviewQualityFiveThirdSuccessMultipliesByEase() {
        // reps >= 2 -> interval = round(oldInterval * updatedEase)
        // quality 5: ease = 2.5 + 0.1 = 2.6; interval = round(6 * 2.6) = round(15.6) = 16
        FlashCard card = freshCard();
        card.setRepetitions(2);
        card.setInterval(6);
        card.setEaseFactor(2.5);
        when(flashCardMapper.findById("c1")).thenReturn(card);

        var result = service.review("c1", 5);

        assertEquals(3, result.get("repetitions"));
        assertEquals(16, result.get("interval"));
    }

    // ===== review: ease factor floor at 1.3 =====

    @Test
    void reviewLowQualityDoesNotDropEaseBelowFloor() {
        FlashCard card = freshCard();
        card.setEaseFactor(1.35);
        when(flashCardMapper.findById("c1")).thenReturn(card);

        // quality 0: ease = 1.35 + (0.1 - 5*(0.08+5*0.02))
        // = 1.35 + (0.1 - 5*0.18) = 1.35 + (0.1 - 0.9) = 1.35 - 0.8 = 0.55 -> floored to 1.3
        var result = service.review("c1", 0);

        assertEquals(1.3, (double) result.get("easeFactor"), 0.0001);
    }

    // ===== review: ease increases on max quality =====

    @Test
    void reviewQualityFiveIncreasesEase() {
        FlashCard card = freshCard();
        card.setEaseFactor(2.5);
        when(flashCardMapper.findById("c1")).thenReturn(card);

        // ease = 2.5 + (0.1 - 0 * (...)) = 2.6
        var result = service.review("c1", 5);
        assertEquals(2.6, (double) result.get("easeFactor"), 0.0001);
    }

    // ===== getTodayCards =====

    @Test
    void getTodayCardsAssemblesStats() {
        FlashCard c = freshCard();
        when(flashCardMapper.findDueCards("u1", 20)).thenReturn(List.of(c));
        when(flashCardMapper.countByUserId("u1")).thenReturn(100);
        when(flashCardMapper.countDue("u1")).thenReturn(20);

        var result = service.getTodayCards("u1");

        assertEquals(1, ((List<?>) result.get("cards")).size());
        @SuppressWarnings("unchecked")
        var stats = (java.util.Map<String, Object>) result.get("stats");
        assertEquals(100, stats.get("total"));
        assertEquals(20, stats.get("due"));
    }

    // ===== generateForDocument =====

    @Test
    void generateForDocumentCreatesOneCardPerKnowledgePoint() {
        KnowledgePoint k1 = new KnowledgePoint();
        k1.setId("k1");
        k1.setTitle("Spring");
        k1.setContent("A framework");
        KnowledgePoint k2 = new KnowledgePoint();
        k2.setId("k2");
        k2.setTitle("JPA");
        k2.setContent("ORM spec");

        when(knowledgeMapper.findByDocumentId("d1")).thenReturn(List.of(k1, k2));

        int count = service.generateForDocument("d1");

        assertEquals(2, count);
        verify(flashCardMapper, times(2)).insert(any(FlashCard.class));
    }

    @Test
    void generateForDocumentEmptyKnowledgePoints() {
        when(knowledgeMapper.findByDocumentId("d1")).thenReturn(List.of());
        assertEquals(0, service.generateForDocument("d1"));
        verify(flashCardMapper, never()).insert(any());
    }

    // ===== delete =====

    @Test
    void deleteDelegatesToMapper() {
        service.delete("c1");
        verify(flashCardMapper).deleteById("c1");
    }

    // ===== helpers =====

    private FlashCard freshCard() {
        FlashCard c = new FlashCard();
        c.setId("c1");
        c.setEaseFactor(2.5);
        c.setInterval(1);
        c.setRepetitions(0);
        return c;
    }
}
