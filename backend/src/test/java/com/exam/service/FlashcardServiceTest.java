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
import java.util.UUID;

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

    private FlashCard freshCard() {
        FlashCard c = new FlashCard();
        c.setId("card-" + UUID.randomUUID().toString().substring(0, 6));
        c.setEaseFactor(2.5);
        c.setInterval(0);
        c.setRepetitions(0);
        c.setNextReview(LocalDateTime.now());
        return c;
    }

    // ==================== SM2 算法：quality >= 3 (答对) ====================

    @Test
    void review_firstPassQuality5_interval1Reps1() {
        FlashCard c = freshCard(); // reps=0
        when(mapper.findById(c.getId())).thenReturn(c);
        ArgumentCaptor<FlashCard> captor = ArgumentCaptor.forClass(FlashCard.class);

        var result = service.review(c.getId(), 5);

        verify(mapper).updateReview(captor.capture());
        FlashCard updated = captor.getValue();
        assertEquals(1, updated.getRepetitions());
        assertEquals(1, updated.getInterval());
        // ease: 2.5 + (0.1 - 0*(...)) = 2.5
        assertEquals(2.5, updated.getEaseFactor(), 0.001);
        assertEquals(1, result.get("repetitions"));
        assertEquals(1, result.get("interval"));
    }

    @Test
    void review_secondPassQuality5_interval6() {
        FlashCard c = freshCard();
        c.setRepetitions(1);
        c.setInterval(1);
        when(mapper.findById(c.getId())).thenReturn(c);
        ArgumentCaptor<FlashCard> captor = ArgumentCaptor.forClass(FlashCard.class);

        service.review(c.getId(), 5);

        verify(mapper).updateReview(captor.capture());
        assertEquals(2, captor.getValue().getRepetitions());
        assertEquals(6, captor.getValue().getInterval());
    }

    @Test
    void review_thirdPassMultipliesByEase() {
        // reps=2 -> interval = prev_interval * ease = 6 * 2.5 = 15
        FlashCard c = freshCard();
        c.setRepetitions(2);
        c.setInterval(6);
        c.setEaseFactor(2.5);
        when(mapper.findById(c.getId())).thenReturn(c);
        ArgumentCaptor<FlashCard> captor = ArgumentCaptor.forClass(FlashCard.class);

        service.review(c.getId(), 5);

        verify(mapper).updateReview(captor.capture());
        FlashCard u = captor.getValue();
        assertEquals(3, u.getRepetitions());
        assertEquals(15, u.getInterval()); // 6 * 2.5
    }

    @Test
    void review_lowQuality3_decreasesEase() {
        // quality=3 -> ease change: 0.1 - (5-3)*(0.08 + (5-3)*0.02) = 0.1 - 2*0.12 = 0.1 - 0.24 = -0.14
        FlashCard c = freshCard();
        c.setEaseFactor(2.5);
        c.setRepetitions(0);
        when(mapper.findById(c.getId())).thenReturn(c);
        ArgumentCaptor<FlashCard> captor = ArgumentCaptor.forClass(FlashCard.class);

        service.review(c.getId(), 3);

        verify(mapper).updateReview(captor.capture());
        // 2.5 - 0.14 = 2.36
        assertEquals(2.36, captor.getValue().getEaseFactor(), 0.001);
    }

    // ==================== SM2：quality < 3 (答错，重置) ====================

    @Test
    void review_quality0_resetsRepsAndInterval1() {
        FlashCard c = freshCard();
        c.setRepetitions(5);
        c.setInterval(30);
        c.setEaseFactor(2.2);
        when(mapper.findById(c.getId())).thenReturn(c);
        ArgumentCaptor<FlashCard> captor = ArgumentCaptor.forClass(FlashCard.class);

        var result = service.review(c.getId(), 0);

        verify(mapper).updateReview(captor.capture());
        FlashCard u = captor.getValue();
        assertEquals(0, u.getRepetitions(), "答错重置 repetitions=0");
        assertEquals(1, u.getInterval(), "答错 interval=1 (明天再学)");
        assertEquals(0, result.get("repetitions"));
        assertEquals(1, result.get("interval"));
    }

    @Test
    void review_quality2_resetsRepsButNotEaseFloor() {
        FlashCard c = freshCard();
        c.setRepetitions(10);
        c.setInterval(100);
        c.setEaseFactor(1.5);
        // quality=2 -> ease: 0.1 - 3*(0.08 + 3*0.02) = 0.1 - 3*0.14 = 0.1 - 0.42 = -0.32 -> 1.5-0.32=1.18, 但下限 1.3
        when(mapper.findById(c.getId())).thenReturn(c);
        ArgumentCaptor<FlashCard> captor = ArgumentCaptor.forClass(FlashCard.class);

        service.review(c.getId(), 2);

        verify(mapper).updateReview(captor.capture());
        FlashCard u = captor.getValue();
        assertEquals(1.3, u.getEaseFactor(), 0.001, "ease 不应低于 1.3 下限");
        assertEquals(0, u.getRepetitions());
        assertEquals(1, u.getInterval());
    }

    // ==================== ease 下限保护 ====================

    @Test
    void review_easeFactorNeverBelow13() {
        // 连续多次低质量响应，确保下限保护
        FlashCard c = freshCard();
        c.setEaseFactor(1.35); // 很接近下限
        when(mapper.findById(c.getId())).thenReturn(c);
        ArgumentCaptor<FlashCard> captor = ArgumentCaptor.forClass(FlashCard.class);

        // quality=0 -> ease change: 0.1 - 5*(0.08 + 5*0.02) = 0.1 - 5*0.18 = 0.1 - 0.9 = -0.8
        service.review(c.getId(), 0);

        verify(mapper).updateReview(captor.capture());
        assertEquals(1.3, captor.getValue().getEaseFactor(), 0.001);
    }

    // ==================== nextReview 时间 ====================

    @Test
    void review_nextReviewSetCorrectly() {
        FlashCard c = freshCard();
        c.setRepetitions(1);
        c.setInterval(1);
        when(mapper.findById(c.getId())).thenReturn(c);
        ArgumentCaptor<FlashCard> captor = ArgumentCaptor.forClass(FlashCard.class);

        LocalDateTime before = LocalDateTime.now();
        service.review(c.getId(), 5); // 第 2 次答对，interval 变成 6
        LocalDateTime after = LocalDateTime.now();

        verify(mapper).updateReview(captor.capture());
        LocalDateTime next = captor.getValue().getNextReview();
        assertTrue(
            (next.isEqual(before.plusDays(6)) || next.isAfter(before.plusDays(6).minusSeconds(1)))
            && (next.isEqual(after.plusDays(6)) || next.isBefore(after.plusDays(6).plusSeconds(1))),
            "nextReview 应该是当前时间加 6 天"
        );
    }

    // ==================== generateForDocument ====================

    @Test
    void generateForDocument_createsCardsFromKnowledgePoints() {
        KnowledgePoint p1 = new KnowledgePoint();
        p1.setId("kp1");
        p1.setTitle("什么是 IoC？");
        p1.setContent("控制反转，将对象创建交给容器");

        KnowledgePoint p2 = new KnowledgePoint();
        p2.setId("kp2");
        p2.setTitle("DI 是什么？");
        p2.setContent("依赖注入，是 IoC 的实现方式");

        when(knowledgeMapper.findByDocumentId("doc1")).thenReturn(List.of(p1, p2));
        ArgumentCaptor<FlashCard> captor = ArgumentCaptor.forClass(FlashCard.class);

        int count = service.generateForDocument("doc1");

        assertEquals(2, count);
        verify(mapper, times(2)).insert(captor.capture());
        var cards = captor.getAllValues();
        assertEquals("什么是 IoC？", cards.get(0).getFront());
        assertEquals("控制反转，将对象创建交给容器", cards.get(0).getBack());
        assertEquals("kp1", cards.get(0).getKnowledgePointId());
        assertEquals("DI 是什么？", cards.get(1).getFront());
    }

    @Test
    void generateForDocument_emptyPointsReturnsZero() {
        when(knowledgeMapper.findByDocumentId("empty")).thenReturn(List.of());
        assertEquals(0, service.generateForDocument("empty"));
        verify(mapper, never()).insert(any());
    }

    // ==================== getTodayCards 统计 ====================

    @Test
    void getTodayCards_returnsCardsAndStats() {
        FlashCard c1 = freshCard();
        FlashCard c2 = freshCard();
        when(mapper.findDueCards("u1", 20)).thenReturn(List.of(c1, c2));
        when(mapper.countByUserId("u1")).thenReturn(15);
        when(mapper.countDue("u1")).thenReturn(3);

        Map<String, Object> result = service.getTodayCards("u1");

        @SuppressWarnings("unchecked")
        var cards = (List<FlashCard>) result.get("cards");
        assertEquals(2, cards.size());
        @SuppressWarnings("unchecked")
        var stats = (Map<String, Integer>) result.get("stats");
        assertEquals(15, stats.get("total"));
        assertEquals(3, stats.get("due"));
    }

    // ==================== 异常 ====================

    @Test
    void review_missingCardThrows() {
        when(mapper.findById("no-card")).thenReturn(null);
        assertThrows(RuntimeException.class, () -> service.review("no-card", 3));
        verify(mapper, never()).updateReview(any());
    }
}
