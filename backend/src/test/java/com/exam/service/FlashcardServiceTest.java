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

@ExtendWith(MockitoExtension.class)
class FlashcardServiceTest {

    @Mock private FlashCardMapper cardMapper;
    @Mock private KnowledgeMapper knowledgeMapper;

    private FlashcardService service;

    @BeforeEach
    void setUp() {
        service = new FlashcardService(cardMapper, knowledgeMapper);
    }

    private static FlashCard card(double ease, int interval, int repetitions) {
        FlashCard card = new FlashCard();
        card.setId("c1");
        card.setEaseFactor(ease);
        card.setInterval(interval);
        card.setRepetitions(repetitions);
        return card;
    }

    @Test
    void failingReviewResetsProgressAndLowersEase() {
        when(cardMapper.findById("c1")).thenReturn(card(2.5, 10, 3));

        Map<String, Object> result = service.review("c1", 2);

        assertEquals(1, result.get("interval"));
        assertEquals(0, result.get("repetitions"));
        assertEquals(2.18, (double) result.get("easeFactor"), 1e-9);
        verify(cardMapper).updateReview(any(FlashCard.class));
    }

    @Test
    void firstSuccessfulReviewSchedulesNextDay() {
        when(cardMapper.findById("c1")).thenReturn(card(2.5, 0, 0));

        Map<String, Object> result = service.review("c1", 5);

        assertEquals(1, result.get("interval"));
        assertEquals(1, result.get("repetitions"));
        assertEquals(2.6, (double) result.get("easeFactor"), 1e-9);
    }

    @Test
    void secondSuccessfulReviewSchedulesSixDays() {
        when(cardMapper.findById("c1")).thenReturn(card(2.5, 1, 1));

        Map<String, Object> result = service.review("c1", 5);

        assertEquals(6, result.get("interval"));
        assertEquals(2, result.get("repetitions"));
    }

    @Test
    void laterReviewMultipliesPreviousIntervalByEase() {
        when(cardMapper.findById("c1")).thenReturn(card(2.5, 6, 2));

        Map<String, Object> result = service.review("c1", 4);

        // quality=4 时 ease 不变：round(6 * 2.5) = 15
        assertEquals(15, result.get("interval"));
        assertEquals(3, result.get("repetitions"));
        assertEquals(2.5, (double) result.get("easeFactor"), 1e-9);
    }

    @Test
    void easeFactorIsClampedAtFloorOfOnePointThree() {
        when(cardMapper.findById("c1")).thenReturn(card(1.0, 1, 1));

        Map<String, Object> result = service.review("c1", 0);

        assertEquals(1.3, (double) result.get("easeFactor"), 1e-9);
        assertEquals(1, result.get("interval"));
        assertEquals(0, result.get("repetitions"));
    }

    @Test
    void reviewOfMissingCardThrows() {
        when(cardMapper.findById("missing")).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.review("missing", 5));
        assertEquals("闪卡不存在", ex.getMessage());
        verify(cardMapper, never()).updateReview(any());
    }

    @Test
    void generateForDocumentCreatesOneCardPerKnowledgePoint() {
        KnowledgePoint first = new KnowledgePoint();
        first.setId("k1");
        first.setTitle("标题1");
        first.setContent("内容1");
        KnowledgePoint second = new KnowledgePoint();
        second.setId("k2");
        second.setTitle("标题2");
        second.setContent("内容2");
        when(knowledgeMapper.findByDocumentId("doc1")).thenReturn(List.of(first, second));

        int created = service.generateForDocument("doc1");

        assertEquals(2, created);
        ArgumentCaptor<FlashCard> captor = ArgumentCaptor.forClass(FlashCard.class);
        verify(cardMapper, times(2)).insert(captor.capture());
        assertEquals("标题2", captor.getAllValues().get(1).getFront());
        assertEquals("内容2", captor.getAllValues().get(1).getBack());
    }
}
