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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 闪卡复习调度（SM-2）是纯业务算法，此前完全未覆盖。
 * 这里锁定间隔递增、失败重置、easeFactor 下限等边界行为。
 */
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

    private static FlashCard card(double ease, int interval, int repetitions) {
        FlashCard card = new FlashCard();
        card.setId("card-1");
        card.setEaseFactor(ease);
        card.setInterval(interval);
        card.setRepetitions(repetitions);
        return card;
    }

    @Test
    void reviewMissingCardThrows() {
        when(flashCardMapper.findById("missing")).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> flashcardService.review("missing", 5));
        assertEquals("闪卡不存在", ex.getMessage());
        verify(flashCardMapper, never()).updateReview(any());
    }

    @Test
    void reviewFirstSuccessfulReviewSchedulesOneDay() {
        FlashCard card = card(2.5, 0, 0);
        when(flashCardMapper.findById("card-1")).thenReturn(card);

        Map<String, Object> result = flashcardService.review("card-1", 5);

        assertEquals(1, result.get("repetitions"));
        assertEquals(1, result.get("interval"));
        assertEquals(2.6, (Double) result.get("easeFactor"), 0.0001);
        verify(flashCardMapper).updateReview(card);
    }

    @Test
    void reviewSecondSuccessfulReviewSchedulesSixDays() {
        FlashCard card = card(2.5, 1, 1);
        when(flashCardMapper.findById("card-1")).thenReturn(card);

        Map<String, Object> result = flashcardService.review("card-1", 5);

        assertEquals(2, result.get("repetitions"));
        assertEquals(6, result.get("interval"));
    }

    @Test
    void reviewLaterSuccessfulReviewMultipliesByEaseFactor() {
        FlashCard card = card(2.5, 10, 2);
        when(flashCardMapper.findById("card-1")).thenReturn(card);

        Map<String, Object> result = flashcardService.review("card-1", 5);

        // ease 先调整为 2.6，再按 round(10 * 2.6) 计算
        assertEquals(3, result.get("repetitions"));
        assertEquals(26, result.get("interval"));
    }

    @Test
    void reviewFailedAttemptResetsProgressAndKeepsOneDayInterval() {
        FlashCard card = card(2.5, 10, 5);
        when(flashCardMapper.findById("card-1")).thenReturn(card);

        Map<String, Object> result = flashcardService.review("card-1", 2);

        assertEquals(0, result.get("repetitions"));
        assertEquals(1, result.get("interval"));
        assertEquals(2.18, (Double) result.get("easeFactor"), 0.0001);
    }

    @Test
    void reviewEaseFactorNeverDropsBelowMinimum() {
        FlashCard card = card(1.3, 5, 3);
        when(flashCardMapper.findById("card-1")).thenReturn(card);

        Map<String, Object> result = flashcardService.review("card-1", 0);

        assertEquals(1.3, (Double) result.get("easeFactor"), 0.0001);
    }

    @Test
    void generateForDocumentCreatesOneCardPerKnowledgePoint() {
        KnowledgePoint kp1 = new KnowledgePoint();
        kp1.setId("kp-1");
        kp1.setTitle("标题一");
        kp1.setContent("内容一");
        KnowledgePoint kp2 = new KnowledgePoint();
        kp2.setId("kp-2");
        kp2.setTitle("标题二");
        kp2.setContent("内容二");
        when(knowledgeMapper.findByDocumentId("doc-1")).thenReturn(List.of(kp1, kp2));

        int created = flashcardService.generateForDocument("doc-1");

        assertEquals(2, created);
        ArgumentCaptor<FlashCard> captor = ArgumentCaptor.forClass(FlashCard.class);
        verify(flashCardMapper, times(2)).insert(captor.capture());
        assertEquals("标题一", captor.getAllValues().get(0).getFront());
        assertEquals("内容二", captor.getAllValues().get(1).getBack());
    }

    @Test
    void getTodayCardsReturnsDueStatistics() {
        when(flashCardMapper.findDueCards("user-1", 20)).thenReturn(List.of(card(2.5, 1, 1)));
        when(flashCardMapper.countByUserId("user-1")).thenReturn(8);
        when(flashCardMapper.countDue("user-1")).thenReturn(3);

        Map<String, Object> result = flashcardService.getTodayCards("user-1");

        assertEquals(8, ((Map<?, ?>) result.get("stats")).get("total"));
        assertEquals(3, ((Map<?, ?>) result.get("stats")).get("due"));
        assertEquals(1, ((List<?>) result.get("cards")).size());
    }
}
