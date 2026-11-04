package com.exam.service;

import com.exam.mapper.FlashCardMapper;
import com.exam.mapper.KnowledgeMapper;
import com.exam.model.FlashCard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FlashcardServiceTest {

    @Mock private FlashCardMapper mapper;
    @Mock private KnowledgeMapper knowledgeMapper;

    private FlashcardService service;

    @BeforeEach
    void setUp() {
        service = new FlashcardService(mapper, knowledgeMapper);
    }

    private FlashCard card(int repetitions, int interval, double ease) {
        FlashCard c = new FlashCard();
        c.setId("card1");
        c.setEaseFactor(ease);
        c.setInterval(interval);
        c.setRepetitions(repetitions);
        return c;
    }

    @Test
    void failedReviewResetsRepetitionsAndIntervalAndClampsEase() {
        FlashCard card = card(4, 30, 1.4);
        when(mapper.findById("card1")).thenReturn(card);

        Map<String, Object> result = service.review("card1", 0);

        assertEquals(0, result.get("repetitions"));
        assertEquals(1, result.get("interval"));
        assertEquals(1.3, result.get("easeFactor"));
        verify(mapper).updateReview(card);
        assertNotNull(card.getNextReview());
        assertTrue(card.getNextReview().isAfter(LocalDateTime.now()));
        assertTrue(card.getNextReview().isBefore(LocalDateTime.now().plusDays(2)));
    }

    @Test
    void firstSuccessfulReviewSchedulesOneDay() {
        FlashCard card = card(0, 1, 2.5);
        when(mapper.findById("card1")).thenReturn(card);

        Map<String, Object> result = service.review("card1", 4);

        assertEquals(1, result.get("repetitions"));
        assertEquals(1, result.get("interval"));
        assertEquals(2.5, result.get("easeFactor"));
    }

    @Test
    void secondSuccessfulReviewSchedulesSixDays() {
        FlashCard card = card(1, 1, 2.5);
        when(mapper.findById("card1")).thenReturn(card);

        Map<String, Object> result = service.review("card1", 4);

        assertEquals(2, result.get("repetitions"));
        assertEquals(6, result.get("interval"));
    }

    @Test
    void laterReviewMultipliesIntervalByEaseFactor() {
        FlashCard card = card(2, 10, 2.5);
        when(mapper.findById("card1")).thenReturn(card);

        Map<String, Object> result = service.review("card1", 4);

        assertEquals(3, result.get("repetitions"));
        assertEquals(25, result.get("interval"));
        assertNotNull(card.getNextReview());
    }

    @Test
    void reviewMissingCardThrows() {
        when(mapper.findById("missing")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class, () -> service.review("missing", 4));

        assertEquals("闪卡不存在", ex.getMessage());
        verify(mapper, never()).updateReview(any());
    }

    @Test
    void getTodayCardsCombinesDueCardsAndStats() {
        FlashCard due = card(0, 1, 2.5);
        when(mapper.findDueCards("u1", 20)).thenReturn(java.util.List.of(due));
        when(mapper.countByUserId("u1")).thenReturn(10);
        when(mapper.countDue("u1")).thenReturn(3);

        Map<String, Object> result = service.getTodayCards("u1");

        assertEquals(java.util.List.of(due), result.get("cards"));
        @SuppressWarnings("unchecked")
        Map<String, Object> stats = (Map<String, Object>) result.get("stats");
        assertEquals(10, stats.get("total"));
        assertEquals(3, stats.get("due"));
    }
}
