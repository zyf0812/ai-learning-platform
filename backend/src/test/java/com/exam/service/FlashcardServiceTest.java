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

    // ===== 工具方法：创建FlashCard =====

    private FlashCard makeCard(String id, double ease, int interval, int reps) {
        FlashCard c = new FlashCard();
        c.setId(id);
        c.setEaseFactor(ease);
        c.setInterval(interval);
        c.setRepetitions(reps);
        c.setNextReview(LocalDateTime.now().minusDays(1));
        return c;
    }

    // ===== review() 质量 < 3：视为失败 =====

    @Test
    void reviewQuality0FailsResetsRepsAndInterval1() {
        FlashCard c = makeCard("c1", 2.5, 30, 10);
        when(mapper.findById("c1")).thenReturn(c);
        // 必须有一个doNothing stub，因为updateReview被调用
        doNothing().when(mapper).updateReview(any(FlashCard.class));

        var result = service.review("c1", 0);

        assertEquals(0, result.get("repetitions"));
        assertEquals(1, result.get("interval"));
        // ease factor: 2.5 + (0.1 - 5*(0.08+5*0.02)) = 2.5 + (0.1 - 5*0.18) = 2.5 - 0.8 = 1.7
        assertEquals(1.7, (Double) result.get("easeFactor"), 0.001);
    }

    @Test
    void reviewQuality2Fails() {
        FlashCard c = makeCard("c1", 2.5, 10, 5);
        when(mapper.findById("c1")).thenReturn(c);
        doNothing().when(mapper).updateReview(any(FlashCard.class));

        var result = service.review("c1", 2);
        assertEquals(0, result.get("repetitions"));
        assertEquals(1, result.get("interval"));
        // ease = 2.5 + (0.1 - 3*(0.08+3*0.02)) = 2.5 + (0.1 - 3*0.14) = 2.5 + (0.1 - 0.42) = 2.18
        assertEquals(2.18, (Double) result.get("easeFactor"), 0.001);
    }

    // ===== review() 质量 ≥3：成功 =====

    @Test
    void reviewQuality3FirstRepetitionInterval1() {
        FlashCard c = makeCard("c1", 2.5, 0, 0);
        when(mapper.findById("c1")).thenReturn(c);
        doNothing().when(mapper).updateReview(any(FlashCard.class));

        var result = service.review("c1", 3);

        assertEquals(1, result.get("repetitions"));
        assertEquals(1, result.get("interval"));
        // ease = 2.5 + (0.1 - 2*(0.08+2*0.02)) = 2.5 + (0.1 - 2*0.12) = 2.5 + (-0.14) = 2.36
        assertEquals(2.36, (Double) result.get("easeFactor"), 0.001);
    }

    @Test
    void reviewQuality5SecondRepetitionInterval6() {
        // reps == 1 → reps+1 == 2 → interval = 6
        FlashCard c = makeCard("c1", 2.5, 1, 1);
        when(mapper.findById("c1")).thenReturn(c);
        doNothing().when(mapper).updateReview(any(FlashCard.class));

        var result = service.review("c1", 5);
        assertEquals(2, result.get("repetitions"));
        assertEquals(6, result.get("interval"));
        // quality=5 → ease不变 (0.1 - 0*...)
        assertEquals(2.6, (Double) result.get("easeFactor"), 0.001);
    }

    @Test
    void reviewQuality4ThirdRepetitionGrowsWithEase() {
        // reps=2 → reps+1 ≥3 → interval = round(interval * ease)
        FlashCard c = makeCard("c1", 2.0, 6, 2);
        when(mapper.findById("c1")).thenReturn(c);
        doNothing().when(mapper).updateReview(any(FlashCard.class));

        var result = service.review("c1", 4);
        assertEquals(3, result.get("repetitions"));
        // quality=4 → ease = 2.0 + (0.1 - 1*(0.08 + 1*0.02)) = 2.0 + 0.0 = 2.0
        // interval = round(6 * 2.0) = 12
        assertEquals(12, result.get("interval"));
        assertEquals(2.0, (Double) result.get("easeFactor"), 0.001);
    }

    @Test
    void reviewQuality3ThirdRepRoundedInterval() {
        // interval=5, ease=1.7 → 5*1.7=8.5 → round=9
        FlashCard c = makeCard("c1", 1.7, 5, 2);
        when(mapper.findById("c1")).thenReturn(c);
        doNothing().when(mapper).updateReview(any(FlashCard.class));

        var result = service.review("c1", 3);
        // ease=1.7 + (0.1 - 2*(0.08+2*0.02)) = 1.7 + (0.1-0.24) = 1.56
        // 但实际上interval使用旧的ease还是新的？
        // 代码中：先算新ease，再用新ease算interval
        // new_ease = 1.7 + 0.1 - 2*(0.08 + 2*0.02) = 1.7 + 0.1 - 2*0.12 = 1.8 - 0.24 = 1.56
        // 然后 interval = round(5 * 1.56) = round(7.8) = 8
        assertEquals(8, result.get("interval"));
        assertEquals(1.56, (Double) result.get("easeFactor"), 0.001);
    }

    // ===== ease factor 最小值保护 =====

    @Test
    void reviewBadQualityEaseNeverBelow13() {
        // 初始ease=1.4，超差的quality=0让ease继续下降，但最低不能低于1.3
        FlashCard c = makeCard("c1", 1.4, 1, 3);
        when(mapper.findById("c1")).thenReturn(c);
        doNothing().when(mapper).updateReview(any(FlashCard.class));

        var result = service.review("c1", 0);

        assertEquals(1.3, (Double) result.get("easeFactor"), 0.001);
    }

    @Test
    void reviewQuality0InitialEase131StaysAbove13() {
        FlashCard c = makeCard("c1", 1.31, 1, 3);
        when(mapper.findById("c1")).thenReturn(c);
        doNothing().when(mapper).updateReview(any(FlashCard.class));

        var result = service.review("c1", 0);
        // 1.31 - 0.8 = 0.51 → 强制到1.3
        assertEquals(1.3, (Double) result.get("easeFactor"), 0.001);
    }

    // ===== updateReview 被调用 =====

    @Test
    void reviewPersistsUpdatedCard() {
        FlashCard c = makeCard("c1", 2.5, 1, 1);
        when(mapper.findById("c1")).thenReturn(c);
        doNothing().when(mapper).updateReview(any(FlashCard.class));

        service.review("c1", 4);

        ArgumentCaptor<FlashCard> captor = ArgumentCaptor.forClass(FlashCard.class);
        verify(mapper).updateReview(captor.capture());
        FlashCard updated = captor.getValue();
        assertEquals("c1", updated.getId());
        assertEquals(2, updated.getRepetitions()); // 1+1
        assertNotNull(updated.getNextReview());
        // nextReview 应该是今天 + interval=6 → 6天后
        assertTrue(updated.getNextReview().isAfter(LocalDateTime.now().plusDays(5)));
        assertTrue(updated.getNextReview().isBefore(LocalDateTime.now().plusDays(7)));
    }

    // ===== 卡片不存在 =====

    @Test
    void reviewNonExistentCardThrows() {
        when(mapper.findById("ghost")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> service.review("ghost", 4));
        assertEquals("闪卡不存在", ex.getMessage());
    }

    // ===== generateForDocument =====

    @Test
    void generateForDocumentCreatesCardsFromKnowledgePoints() {
        KnowledgePoint p1 = new KnowledgePoint();
        p1.setId("kp1");
        p1.setTitle("什么是OOP？");
        p1.setContent("面向对象编程的定义...");
        KnowledgePoint p2 = new KnowledgePoint();
        p2.setId("kp2");
        p2.setTitle("SOLID原则");
        p2.setContent("SOLID原则的内容...");
        when(knowledgeMapper.findByDocumentId("doc1")).thenReturn(List.of(p1, p2));
        doNothing().when(mapper).insert(any(FlashCard.class));

        int count = service.generateForDocument("doc1");

        assertEquals(2, count);
        ArgumentCaptor<FlashCard> captor = ArgumentCaptor.forClass(FlashCard.class);
        verify(mapper, times(2)).insert(captor.capture());
        List<FlashCard> cards = captor.getAllValues();
        assertEquals("kp1", cards.get(0).getKnowledgePointId());
        assertEquals("什么是OOP？", cards.get(0).getFront());
        assertEquals("面向对象编程的定义...", cards.get(0).getBack());
        assertEquals("kp2", cards.get(1).getKnowledgePointId());
    }

    @Test
    void generateForDocumentNoKnowledgePointsReturnsZero() {
        when(knowledgeMapper.findByDocumentId("emptyDoc")).thenReturn(List.of());
        assertEquals(0, service.generateForDocument("emptyDoc"));
        verify(mapper, never()).insert(any(FlashCard.class));
    }

    @Test
    void generateForDocumentEachCardHasUnique8CharId() {
        KnowledgePoint p1 = new KnowledgePoint(); p1.setId("a"); p1.setTitle("t1"); p1.setContent("c1");
        KnowledgePoint p2 = new KnowledgePoint(); p2.setId("b"); p2.setTitle("t2"); p2.setContent("c2");
        when(knowledgeMapper.findByDocumentId("doc")).thenReturn(List.of(p1, p2));
        doNothing().when(mapper).insert(any(FlashCard.class));

        service.generateForDocument("doc");

        ArgumentCaptor<FlashCard> captor = ArgumentCaptor.forClass(FlashCard.class);
        verify(mapper, times(2)).insert(captor.capture());
        assertNotEquals(captor.getAllValues().get(0).getId(), captor.getAllValues().get(1).getId());
        assertEquals(8, captor.getAllValues().get(0).getId().length());
    }

    // ===== delete =====

    @Test
    void deleteDelegatesToMapper() {
        doNothing().when(mapper).deleteById("c-del");
        service.delete("c-del");
        verify(mapper).deleteById("c-del");
    }

    // ===== getTodayCards =====

    @Test
    void getTodayCardsReturnsDueCardsAndStats() {
        List<FlashCard> due = List.of(makeCard("d1", 2.5, 1, 0));
        when(mapper.findDueCards("u1", 20)).thenReturn(due);
        when(mapper.countByUserId("u1")).thenReturn(50);
        when(mapper.countDue("u1")).thenReturn(5);

        var result = service.getTodayCards("u1");

        assertEquals(due, result.get("cards"));
        @SuppressWarnings("unchecked")
        var stats = (java.util.Map<String, Integer>) result.get("stats");
        assertEquals(50, stats.get("total"));
        assertEquals(5, stats.get("due"));
    }
}
