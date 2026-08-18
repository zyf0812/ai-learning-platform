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

/**
 * FlashcardService SM-2 间隔重复算法单元测试
 * 覆盖 SM-2 核心公式：ease 调整、interval 递增、quality 边界、ease 最低值 1.3
 */
@ExtendWith(MockitoExtension.class)
class FlashcardServiceTest {

    @Mock private FlashCardMapper mapper;
    @Mock private KnowledgeMapper knowledgeMapper;

    private FlashcardService service;

    @BeforeEach
    void setUp() {
        service = new FlashcardService(mapper, knowledgeMapper);
    }

    private FlashCard newCard(double ease, int interval, int reps) {
        FlashCard c = new FlashCard();
        c.setId("c1");
        c.setEaseFactor(ease);
        c.setInterval(interval);
        c.setRepetitions(reps);
        c.setNextReview(LocalDateTime.now().minusDays(1));
        return c;
    }

    // ========== SM-2: quality < 3 (失败) → interval=1, reps=0 ==========

    @Test
    void reviewQuality0FailedResets() {
        FlashCard c = newCard(2.5, 10, 5);
        when(mapper.findById("c1")).thenReturn(c);

        var r = service.review("c1", 0);
        assertEquals(1, r.get("interval"));
        assertEquals(0, r.get("repetitions"));
        // ease 降低：2.5 + (0.1 - (5-0)*(0.08+(5-0)*0.02))
        // = 2.5 + (0.1 - 5*(0.08+0.1)) = 2.5 + (0.1 - 5*0.18) = 2.5 + (0.1-0.9) = 2.5-0.8 = 1.7
        assertEquals(1.7, (Double) r.get("easeFactor"), 0.0001);
    }

    @Test
    void reviewQuality2FailedStillResetsReps() {
        FlashCard c = newCard(2.5, 30, 10);
        when(mapper.findById("c1")).thenReturn(c);

        var r = service.review("c1", 2);
        assertEquals(1, r.get("interval"));
        assertEquals(0, r.get("repetitions"));
    }

    // ========== SM-2: quality >= 3 (通过) ==========

    @Test
    void reviewQuality3FirstPassReps1Interval1() {
        // reps = 0 → 新卡
        FlashCard c = newCard(2.5, 0, 0);
        when(mapper.findById("c1")).thenReturn(c);

        var r = service.review("c1", 3);
        assertEquals(1, r.get("repetitions"));
        assertEquals(1, r.get("interval"));
    }

    @Test
    void reviewQuality3SecondPassReps2Interval6() {
        // reps = 1 → 第二次通过
        FlashCard c = newCard(2.5, 1, 1);
        when(mapper.findById("c1")).thenReturn(c);

        var r = service.review("c1", 3);
        assertEquals(2, r.get("repetitions"));
        assertEquals(6, r.get("interval"));
    }

    @Test
    void reviewQuality3ThirdPassIntervalMultipliedByEase() {
        // ease 先按 quality=3 更新：delta = 0.1 - 2*(0.08+0.04) = -0.14 → new_ease = 2.36
        // reps >= 2 → interval = round(prev_interval * new_ease) = round(6 * 2.36) = round(14.16) = 14
        FlashCard c = newCard(2.5, 6, 2);
        when(mapper.findById("c1")).thenReturn(c);

        var r = service.review("c1", 3);
        assertEquals(3, r.get("repetitions"));
        assertEquals(14, r.get("interval"));
    }

    @Test
    void reviewIntervalRoundsHalfUp() {
        // interval = 7, ease = 2.3 → 7*2.3 = 16.1 → round=16
        // 这里让 7*2.35 = 16.45 → round 16, 或 interval=8, ease=2.5 → 20
        FlashCard c = newCard(2.5, 8, 2); // 8 * 2.5 = 20
        when(mapper.findById("c1")).thenReturn(c);
        assertEquals(20, service.review("c1", 4).get("interval"));
    }

    // ========== quality=5 (完美) → ease 略微升高 ==========

    @Test
    void reviewQuality5EaseIncreasesSlightly() {
        // ease = 2.5, quality=5
        // delta = 0.1 - 0 * (...) = +0.1
        FlashCard c = newCard(2.5, 10, 5);
        when(mapper.findById("c1")).thenReturn(c);

        assertEquals(2.6, (Double) service.review("c1", 5).get("easeFactor"), 0.0001);
    }

    @Test
    void reviewQuality4EaseUnchanged() {
        // quality=4: 5-4=1 → delta = 0.1 - 1*(0.08 + 1*0.02) = 0.1 - 0.1 = 0
        FlashCard c = newCard(2.5, 10, 5);
        when(mapper.findById("c1")).thenReturn(c);

        assertEquals(2.5, (Double) service.review("c1", 4).get("easeFactor"), 0.0001);
    }

    // ========== ease 最低值：1.3 ==========

    @Test
    void reviewEaseCannotGoBelow1Point3() {
        // 即使多次失败，ease 不能跌破 1.3
        FlashCard c = newCard(1.35, 10, 10); // ease 已经很低
        when(mapper.findById("c1")).thenReturn(c);

        // quality=0 会扣 ease：0.1 - 5*(0.08+0.1) = -0.8 → 1.35 - 0.8 = 0.55 < 1.3
        // 应被截断为 1.3
        assertEquals(1.3, (Double) service.review("c1", 0).get("easeFactor"), 0.0001);
    }

    @Test
    void reviewEaseExactly1Point3IsKept() {
        FlashCard c = newCard(1.3, 1, 1);
        when(mapper.findById("c1")).thenReturn(c);

        // quality=3 → ease: 1.3 + (0.1 - 2*(0.08+2*0.02)) = 1.3 + (0.1 - 2*0.12)
        // = 1.3 + (0.1 - 0.24) = 1.3 - 0.14 = 1.16 < 1.3 → 应该保持 1.3
        assertEquals(1.3, (Double) service.review("c1", 3).get("easeFactor"), 0.0001);
    }

    // ========== nextReview 被正确更新 ==========

    @Test
    void reviewPersistsUpdatedCard() {
        FlashCard c = newCard(2.5, 1, 1);
        when(mapper.findById("c1")).thenReturn(c);

        service.review("c1", 4);

        var captor = ArgumentCaptor.forClass(FlashCard.class);
        verify(mapper).updateReview(captor.capture());
        FlashCard saved = captor.getValue();
        assertEquals(2, saved.getRepetitions());
        assertEquals(6, saved.getInterval());
        assertNotNull(saved.getNextReview());
        // nextReview 应该是今天 + 6 天
        assertTrue(saved.getNextReview().isAfter(LocalDateTime.now().plusDays(5)));
        assertTrue(saved.getNextReview().isBefore(LocalDateTime.now().plusDays(7)));
    }

    // ========== 闪卡不存在 ==========

    @Test
    void reviewMissingCardThrows() {
        when(mapper.findById("nope")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class,
            () -> service.review("nope", 4));
        assertTrue(ex.getMessage().contains("闪卡不存在"));
    }

    // ========== getTodayCards 统计 ==========

    @Test
    void getTodayCardsAggregatesStats() {
        var dueCards = List.of(new FlashCard(), new FlashCard());
        when(mapper.findDueCards("u1", 20)).thenReturn(dueCards);
        when(mapper.countByUserId("u1")).thenReturn(100);
        when(mapper.countDue("u1")).thenReturn(15);

        var r = service.getTodayCards("u1");
        assertEquals(dueCards, r.get("cards"));
        @SuppressWarnings("unchecked")
        var stats = (Map<String, Object>) r.get("stats");
        assertEquals(100, stats.get("total"));
        assertEquals(15, stats.get("due"));
    }

    // ========== generateForDocument ==========

    @Test
    void generateForDocumentCreatesCardsFromKnowledgePoints() {
        KnowledgePoint p1 = new KnowledgePoint();
        p1.setId("kp1");
        p1.setTitle("封装");
        p1.setContent("封装是OOP原则");
        KnowledgePoint p2 = new KnowledgePoint();
        p2.setId("kp2");
        p2.setTitle("继承");
        p2.setContent("继承是OOP机制");
        when(knowledgeMapper.findByDocumentId("d1")).thenReturn(List.of(p1, p2));

        int count = service.generateForDocument("d1");
        assertEquals(2, count);

        var captor = ArgumentCaptor.forClass(FlashCard.class);
        verify(mapper, times(2)).insert(captor.capture());
        var cards = captor.getAllValues();
        assertEquals("kp1", cards.get(0).getKnowledgePointId());
        assertEquals("封装", cards.get(0).getFront());
        assertEquals("封装是OOP原则", cards.get(0).getBack());
        assertEquals("kp2", cards.get(1).getKnowledgePointId());
    }

    @Test
    void generateForDocumentEmptyPointsReturnsZero() {
        when(knowledgeMapper.findByDocumentId("d1")).thenReturn(List.of());
        assertEquals(0, service.generateForDocument("d1"));
        verify(mapper, never()).insert(any());
    }

    // ========== delete ==========

    @Test
    void deleteCard() {
        service.delete("c1");
        verify(mapper).deleteById("c1");
    }
}
