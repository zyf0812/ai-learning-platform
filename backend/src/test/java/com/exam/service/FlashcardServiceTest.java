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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 间隔重复算法直接影响复习计划。原实现没有测试，边界（失败重置、ease 下限、
 * 第 1/2/后续次成功复习）都容易在重构中被破坏。
 */
@ExtendWith(MockitoExtension.class)
class FlashcardServiceTest {

    @Mock
    private FlashCardMapper mapper;

    @Mock
    private KnowledgeMapper knowledgeMapper;

    private FlashcardService service;

    @BeforeEach
    void setUp() {
        service = new FlashcardService(mapper, knowledgeMapper);
    }

    private FlashCard card(double ease, int interval, int repetitions) {
        FlashCard card = new FlashCard();
        card.setId("card1");
        card.setEaseFactor(ease);
        card.setInterval(interval);
        card.setRepetitions(repetitions);
        return card;
    }

    @Test
    void failedReviewResetsRepetitionsAndInterval() {
        FlashCard card = card(2.5, 10, 4);
        when(mapper.findById("card1")).thenReturn(card);

        Map<String, Object> result = service.review("card1", 2);

        assertEquals(2.18, (Double) result.get("easeFactor"), 1e-9);
        assertEquals(1, result.get("interval"));
        assertEquals(0, result.get("repetitions"));
        assertNotNull(card.getNextReview());
        verify(mapper).updateReview(card);
    }

    @Test
    void easeFactorNeverDropsBelowMinimum() {
        FlashCard card = card(1.3, 0, 0);
        when(mapper.findById("card1")).thenReturn(card);

        Map<String, Object> result = service.review("card1", 0);

        assertEquals(1.3, (Double) result.get("easeFactor"), 1e-9);
        assertEquals(1, result.get("interval"));
        assertEquals(0, result.get("repetitions"));
    }

    @Test
    void firstSuccessfulReviewSchedulesOneDay() {
        FlashCard card = card(2.5, 0, 0);
        when(mapper.findById("card1")).thenReturn(card);

        Map<String, Object> result = service.review("card1", 5);

        assertEquals(2.6, (Double) result.get("easeFactor"), 1e-9);
        assertEquals(1, result.get("interval"));
        assertEquals(1, result.get("repetitions"));
    }

    @Test
    void secondSuccessfulReviewSchedulesSixDays() {
        FlashCard card = card(2.5, 1, 1);
        when(mapper.findById("card1")).thenReturn(card);

        Map<String, Object> result = service.review("card1", 4);

        assertEquals(2.5, (Double) result.get("easeFactor"), 1e-9);
        assertEquals(6, result.get("interval"));
        assertEquals(2, result.get("repetitions"));
    }

    @Test
    void laterSuccessfulReviewMultipliesIntervalByEase() {
        FlashCard card = card(2.5, 6, 2);
        when(mapper.findById("card1")).thenReturn(card);

        Map<String, Object> result = service.review("card1", 5);

        assertEquals(2.6, (Double) result.get("easeFactor"), 1e-9);
        assertEquals(16, result.get("interval")); // round(6 * 2.6)
        assertEquals(3, result.get("repetitions"));
    }

    @Test
    void reviewUnknownCardThrows() {
        when(mapper.findById("missing")).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> service.review("missing", 5));

        assertEquals("闪卡不存在", ex.getMessage());
        verify(mapper, never()).updateReview(any());
    }

    @Test
    void generateForDocumentCreatesOneCardPerKnowledgePoint() {
        KnowledgePoint p1 = new KnowledgePoint();
        p1.setId("kp1");
        p1.setTitle("标题1");
        p1.setContent("内容1");
        KnowledgePoint p2 = new KnowledgePoint();
        p2.setId("kp2");
        p2.setTitle("标题2");
        p2.setContent("内容2");

        when(knowledgeMapper.findByDocumentId("doc1")).thenReturn(List.of(p1, p2));

        int count = service.generateForDocument("doc1");

        assertEquals(2, count);
        ArgumentCaptor<FlashCard> captor = ArgumentCaptor.forClass(FlashCard.class);
        verify(mapper, times(2)).insert(captor.capture());
        assertEquals("kp1", captor.getAllValues().get(0).getKnowledgePointId());
        assertEquals("标题2", captor.getAllValues().get(1).getFront());
        assertEquals("内容2", captor.getAllValues().get(1).getBack());
    }

    @Test
    void getTodayCardsReturnsCardsAndCounts() {
        when(mapper.findDueCards("u1", 20)).thenReturn(List.of(card(2.5, 1, 1)));
        when(mapper.countByUserId("u1")).thenReturn(5);
        when(mapper.countDue("u1")).thenReturn(3);

        Map<String, Object> result = service.getTodayCards("u1");

        assertEquals(1, ((List<?>) result.get("cards")).size());
        Map<?, ?> stats = (Map<?, ?>) result.get("stats");
        assertEquals(5, stats.get("total"));
        assertEquals(3, stats.get("due"));
        verify(mapper, never()).findById(anyString());
    }
}
