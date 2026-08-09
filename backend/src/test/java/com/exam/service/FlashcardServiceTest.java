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
    private FlashCardMapper mapper;
    @Mock
    private KnowledgeMapper knowledgeMapper;

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
        c.setNextReview(LocalDateTime.now());
        return c;
    }

    // ==================== review() - 初始学习阶段 ====================

    @Test
    void reviewFirstSuccessQuality5SetsInterval1Day() {
        // 初始状态 (全新卡: reps=0, interval=0, ease=2.5)
        FlashCard card = makeCard("c1", 2.5, 0, 0);
        when(mapper.findById("c1")).thenReturn(card);
        doNothing().when(mapper).updateReview(any());

        Map<String, Object> result = service.review("c1", 5);

        assertEquals(1, result.get("interval"));
        assertEquals(1, result.get("repetitions"));
        // ease = 2.5 + (0.1 - 0 * (0.08 + 0*0.02)) = 2.6
        assertEquals(2.6, (Double) result.get("easeFactor"), 0.001);

        ArgumentCaptor<FlashCard> captor = ArgumentCaptor.forClass(FlashCard.class);
        verify(mapper).updateReview(captor.capture());
        assertNotNull(captor.getValue().getNextReview());
    }

    @Test
    void reviewSecondSuccessQuality4SetsInterval6Days() {
        // reps=1, interval=1 (已通过第一次复习)
        FlashCard card = makeCard("c1", 2.5, 1, 1);
        when(mapper.findById("c1")).thenReturn(card);
        doNothing().when(mapper).updateReview(any());

        Map<String, Object> result = service.review("c1", 4);

        assertEquals(2, result.get("repetitions"));
        assertEquals(6, result.get("interval")); // reps==2 → interval=6
    }

    @Test
    void reviewThirdSuccessUsesIntervalTimesEase() {
        // reps=2, interval=6, ease=2.5
        FlashCard card = makeCard("c1", 2.5, 6, 2);
        when(mapper.findById("c1")).thenReturn(card);
        doNothing().when(mapper).updateReview(any());

        Map<String, Object> result = service.review("c1", 5);

        assertEquals(3, result.get("repetitions"));
        // interval = round(6 * 2.6) = round(15.6) = 16
        assertEquals(16, result.get("interval"));
    }

    // ==================== review() - quality<3 重置 ====================

    @Test
    void reviewQualityZeroResetsRepsAndInterval() {
        FlashCard card = makeCard("c1", 2.8, 30, 10); // 学到中期
        when(mapper.findById("c1")).thenReturn(card);
        doNothing().when(mapper).updateReview(any());

        Map<String, Object> result = service.review("c1", 0);

        assertEquals(0, result.get("repetitions"));
        assertEquals(1, result.get("interval"));
    }

    @Test
    void reviewQualityTwoResetsRepsAndInterval() {
        FlashCard card = makeCard("c1", 2.6, 20, 5);
        when(mapper.findById("c1")).thenReturn(card);
        doNothing().when(mapper).updateReview(any());

        Map<String, Object> result = service.review("c1", 2);

        assertEquals(0, result.get("repetitions"));
        assertEquals(1, result.get("interval"));
    }

    @Test
    void reviewQualityThreeIsPassingNotReset() {
        // quality=3 是临界值：<3 才重置
        FlashCard card = makeCard("c1", 2.5, 6, 2);
        when(mapper.findById("c1")).thenReturn(card);
        doNothing().when(mapper).updateReview(any());

        Map<String, Object> result = service.review("c1", 3);

        assertEquals(3, result.get("repetitions")); // reps 增加
        assertNotEquals(1, result.get("interval"));  // interval 不等于 1
    }

    // ==================== review() - ease factor 调整 ====================

    @Test
    void reviewQuality5IncreasesEase() {
        FlashCard card = makeCard("c1", 2.5, 6, 2);
        when(mapper.findById("c1")).thenReturn(card);
        doNothing().when(mapper).updateReview(any());

        double newEase = (Double) service.review("c1", 5).get("easeFactor");
        // ease = 2.5 + (0.1 - 0 * (...)) = 2.6
        assertEquals(2.6, newEase, 0.001);
        assertTrue(newEase > 2.5);
    }

    @Test
    void reviewLowQualityDecreasesEase() {
        FlashCard card = makeCard("c1", 2.5, 6, 2);
        when(mapper.findById("c1")).thenReturn(card);
        doNothing().when(mapper).updateReview(any());

        // quality=3: (5-3)=2
        // delta = 0.1 - 2*(0.08 + 2*0.02) = 0.1 - 2*(0.12) = 0.1 - 0.24 = -0.14
        double newEase = (Double) service.review("c1", 3).get("easeFactor");
        assertEquals(2.5 - 0.14, newEase, 0.001);
    }

    @Test
    void reviewEaseFactorHasHardFloor1Point3() {
        // 初始 ease 已经很低，再用极低 quality 尝试
        FlashCard card = makeCard("c1", 1.4, 10, 5);
        when(mapper.findById("c1")).thenReturn(card);
        doNothing().when(mapper).updateReview(any());

        // quality=2: 虽然 quality<3 会触发重置，但 ease 先计算
        // delta = 0.1 - 3*(0.08+3*0.02) = 0.1 - 3*0.14 = 0.1-0.42 = -0.32
        // newEase = 1.4 - 0.32 = 1.08 → 被 floor 到 1.3
        double newEase = (Double) service.review("c1", 2).get("easeFactor");
        assertEquals(1.3, newEase, 0.001);
    }

    @Test
    void reviewExtremelyLowEaseCannotDropBelow1Point3() {
        // 用极端初始值（实际不会出现，但边界防护）
        FlashCard card = makeCard("c1", 1.3, 1, 0);
        when(mapper.findById("c1")).thenReturn(card);
        doNothing().when(mapper).updateReview(any());

        double ease = (Double) service.review("c1", 0).get("easeFactor");
        assertTrue(ease >= 1.3);
    }

    // ==================== review() - 异常流 ====================

    @Test
    void reviewNonExistingCardThrows() {
        when(mapper.findById("ghost")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
                () -> service.review("ghost", 3));
        assertEquals("闪卡不存在", ex.getMessage());
    }

    @Test
    void reviewPersistsUpdatedCard() {
        FlashCard card = makeCard("c1", 2.5, 0, 0);
        when(mapper.findById("c1")).thenReturn(card);

        service.review("c1", 5);

        ArgumentCaptor<FlashCard> captor = ArgumentCaptor.forClass(FlashCard.class);
        verify(mapper).updateReview(captor.capture());
        FlashCard saved = captor.getValue();
        assertEquals("c1", saved.getId());
        assertEquals(1, saved.getRepetitions());
        assertNotNull(saved.getNextReview());
    }

    // ==================== getTodayCards() ====================

    @Test
    void getTodayCardsReturnsStatsAndCards() {
        FlashCard c1 = makeCard("c1", 2.5, 1, 1);
        FlashCard c2 = makeCard("c2", 2.5, 2, 2);
        when(mapper.findDueCards("u1", 20)).thenReturn(List.of(c1, c2));
        when(mapper.countByUserId("u1")).thenReturn(100);
        when(mapper.countDue("u1")).thenReturn(15);

        Map<String, Object> result = service.getTodayCards("u1");

        @SuppressWarnings("unchecked")
        List<FlashCard> cards = (List<FlashCard>) result.get("cards");
        assertEquals(2, cards.size());

        @SuppressWarnings("unchecked")
        Map<String, Object> stats = (Map<String, Object>) result.get("stats");
        assertEquals(100, stats.get("total"));
        assertEquals(15, stats.get("due"));
    }

    // ==================== generateForDocument() ====================

    @Test
    void generateForDocumentCreatesCardsForEachKnowledgePoint() {
        KnowledgePoint k1 = new KnowledgePoint();
        k1.setId("kp1");
        k1.setTitle("什么是Java");
        k1.setContent("一种编程语言");

        KnowledgePoint k2 = new KnowledgePoint();
        k2.setId("kp2");
        k2.setTitle("JVM是什么");
        k2.setContent("Java虚拟机");

        KnowledgePoint k3 = new KnowledgePoint();
        k3.setId("kp3");
        k3.setTitle("GC是什么");
        k3.setContent("垃圾回收机制");

        when(knowledgeMapper.findByDocumentId("doc1"))
                .thenReturn(List.of(k1, k2, k3));

        int count = service.generateForDocument("doc1");

        assertEquals(3, count);
        verify(mapper, times(3)).insert(any(FlashCard.class));

        ArgumentCaptor<FlashCard> captor = ArgumentCaptor.forClass(FlashCard.class);
        verify(mapper, times(3)).insert(captor.capture());
        List<FlashCard> allInserted = captor.getAllValues();
        // 验证每张卡的 front/back 对应知识点 title/content
        assertEquals("什么是Java", allInserted.get(0).getFront());
        assertEquals("一种编程语言", allInserted.get(0).getBack());
        assertEquals("kp1", allInserted.get(0).getKnowledgePointId());
    }

    @Test
    void generateForDocumentEmptyKnowledgePointsReturnsZero() {
        when(knowledgeMapper.findByDocumentId("empty")).thenReturn(List.of());

        assertEquals(0, service.generateForDocument("empty"));
        verify(mapper, never()).insert(any());
    }

    // ==================== delete() ====================

    @Test
    void deleteDelegatesToMapper() {
        doNothing().when(mapper).deleteById("c1");
        service.delete("c1");
        verify(mapper).deleteById("c1");
    }

    // ==================== 综合 SM-2 复习场景 ====================

    @Test
    void reviewFullSRSLifecycleFirstFewReps() {
        // 模拟一张新卡经过多次成功复习
        FlashCard card = makeCard("newcard", 2.5, 0, 0);
        when(mapper.findById("newcard")).thenReturn(card);
        doNothing().when(mapper).updateReview(any());

        // 第1次复习 (quality=4) → reps=1, interval=1
        Map<String, Object> r1 = service.review("newcard", 4);
        assertEquals(1, r1.get("interval"));
        assertEquals(1, r1.get("repetitions"));

        // 手动更新卡状态以模拟持久化
        card.setInterval((Integer) r1.get("interval"));
        card.setRepetitions((Integer) r1.get("repetitions"));
        card.setEaseFactor((Double) r1.get("easeFactor"));

        // 第2次复习 (quality=5) → reps=2, interval=6
        Map<String, Object> r2 = service.review("newcard", 5);
        assertEquals(6, r2.get("interval"));
        assertEquals(2, r2.get("repetitions"));

        card.setInterval((Integer) r2.get("interval"));
        card.setRepetitions((Integer) r2.get("repetitions"));
        card.setEaseFactor((Double) r2.get("easeFactor"));

        // 第3次复习 → interval *= ease
        Map<String, Object> r3 = service.review("newcard", 5);
        assertEquals(3, r3.get("repetitions"));
        assertTrue((Integer) r3.get("interval") > 6);
    }

    @Test
    void reviewMidCycleFailingResetsProgress() {
        // 中期学习失败 → 重置 reps=0, interval=1
        FlashCard card = makeCard("hard", 2.6, 21, 5);
        when(mapper.findById("hard")).thenReturn(card);
        doNothing().when(mapper).updateReview(any());

        Map<String, Object> result = service.review("hard", 1); // quality=1 < 3
        assertEquals(0, result.get("repetitions"));
        assertEquals(1, result.get("interval"));
    }
}
