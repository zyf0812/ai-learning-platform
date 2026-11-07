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
    private FlashCardMapper cardMapper;
    @Mock
    private KnowledgeMapper knowledgeMapper;

    private FlashcardService service;

    @BeforeEach
    void setUp() {
        service = new FlashcardService(cardMapper, knowledgeMapper);
    }

    @Test
    void reviewFailedRecallResetsRepetitionsAndSchedulesNextDay() {
        FlashCard card = card(2.5, 5, 30);
        when(cardMapper.findById("card1")).thenReturn(card);

        Map<String, Object> result = service.review("card1", 2);

        assertEquals(1, result.get("interval"));
        assertEquals(0, result.get("repetitions"));
        assertEquals(2.18, (Double) result.get("easeFactor"), 0.0001);
        ArgumentCaptor<FlashCard> captor = ArgumentCaptor.forClass(FlashCard.class);
        verify(cardMapper).updateReview(captor.capture());
        assertEquals(1, captor.getValue().getInterval());
        assertTrue(captor.getValue().getNextReview().isAfter(LocalDateTime.now().plusHours(23)));
    }

    @Test
    void reviewFirstSuccessSchedulesOneDay() {
        FlashCard card = card(2.5, 0, 0);
        when(cardMapper.findById("card1")).thenReturn(card);

        Map<String, Object> result = service.review("card1", 5);

        assertEquals(1, result.get("interval"));
        assertEquals(1, result.get("repetitions"));
        assertEquals(2.6, (Double) result.get("easeFactor"), 0.0001);
    }

    @Test
    void reviewSecondSuccessSchedulesSixDays() {
        FlashCard card = card(2.5, 1, 1);
        when(cardMapper.findById("card1")).thenReturn(card);

        Map<String, Object> result = service.review("card1", 5);

        assertEquals(6, result.get("interval"));
        assertEquals(2, result.get("repetitions"));
    }

    @Test
    void reviewLaterSuccessMultipliesIntervalByEaseFactor() {
        FlashCard card = card(2.5, 2, 6);
        when(cardMapper.findById("card1")).thenReturn(card);

        Map<String, Object> result = service.review("card1", 5);

        assertEquals(16, result.get("interval"));
        assertEquals(3, result.get("repetitions"));
    }

    @Test
    void reviewKeepsEaseFactorAboveMinimumFloor() {
        FlashCard card = card(1.3, 4, 20);
        when(cardMapper.findById("card1")).thenReturn(card);

        Map<String, Object> result = service.review("card1", 0);

        assertEquals(1.3, (Double) result.get("easeFactor"), 0.0001);
    }

    @Test
    void reviewMissingCardThrows() {
        when(cardMapper.findById("missing")).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.review("missing", 5));

        assertEquals("闪卡不存在", ex.getMessage());
        verify(cardMapper, never()).updateReview(any());
    }

    @Test
    void generateForDocumentCreatesCardPerKnowledgePoint() {
        KnowledgePoint first = knowledgePoint("kp1", "标题1", "内容1");
        KnowledgePoint second = knowledgePoint("kp2", "标题2", "内容2");
        when(knowledgeMapper.findByDocumentId("doc1")).thenReturn(List.of(first, second));

        int created = service.generateForDocument("doc1");

        assertEquals(2, created);
        ArgumentCaptor<FlashCard> captor = ArgumentCaptor.forClass(FlashCard.class);
        verify(cardMapper, times(2)).insert(captor.capture());
        assertEquals("kp1", captor.getAllValues().get(0).getKnowledgePointId());
        assertEquals("标题1", captor.getAllValues().get(0).getFront());
        assertEquals("内容1", captor.getAllValues().get(0).getBack());
        assertEquals("kp2", captor.getAllValues().get(1).getKnowledgePointId());
    }

    private static FlashCard card(double ease, int repetitions, int interval) {
        FlashCard card = new FlashCard();
        card.setId("card1");
        card.setEaseFactor(ease);
        card.setRepetitions(repetitions);
        card.setInterval(interval);
        return card;
    }

    private static KnowledgePoint knowledgePoint(String id, String title, String content) {
        KnowledgePoint point = new KnowledgePoint();
        point.setId(id);
        point.setTitle(title);
        point.setContent(content);
        return point;
    }
}
