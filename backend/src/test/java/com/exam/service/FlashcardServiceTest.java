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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

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

    // ---------- getTodayCards ----------

    @Test
    void getTodayCardsReturnsCardsAndStats() {
        FlashCard c1 = new FlashCard();
        c1.setId("c1");
        FlashCard c2 = new FlashCard();
        c2.setId("c2");

        when(mapper.findDueCards("u1", 20)).thenReturn(List.of(c1, c2));
        when(mapper.countByUserId("u1")).thenReturn(50);
        when(mapper.countDue("u1")).thenReturn(12);

        Map<String, Object> result = service.getTodayCards("u1");

        @SuppressWarnings("unchecked")
        List<FlashCard> cards = (List<FlashCard>) result.get("cards");
        assertEquals(2, cards.size());

        @SuppressWarnings("unchecked")
        Map<String, Integer> stats = (Map<String, Integer>) result.get("stats");
        assertEquals(50, stats.get("total"));
        assertEquals(12, stats.get("due"));
    }

    // ---------- review: SM-2 算法核心 ----------

    @Test
    void reviewWithQuality0ResetsRepsAndInterval() {
        FlashCard card = new FlashCard();
        card.setId("c1");
        card.setEaseFactor(2.5);
        card.setInterval(30);
        card.setRepetitions(5);

        when(mapper.findById("c1")).thenReturn(card);
        doNothing().when(mapper).updateReview(any(FlashCard.class));

        Map<String, Object> result = service.review("c1", 0);

        assertEquals(0, result.get("repetitions"));
        assertEquals(1, result.get("interval"));
        // ease = 2.5 + 0.1 - 5*(0.08 + 5*0.02) = 2.5 + 0.1 - 5*(0.18) = 2.6 - 0.9 = 1.7 -> 但1.7 >= 1.3，保留
        assertEquals(1.7, (Double) result.get("easeFactor"), 0.001);
        verify(mapper).updateReview(card);
    }

    @Test
    void reviewWithQuality2StillResetsRepsAndInterval() {
        // quality < 3 仍然重置
        FlashCard card = new FlashCard();
        card.setId("c1");
        card.setEaseFactor(2.5);
        card.setInterval(15);
        card.setRepetitions(3);

        when(mapper.findById("c1")).thenReturn(card);
        doNothing().when(mapper).updateReview(any(FlashCard.class));

        Map<String, Object> result = service.review("c1", 2);

        assertEquals(0, result.get("repetitions"));
        assertEquals(1, result.get("interval"));
        // ease = 2.5 + 0.1 - 3*(0.08 + 3*0.02) = 2.6 - 3*(0.14) = 2.6 - 0.42 = 2.18
        assertEquals(2.18, (Double) result.get("easeFactor"), 0.001);
    }

    @Test
    void reviewFirstPassQuality3SetsIntervalTo1() {
        // reps==1 -> interval=1
        FlashCard card = new FlashCard();
        card.setId("c1");
        card.setEaseFactor(2.5);
        card.setInterval(0);
        card.setRepetitions(0);

        when(mapper.findById("c1")).thenReturn(card);
        doNothing().when(mapper).updateReview(any(FlashCard.class));

        Map<String, Object> result = service.review("c1", 3);

        assertEquals(1, result.get("repetitions"));
        assertEquals(1, result.get("interval"));
    }

    @Test
    void reviewSecondPassQuality4SetsIntervalTo6() {
        // reps从1变到2 -> interval=6
        FlashCard card = new FlashCard();
        card.setId("c1");
        card.setEaseFactor(2.5);
        card.setInterval(1);
        card.setRepetitions(1);

        when(mapper.findById("c1")).thenReturn(card);
        doNothing().when(mapper).updateReview(any(FlashCard.class));

        Map<String, Object> result = service.review("c1", 4);

        assertEquals(2, result.get("repetitions"));
        assertEquals(6, result.get("interval"));
    }

    @Test
    void reviewThirdPassQuality5MultipliesIntervalByEase() {
        // reps从2变到3+ -> interval = prev_interval * ease 四舍五入
        FlashCard card = new FlashCard();
        card.setId("c1");
        card.setEaseFactor(2.5);
        card.setInterval(6);
        card.setRepetitions(2);

        when(mapper.findById("c1")).thenReturn(card);
        doNothing().when(mapper).updateReview(any(FlashCard.class));

        Map<String, Object> result = service.review("c1", 5);

        assertEquals(3, result.get("repetitions"));
        // 6 * 2.5 = 15
        assertEquals(15, result.get("interval"));
        // ease = 2.5 + 0.1 - 0 = 2.6 (quality=5, diff=0)
        assertEquals(2.6, (Double) result.get("easeFactor"), 0.001);
    }

    @Test
    void reviewEaseFactorHasHardFloor13() {
        // 多次低质量回答，ease不能低于1.3
        FlashCard card = new FlashCard();
        card.setId("c1");
        card.setEaseFactor(1.35);
        card.setInterval(10);
        card.setRepetitions(5);

        when(mapper.findById("c1")).thenReturn(card);
        doNothing().when(mapper).updateReview(any(FlashCard.class));

        // quality=0会把ease降到1.35+0.1-5*(0.08+0.1)=1.45-0.9=0.55，但地板是1.3
        Map<String, Object> result = service.review("c1", 0);

        assertEquals(1.3, (Double) result.get("easeFactor"), 0.001);
    }

    @Test
    void reviewIntervalRoundsCorrectly() {
        // interval = 10 * 1.34 = 13.4 -> round 13
        FlashCard card = new FlashCard();
        card.setId("c1");
        card.setEaseFactor(1.34);
        card.setInterval(10);
        card.setRepetitions(3);

        when(mapper.findById("c1")).thenReturn(card);
        doNothing().when(mapper).updateReview(any(FlashCard.class));

        Map<String, Object> result = service.review("c1", 4);
        assertEquals(13, result.get("interval")); // 10 * 1.34 = 13.4 -> round 13
    }

    @Test
    void reviewQuality5MaximizesEaseFactorBoost() {
        // quality=5 -> 扣减项 = (5-5)*(...) = 0, ease直接+0.1
        FlashCard card = new FlashCard();
        card.setId("c1");
        card.setEaseFactor(2.0);
        card.setInterval(10);
        card.setRepetitions(5);

        when(mapper.findById("c1")).thenReturn(card);
        doNothing().when(mapper).updateReview(any(FlashCard.class));

        Map<String, Object> result = service.review("c1", 5);
        assertEquals(2.1, (Double) result.get("easeFactor"), 0.001);
    }

    @Test
    void reviewMissingCardThrowsNotFound() {
        when(mapper.findById("missing")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> service.review("missing", 3));
        assertEquals("闪卡不存在", ex.getMessage());
    }

    // ---------- generateForDocument ----------

    @Test
    void generateForDocumentCreatesCardsForAllPoints() {
        KnowledgePoint p1 = new KnowledgePoint();
        p1.setId("kp1");
        p1.setTitle("什么是OOP");
        p1.setContent("面向对象编程");

        KnowledgePoint p2 = new KnowledgePoint();
        p2.setId("kp2");
        p2.setTitle("Java基本类型");
        p2.setContent("8种基本数据类型");

        when(knowledgeMapper.findByDocumentId("doc1")).thenReturn(List.of(p1, p2));
        doNothing().when(mapper).insert(any(FlashCard.class));

        int count = service.generateForDocument("doc1");

        assertEquals(2, count);
        verify(mapper, times(2)).insert(argThat(card ->
            card.getId() != null && card.getFront() != null && card.getBack() != null
        ));
    }

    @Test
    void generateForDocumentNoKnowledgePointsReturnsZero() {
        when(knowledgeMapper.findByDocumentId("empty")).thenReturn(List.of());

        assertEquals(0, service.generateForDocument("empty"));
        verify(mapper, never()).insert(any());
    }

    // ---------- delete ----------

    @Test
    void deleteDelegatesToMapper() {
        doNothing().when(mapper).deleteById("c1");
        service.delete("c1");
        verify(mapper).deleteById("c1");
    }
}
