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

    @Mock
    private FlashCardMapper flashCardMapper;

    @Mock
    private KnowledgeMapper knowledgeMapper;

    private FlashcardService service;

    @BeforeEach
    void setUp() {
        service = new FlashcardService(flashCardMapper, knowledgeMapper);
    }

    private FlashCard makeCard(double ease, int interval, int reps) {
        FlashCard c = new FlashCard();
        c.setId("c1");
        c.setEaseFactor(ease);
        c.setInterval(interval);
        c.setRepetitions(reps);
        c.setNextReview(LocalDateTime.now().minusDays(1));
        return c;
    }

    // ==================== getTodayCards 统计 ====================

    @Test
    void getTodayCardsReturnsCardsAndStats() {
        FlashCard c = makeCard(2.5, 3, 2);
        when(flashCardMapper.findDueCards("u1", 20)).thenReturn(List.of(c));
        when(flashCardMapper.countByUserId("u1")).thenReturn(10);
        when(flashCardMapper.countDue("u1")).thenReturn(3);

        Map<String, Object> result = service.getTodayCards("u1");

        @SuppressWarnings("unchecked")
        List<FlashCard> cards = (List<FlashCard>) result.get("cards");
        assertEquals(1, cards.size());
        assertEquals("c1", cards.get(0).getId());

        @SuppressWarnings("unchecked")
        Map<String, Object> stats = (Map<String, Object>) result.get("stats");
        assertEquals(10, stats.get("total"));
        assertEquals(3, stats.get("due"));
    }

    // ==================== review 质量 < 3：重置 ====================

    @Test
    void reviewQuality0ResetsRepsAndInterval() {
        FlashCard card = makeCard(2.5, 10, 5);
        when(flashCardMapper.findById("c1")).thenReturn(card);
        doNothing().when(flashCardMapper).updateReview(any());

        Map<String, Object> r = service.review("c1", 0);

        assertEquals(0, r.get("repetitions"));
        assertEquals(1, r.get("interval"));
        // ease 下降：2.5 + (0.1 - (5-0)*(0.08 + 5*0.02)) = 2.5 + (0.1 - 5*0.18) = 2.5 + (0.1-0.9) = 1.7
        assertEquals(1.7, (Double) r.get("easeFactor"), 0.001);
        verify(flashCardMapper).updateReview(argThat(c ->
            c.getRepetitions() == 0 && c.getInterval() == 1
        ));
    }

    @Test
    void reviewQuality2ResetsRepsAndInterval() {
        FlashCard card = makeCard(2.5, 10, 5);
        when(flashCardMapper.findById("c1")).thenReturn(card);
        doNothing().when(flashCardMapper).updateReview(any());

        Map<String, Object> r = service.review("c1", 2);

        assertEquals(0, r.get("repetitions"));
        assertEquals(1, r.get("interval"));
        // 2.5 + (0.1 - 3*(0.08+0.06)) = 2.5 + (0.1 - 0.42) = 2.18
        assertEquals(2.18, (Double) r.get("easeFactor"), 0.001);
    }

    @Test
    void reviewLowQualityEaseDropsBelowMinClamps() {
        FlashCard card = makeCard(1.4, 5, 3);
        when(flashCardMapper.findById("c1")).thenReturn(card);
        doNothing().when(flashCardMapper).updateReview(any());

        // quality=0 → ease=1.4 + (0.1 - 5*0.18) = 1.4 - 0.8 = 0.6 → 钳位到 1.3
        Map<String, Object> r = service.review("c1", 0);

        assertEquals(1.3, (Double) r.get("easeFactor"), 0.001);
        assertEquals(0, r.get("repetitions"));
    }

    // ==================== review 质量 >= 3：递进 ====================

    @Test
    void reviewQuality3Reps0BecomesReps1Interval1() {
        FlashCard card = makeCard(2.5, 0, 0);
        when(flashCardMapper.findById("c1")).thenReturn(card);
        doNothing().when(flashCardMapper).updateReview(any());

        Map<String, Object> r = service.review("c1", 3);

        assertEquals(1, r.get("repetitions"));
        assertEquals(1, r.get("interval")); // reps==1 → 1天
        // ease: 2.5 + (0.1 - 2*(0.08+0.04)) = 2.5 + (0.1-0.24) = 2.36
        assertEquals(2.36, (Double) r.get("easeFactor"), 0.001);
    }

    @Test
    void reviewQuality4Reps1BecomesReps2Interval6() {
        FlashCard card = makeCard(2.5, 1, 1);
        when(flashCardMapper.findById("c1")).thenReturn(card);
        doNothing().when(flashCardMapper).updateReview(any());

        Map<String, Object> r = service.review("c1", 4);

        assertEquals(2, r.get("repetitions"));
        assertEquals(6, r.get("interval")); // reps==2 → 6天
        // ease: 2.5 + (0.1 - 1*(0.08+0.02)) = 2.5 + 0 = 2.5
        assertEquals(2.5, (Double) r.get("easeFactor"), 0.001);
    }

    @Test
    void reviewQuality5Reps2IntervalMultipliesByEase() {
        // reps=2, interval=6 → next reps=3 (>2) → interval = round(6 * ease)
        FlashCard card = makeCard(2.5, 6, 2);
        when(flashCardMapper.findById("c1")).thenReturn(card);
        doNothing().when(flashCardMapper).updateReview(any());

        Map<String, Object> r = service.review("c1", 5);

        assertEquals(3, r.get("repetitions"));
        // ease = 2.5 + (0.1 - 0) = 2.6; interval = round(6 * 2.6) = round(15.6) = 16
        assertEquals(2.6, (Double) r.get("easeFactor"), 0.001);
        assertEquals(16, r.get("interval"));
    }

    @Test
    void reviewQuality5Reps5MultipliesExistingInterval() {
        FlashCard card = makeCard(2.2, 20, 5);
        when(flashCardMapper.findById("c1")).thenReturn(card);
        doNothing().when(flashCardMapper).updateReview(any());

        Map<String, Object> r = service.review("c1", 5);

        assertEquals(6, r.get("repetitions"));
        // ease = 2.2 + 0.1 = 2.3; interval = round(20 * 2.3) = 46
        assertEquals(2.3, (Double) r.get("easeFactor"), 0.001);
        assertEquals(46, r.get("interval"));
    }

    @Test
    void reviewUpdatesNextReviewDateBasedOnInterval() {
        FlashCard card = makeCard(2.5, 1, 1);
        when(flashCardMapper.findById("c1")).thenReturn(card);
        doNothing().when(flashCardMapper).updateReview(any());

        LocalDateTime before = LocalDateTime.now().plusDays(6).minusSeconds(1);
        service.review("c1", 5);
        LocalDateTime after = LocalDateTime.now().plusDays(6).plusSeconds(1);

        verify(flashCardMapper).updateReview(argThat(c -> {
            LocalDateTime nr = c.getNextReview();
            // interval = 6天
            return nr != null && nr.isAfter(before) && nr.isBefore(after);
        }));
    }

    @Test
    void reviewNonExistentCardThrows() {
        when(flashCardMapper.findById("ghost")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> service.review("ghost", 3));
        assertEquals("闪卡不存在", ex.getMessage());
    }

    // ==================== generateForDocument ====================

    @Test
    void generateForDocumentCreatesCardsForEachPoint() {
        KnowledgePoint p1 = new KnowledgePoint();
        p1.setId("kp1");
        p1.setTitle("什么是OOP？");
        p1.setContent("面向对象编程...");
        KnowledgePoint p2 = new KnowledgePoint();
        p2.setId("kp2");
        p2.setTitle("继承的作用");
        p2.setContent("继承是...");
        when(knowledgeMapper.findByDocumentId("d1")).thenReturn(List.of(p1, p2));
        doNothing().when(flashCardMapper).insert(any());

        int count = service.generateForDocument("d1");

        assertEquals(2, count);
        verify(flashCardMapper, times(2)).insert(argThat(c ->
            c.getFront() != null && c.getBack() != null && c.getKnowledgePointId() != null
        ));
    }

    @Test
    void generateForDocumentNoPointsReturnsZero() {
        when(knowledgeMapper.findByDocumentId("d-empty")).thenReturn(List.of());

        int count = service.generateForDocument("d-empty");

        assertEquals(0, count);
        verify(flashCardMapper, never()).insert(any());
    }

    // ==================== delete ====================

    @Test
    void deleteDelegatesToMapper() {
        doNothing().when(flashCardMapper).deleteById("c-del");

        assertDoesNotThrow(() -> service.delete("c-del"));

        verify(flashCardMapper).deleteById("c-del");
    }
}
