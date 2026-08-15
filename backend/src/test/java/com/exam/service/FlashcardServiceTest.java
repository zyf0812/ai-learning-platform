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

    private FlashCard freshCard() {
        FlashCard c = new FlashCard();
        c.setId("c1");
        c.setEaseFactor(2.5);   // SM-2 默认值
        c.setInterval(0);
        c.setRepetitions(0);
        c.setNextReview(LocalDateTime.now());
        return c;
    }

    // ---------- review() - SM-2 间隔重复算法 ----------

    @Test
    void reviewFirstTimeQuality5DoublesRepsAndSetsInterval1() {
        FlashCard card = freshCard();
        when(mapper.findById("c1")).thenReturn(card);
        doNothing().when(mapper).updateReview(any());

        var res = service.review("c1", 5);

        // quality=5: ease = 2.5 + (0.1 - 0*(0.08+0*0.02)) = 2.6
        // reps: card初始0 → reps=0+1=1; reps==1 → interval = 1
        assertEquals(1, res.get("repetitions"));
        assertEquals(1, res.get("interval"));
        assertEquals(2.6, (Double) res.get("easeFactor"), 0.001);

        ArgumentCaptor<FlashCard> captor = ArgumentCaptor.forClass(FlashCard.class);
        verify(mapper).updateReview(captor.capture());
        FlashCard saved = captor.getValue();
        assertEquals(1, saved.getRepetitions());
        assertEquals(1, saved.getInterval());
        assertEquals(2.6, saved.getEaseFactor(), 0.001);
        assertNotNull(saved.getNextReview());
    }

    @Test
    void reviewSecondCorrectTimeSetsInterval6() {
        FlashCard card = freshCard();
        card.setRepetitions(1);
        card.setInterval(1);
        card.setEaseFactor(2.6);
        when(mapper.findById("c1")).thenReturn(card);
        doNothing().when(mapper).updateReview(any());

        var res = service.review("c1", 4);
        // quality=4: ease = 2.6 + (0.1 - 1*(0.08+1*0.02)) = 2.6 + (0.1 - 0.10) = 2.6
        // reps==2 → interval = 6
        assertEquals(2, res.get("repetitions"));
        assertEquals(6, res.get("interval"));
        assertEquals(2.6, (Double) res.get("easeFactor"), 0.001);
    }

    @Test
    void reviewThirdCorrectUsesIntervalTimesEase() {
        FlashCard card = freshCard();
        card.setRepetitions(2);
        card.setInterval(6);
        card.setEaseFactor(2.5);
        when(mapper.findById("c1")).thenReturn(card);
        doNothing().when(mapper).updateReview(any());

        var res = service.review("c1", 5);
        // reps==3: interval = round(6 * 2.6) = round(15.6) = 16
        // ease: 2.5 + 0.1 - 0 = 2.6
        assertEquals(3, res.get("repetitions"));
        assertEquals(16, res.get("interval")); // round(6 * 2.6) = 16
    }

    @Test
    void reviewQuality0ResetsRepetitionsAndInterval1() {
        FlashCard card = freshCard();
        card.setRepetitions(5);
        card.setInterval(30);
        card.setEaseFactor(2.6);
        when(mapper.findById("c1")).thenReturn(card);
        doNothing().when(mapper).updateReview(any());

        var res = service.review("c1", 0);
        // quality<3 → interval=1, reps=0
        // ease = 2.6 + (0.1 - 5*(0.08 + 5*0.02)) = 2.6 + (0.1 - 5*0.18) = 2.6 + (0.1 - 0.9) = 2.6 - 0.8 = 1.8
        assertEquals(0, res.get("repetitions"));
        assertEquals(1, res.get("interval"));
        assertEquals(1.8, (Double) res.get("easeFactor"), 0.001);
    }

    @Test
    void reviewQuality2StillResetsRepetitions() {
        FlashCard card = freshCard();
        card.setRepetitions(10);
        card.setInterval(100);
        card.setEaseFactor(2.6);
        when(mapper.findById("c1")).thenReturn(card);
        doNothing().when(mapper).updateReview(any());

        var res = service.review("c1", 2);
        // quality<3 → reset
        assertEquals(0, res.get("repetitions"));
        assertEquals(1, res.get("interval"));
    }

    @Test
    void reviewQuality3DoesNotReset() {
        FlashCard card = freshCard();
        card.setRepetitions(2);
        card.setInterval(6);
        card.setEaseFactor(2.5);
        when(mapper.findById("c1")).thenReturn(card);
        doNothing().when(mapper).updateReview(any());

        var res = service.review("c1", 3);
        // quality=3: ease = 2.5 + (0.1 - 2*(0.08+2*0.02)) = 2.5 + (0.1 - 2*0.12) = 2.5 + (0.1 - 0.24) = 2.5 - 0.14 = 2.36
        // reps == 3, interval = round(6 * 2.36) = round(14.16) = 14
        assertEquals(3, res.get("repetitions"));
        assertEquals(14, res.get("interval"));
    }

    @Test
    void reviewEaseFactorHasLowerBoundOf1Point3() {
        // 连续多次低质量评价，ease 不能低于 1.3
        FlashCard card = freshCard();
        card.setRepetitions(2);
        card.setInterval(6);
        card.setEaseFactor(1.35); // 已经接近下限
        when(mapper.findById("c1")).thenReturn(card);
        doNothing().when(mapper).updateReview(any());

        // quality=0 会大幅降低 ease
        var res = service.review("c1", 0);
        // ease = 1.35 + (0.1 - 5*(0.08+5*0.02)) = 1.35 + (0.1 - 0.9) = 1.35 - 0.8 = 0.55 → 下限 1.3
        assertEquals(1.3, (Double) res.get("easeFactor"), 0.001);
    }

    @Test
    void reviewCardNotFoundThrows() {
        when(mapper.findById("missing")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> service.review("missing", 3));
        assertEquals("闪卡不存在", ex.getMessage());
    }

    // ---------- getTodayCards ----------

    @Test
    void getTodayCardsReturnsCardsAndStats() {
        FlashCard c1 = new FlashCard(); c1.setId("c1");
        FlashCard c2 = new FlashCard(); c2.setId("c2");
        when(mapper.findDueCards("u1", 20)).thenReturn(List.of(c1, c2));
        when(mapper.countByUserId("u1")).thenReturn(100);
        when(mapper.countDue("u1")).thenReturn(15);

        var res = service.getTodayCards("u1");
        @SuppressWarnings("unchecked")
        var cards = (List<FlashCard>) res.get("cards");
        assertEquals(2, cards.size());
        @SuppressWarnings("unchecked")
        var stats = (Map<String, Integer>) res.get("stats");
        assertEquals(100, stats.get("total"));
        assertEquals(15, stats.get("due"));
    }

    // ---------- generateForDocument ----------

    @Test
    void generateForDocumentCreatesFlashCardsFromKnowledgePoints() {
        KnowledgePoint kp1 = new KnowledgePoint();
        kp1.setId("kp1");
        kp1.setTitle("什么是Java？");
        kp1.setContent("Java是一种面向对象的编程语言");

        KnowledgePoint kp2 = new KnowledgePoint();
        kp2.setId("kp2");
        kp2.setTitle("Spring的核心？");
        kp2.setContent("IoC和AOP");

        when(knowledgeMapper.findByDocumentId("doc1")).thenReturn(List.of(kp1, kp2));

        int count = service.generateForDocument("doc1");

        assertEquals(2, count);
        ArgumentCaptor<FlashCard> captor = ArgumentCaptor.forClass(FlashCard.class);
        verify(mapper, times(2)).insert(captor.capture());
        List<FlashCard> inserted = captor.getAllValues();
        assertEquals("什么是Java？", inserted.get(0).getFront());
        assertEquals("Java是一种面向对象的编程语言", inserted.get(0).getBack());
        assertEquals("kp1", inserted.get(0).getKnowledgePointId());
        assertEquals("Spring的核心？", inserted.get(1).getFront());
        assertNotNull(inserted.get(0).getId());
        assertNotNull(inserted.get(1).getId());
        assertNotEquals(inserted.get(0).getId(), inserted.get(1).getId());
    }

    @Test
    void generateForDocumentNoKnowledgePointsReturnsZero() {
        when(knowledgeMapper.findByDocumentId("doc-empty")).thenReturn(List.of());

        int count = service.generateForDocument("doc-empty");
        assertEquals(0, count);
        verify(mapper, never()).insert(any());
    }

    // ---------- delete ----------

    @Test
    void deleteCallsMapper() {
        doNothing().when(mapper).deleteById("c-del");

        service.delete("c-del");

        verify(mapper).deleteById("c-del");
    }
}
