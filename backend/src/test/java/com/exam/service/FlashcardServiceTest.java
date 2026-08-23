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

    private FlashcardService service;

    private FlashCard makeCard(double ease, int interval, int reps) {
        FlashCard c = new FlashCard();
        c.setId("c1");
        c.setEaseFactor(ease);
        c.setInterval(interval);
        c.setRepetitions(reps);
        c.setNextReview(LocalDateTime.now());
        return c;
    }

    @BeforeEach
    void setUp() {
        service = new FlashcardService(mapper, knowledgeMapper);
    }

    // ========== SM-2 间隔重复算法 ==========

    @Test
    void reviewFirstRepGoodQuality_Interval1() {
        // reps=0 → quality>=3 → reps=1, interval=1
        FlashCard c = makeCard(2.5, 0, 0);
        when(mapper.findById("c1")).thenReturn(c);
        doNothing().when(mapper).updateReview(any(FlashCard.class));

        var result = service.review("c1", 4);
        assertEquals(1, result.get("interval"));
        assertEquals(1, result.get("repetitions"));
    }

    @Test
    void reviewSecondRepGoodQuality_Interval6() {
        // reps=1 → reps=2, interval=6
        FlashCard c = makeCard(2.5, 1, 1);
        when(mapper.findById("c1")).thenReturn(c);
        doNothing().when(mapper).updateReview(any(FlashCard.class));

        var result = service.review("c1", 4);
        assertEquals(6, result.get("interval"));
        assertEquals(2, result.get("repetitions"));
    }

    @Test
    void reviewThirdRepGoodQuality_IntervalScaledByEase() {
        // reps=2 → reps=3, interval = round(6 * 2.5) = 15
        FlashCard c = makeCard(2.5, 6, 2);
        when(mapper.findById("c1")).thenReturn(c);
        doNothing().when(mapper).updateReview(any(FlashCard.class));

        var result = service.review("c1", 4);
        assertEquals(15, result.get("interval")); // 6 * 2.5 = 15
        assertEquals(3, result.get("repetitions"));
    }

    @Test
    void reviewQuality5_EaseFactorIncreased() {
        // quality=5 → ease += 0.1 - 0*(0.08+0*0.02) = 0.1 → 2.5 + 0.1 = 2.6
        FlashCard c = makeCard(2.5, 6, 2);
        when(mapper.findById("c1")).thenReturn(c);
        doNothing().when(mapper).updateReview(any(FlashCard.class));

        var result = service.review("c1", 5);
        assertEquals(2.6, (Double) result.get("easeFactor"), 0.0001);
    }

    @Test
    void reviewQuality3_EaseFactorDecreased() {
        // quality=3 → ease += 0.1 - 2*(0.08+2*0.02) = 0.1 - 2*0.12 = 0.1 - 0.24 = -0.14
        FlashCard c = makeCard(2.5, 6, 2);
        when(mapper.findById("c1")).thenReturn(c);
        doNothing().when(mapper).updateReview(any(FlashCard.class));

        var result = service.review("c1", 3);
        // 2.5 + (0.1 - 2*(0.08 + 0.04)) = 2.5 + (0.1 - 0.24) = 2.5 - 0.14 = 2.36
        assertEquals(2.36, (Double) result.get("easeFactor"), 0.0001);
    }

    @Test
    void reviewQualityBelow3_ResetRepsAndInterval1() {
        // quality = 2 < 3 → reps=0, interval=1
        FlashCard c = makeCard(2.5, 10, 5);
        when(mapper.findById("c1")).thenReturn(c);
        doNothing().when(mapper).updateReview(any(FlashCard.class));

        var result = service.review("c1", 2);
        assertEquals(1, result.get("interval"));
        assertEquals(0, result.get("repetitions"));
    }

    @Test
    void reviewQuality0_ResetReps() {
        FlashCard c = makeCard(2.5, 30, 10);
        when(mapper.findById("c1")).thenReturn(c);
        doNothing().when(mapper).updateReview(any(FlashCard.class));

        var result = service.review("c1", 0);
        assertEquals(1, result.get("interval"));
        assertEquals(0, result.get("repetitions"));
    }

    @Test
    void reviewEaseFactorFlooredAtMinimum() {
        // quality极低导致 ease 跌破 1.3 → 强制 = 1.3
        // quality=0 → 0.1 - 5*(0.08+5*0.02) = 0.1 - 5*0.18 = 0.1-0.9 = -0.8 → 1.5 - 0.8 = 0.7 < 1.3
        FlashCard c = makeCard(1.5, 5, 3);
        when(mapper.findById("c1")).thenReturn(c);
        doNothing().when(mapper).updateReview(any(FlashCard.class));

        var result = service.review("c1", 0);
        assertEquals(1.3, (Double) result.get("easeFactor"), 0.0001);
    }

    @Test
    void reviewMissingCardThrows() {
        when(mapper.findById("nonexistent")).thenReturn(null);
        assertThrows(RuntimeException.class,
            () -> service.review("nonexistent", 4));
    }

    @Test
    void reviewPersistsUpdatedCard() {
        FlashCard c = makeCard(2.5, 1, 1);
        when(mapper.findById("c1")).thenReturn(c);
        doNothing().when(mapper).updateReview(any(FlashCard.class));

        service.review("c1", 5);

        verify(mapper).updateReview(c);
        // 验证 nextReview 被设置
        assertNotNull(c.getNextReview());
    }

    // ========== getTodayCards ==========

    @Test
    void getTodayCardsAggregatesStats() {
        FlashCard c1 = new FlashCard(); c1.setId("c1");
        FlashCard c2 = new FlashCard(); c2.setId("c2");
        when(mapper.findDueCards("u1", 20)).thenReturn(List.of(c1, c2));
        when(mapper.countByUserId("u1")).thenReturn(100);
        when(mapper.countDue("u1")).thenReturn(15);

        var result = service.getTodayCards("u1");

        @SuppressWarnings("unchecked")
        var cards = (List<FlashCard>) result.get("cards");
        @SuppressWarnings("unchecked")
        var stats = (Map<String, Object>) result.get("stats");

        assertEquals(2, cards.size());
        assertEquals(100, stats.get("total"));
        assertEquals(15, stats.get("due"));
    }

    // ========== generateForDocument ==========

    @Test
    void generateForDocumentCreatesCardsFromKnowledgePoints() {
        KnowledgePoint p1 = new KnowledgePoint();
        p1.setId("kp1"); p1.setTitle("什么是OOP？"); p1.setContent("面向对象编程");
        KnowledgePoint p2 = new KnowledgePoint();
        p2.setId("kp2"); p2.setTitle("什么是多态？"); p2.setContent("同一接口不同实现");

        when(knowledgeMapper.findByDocumentId("d1")).thenReturn(List.of(p1, p2));
        doNothing().when(mapper).insert(any(FlashCard.class));

        int count = service.generateForDocument("d1");

        assertEquals(2, count);
        verify(mapper, times(2)).insert(any(FlashCard.class));
    }

    @Test
    void generateForDocumentNoPointsReturnsZero() {
        when(knowledgeMapper.findByDocumentId("d1")).thenReturn(List.of());

        assertEquals(0, service.generateForDocument("d1"));
        verify(mapper, never()).insert(any());
    }

    // ========== delete ==========

    @Test
    void deleteDelegatesToMapper() {
        doNothing().when(mapper).deleteById("c1");
        service.delete("c1");
        verify(mapper).deleteById("c1");
    }
}
