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

import java.util.ArrayList;
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

    @BeforeEach
    void setUp() {
        service = new FlashcardService(mapper, knowledgeMapper);
    }

    private FlashCard makeCard(String id, double ease, int interval, int reps) {
        FlashCard c = new FlashCard();
        c.setId(id);
        c.setEaseFactor(ease);
        c.setInterval(interval);
        c.setRepetitions(reps);
        c.setNextReview(java.time.LocalDateTime.now());
        return c;
    }

    // ---------- getTodayCards ----------

    @Test
    void getTodayCardsReturnsCardsAndStats() {
        List<FlashCard> cards = List.of(makeCard("c1", 2.5, 1, 0));
        when(mapper.findDueCards("u1", 20)).thenReturn(cards);
        when(mapper.countByUserId("u1")).thenReturn(10);
        when(mapper.countDue("u1")).thenReturn(3);

        Map<String, Object> result = service.getTodayCards("u1");

        @SuppressWarnings("unchecked")
        List<FlashCard> resultCards = (List<FlashCard>) result.get("cards");
        assertEquals(1, resultCards.size());
        @SuppressWarnings("unchecked")
        Map<String, Integer> stats = (Map<String, Integer>) result.get("stats");
        assertEquals(10, stats.get("total"));
        assertEquals(3, stats.get("due"));
    }

    // ---------- review：quality < 3 重置 ----------

    @Test
    void reviewQualityZeroResetsRepsAndInterval() {
        FlashCard card = makeCard("c1", 2.5, 30, 10);
        when(mapper.findById("c1")).thenReturn(card);

        Map<String, Object> r = service.review("c1", 0);

        assertEquals(0, r.get("repetitions"));
        assertEquals(1, r.get("interval"));
        // ease 应下降：2.5 + (0.1 - 5*(0.08 + 5*0.02)) = 2.5 + (0.1 - 5*0.18) = 2.5 + (0.1 - 0.9) = 2.5 - 0.8 = 1.7
        assertEquals(1.7, (Double) r.get("easeFactor"), 0.001);
        verify(mapper).updateReview(card);
    }

    @Test
    void reviewQualityTwoResetsReps() {
        FlashCard card = makeCard("c2", 2.5, 10, 5);
        when(mapper.findById("c2")).thenReturn(card);

        Map<String, Object> r = service.review("c2", 2);

        assertEquals(0, r.get("repetitions"));
        assertEquals(1, r.get("interval"));
        // ease: 2.5 + (0.1 - 3*(0.08 + 3*0.02)) = 2.5 + (0.1 - 3*0.14) = 2.5 + (0.1 - 0.42) = 2.18
        assertEquals(2.18, (Double) r.get("easeFactor"), 0.001);
    }

    // ---------- review：quality >= 3 正常递进 ----------

    @Test
    void reviewQualityThreeFirstSuccessSetsInterval1() {
        // reps == 0 → 成功后 reps=1, interval=1
        FlashCard card = makeCard("c3", 2.5, 0, 0);
        when(mapper.findById("c3")).thenReturn(card);

        Map<String, Object> r = service.review("c3", 3);

        assertEquals(1, r.get("repetitions"));
        assertEquals(1, r.get("interval"));
        // ease: 2.5 + (0.1 - 2*(0.08 + 2*0.02)) = 2.5 + (0.1 - 2*0.12) = 2.5 + (0.1 - 0.24) = 2.36
        assertEquals(2.36, (Double) r.get("easeFactor"), 0.001);
    }

    @Test
    void reviewSecondSuccessSetsInterval6() {
        // reps == 1 → 成功后 reps=2, interval=6
        FlashCard card = makeCard("c4", 2.5, 1, 1);
        when(mapper.findById("c4")).thenReturn(card);

        Map<String, Object> r = service.review("c4", 4);

        assertEquals(2, r.get("repetitions"));
        assertEquals(6, r.get("interval"));
        // ease: 2.5 + (0.1 - 1*(0.08 + 1*0.02)) = 2.5 + (0.1 - 0.1) = 2.5
        assertEquals(2.5, (Double) r.get("easeFactor"), 0.001);
    }

    @Test
    void reviewThirdSuccessUsesEaseFactor() {
        // reps >= 2 → interval = round(oldInterval * ease)
        // reps=2 → reps+1=3, interval = round(6 * 2.5) = round(15) = 15
        FlashCard card = makeCard("c5", 2.5, 6, 2);
        when(mapper.findById("c5")).thenReturn(card);

        Map<String, Object> r = service.review("c5", 4);
        assertEquals(3, r.get("repetitions"));
        assertEquals(15, r.get("interval"));
    }

    @Test
    void reviewRoundsIntervalCorrectly() {
        // interval = 7 * 1.42 = 9.94 → round → 10
        FlashCard card = makeCard("c5b", 1.42, 7, 3);
        when(mapper.findById("c5b")).thenReturn(card);

        Map<String, Object> r = service.review("c5b", 4);
        assertEquals(10, r.get("interval"));
    }

    @Test
    void reviewQualityFiveBoostsEase() {
        // quality=5: (0.1 - 0) = ease + 0.1
        FlashCard card = makeCard("c6", 2.5, 6, 2);
        when(mapper.findById("c6")).thenReturn(card);

        Map<String, Object> r = service.review("c6", 5);
        // ease: 2.5 + 0.1 = 2.6
        assertEquals(2.6, (Double) r.get("easeFactor"), 0.001);
        assertEquals(3, r.get("repetitions"));
        // interval = round(6 * 2.6) = round(15.6) = 16
        assertEquals(16, r.get("interval"));
    }

    @Test
    void reviewEaseFactorFlooredAt1Point3() {
        // 非常低的 quality 让 ease 大幅下降，但不应低于 1.3
        FlashCard card = makeCard("c7", 1.3, 1, 1); // 已经在底线，继续给 0 分
        when(mapper.findById("c7")).thenReturn(card);

        Map<String, Object> r = service.review("c7", 0);
        // 1.3 + (0.1 - 5*(0.08+5*0.02)) = 1.3 - 0.8 = 0.5 → 应 floor 为 1.3
        assertEquals(1.3, (Double) r.get("easeFactor"), 0.001);
    }

    // ---------- review：不存在的卡 ----------

    @Test
    void reviewNonExistentCardThrows() {
        when(mapper.findById("missing")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class,
                () -> service.review("missing", 4));
        assertEquals("闪卡不存在", ex.getMessage());
    }

    // ---------- generateForDocument ----------

    @Test
    void generateForDocumentCreatesCardsForEachKnowledgePoint() {
        List<KnowledgePoint> points = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            KnowledgePoint kp = new KnowledgePoint();
            kp.setId("kp" + i);
            kp.setTitle("标题" + i);
            kp.setContent("内容" + i);
            points.add(kp);
        }
        when(knowledgeMapper.findByDocumentId("d1")).thenReturn(points);

        int count = service.generateForDocument("d1");

        assertEquals(3, count);
        ArgumentCaptor<FlashCard> captor = ArgumentCaptor.forClass(FlashCard.class);
        verify(mapper, times(3)).insert(captor.capture());
        List<FlashCard> all = captor.getAllValues();
        assertEquals("kp1", all.get(0).getKnowledgePointId());
        assertEquals("标题1", all.get(0).getFront());
        assertEquals("内容1", all.get(0).getBack());
    }

    @Test
    void generateForDocumentWithNoPointsReturnsZero() {
        when(knowledgeMapper.findByDocumentId("d2")).thenReturn(List.of());
        assertEquals(0, service.generateForDocument("d2"));
        verify(mapper, never()).insert(any());
    }

    // ---------- delete ----------

    @Test
    void deleteDelegatesToMapper() {
        doNothing().when(mapper).deleteById("cDel");
        assertDoesNotThrow(() -> service.delete("cDel"));
        verify(mapper).deleteById("cDel");
    }

    // ---------- nextReview 时间正确性 ----------

    @Test
    void reviewSetsNextReviewBasedOnInterval() {
        FlashCard card = makeCard("cTime", 2.5, 6, 2);
        when(mapper.findById("cTime")).thenReturn(card);

        // interval = round(6 * 2.5) = 15
        service.review("cTime", 4);

        ArgumentCaptor<FlashCard> captor = ArgumentCaptor.forClass(FlashCard.class);
        verify(mapper).updateReview(captor.capture());
        FlashCard updated = captor.getValue();
        java.time.LocalDateTime expected = java.time.LocalDateTime.now().plusDays(15);
        // 允许 1 分钟的误差（测试运行时间）
        long minutesDiff = java.time.Duration.between(updated.getNextReview(), expected).abs().toMinutes();
        assertTrue(minutesDiff <= 1, "nextReview 应在 ~15 天后，实际相差 " + minutesDiff + " 分钟");
    }
}
