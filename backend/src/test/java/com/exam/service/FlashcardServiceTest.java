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
    private FlashCardMapper mapper;

    @Mock
    private KnowledgeMapper knowledgeMapper;

    private FlashcardService service;

    @BeforeEach
    void setUp() {
        service = new FlashcardService(mapper, knowledgeMapper);
    }

    // ========== review(): SM-2 间隔重复算法 ==========

    @Test
    void reviewQualityZeroResetsRepetitions() {
        FlashCard card = new FlashCard();
        card.setId("c1");
        card.setEaseFactor(2.5);
        card.setInterval(10);
        card.setRepetitions(5);

        when(mapper.findById("c1")).thenReturn(card);

        var result = service.review("c1", 0); // quality < 3 → fail
        // ease = 2.5 + (0.1 - 5*(0.08+5*0.02)) = 2.5 + (0.1 - 0.9) = 1.7

        assertEquals(0, result.get("repetitions"));
        assertEquals(1, result.get("interval"));
        assertEquals(1.7, (double) result.get("easeFactor"), 0.001);
        verify(mapper).updateReview(card);
    }

    @Test
    void reviewQualityTwoStillFails() {
        FlashCard card = new FlashCard();
        card.setId("c1");
        card.setEaseFactor(2.5);
        card.setInterval(10);
        card.setRepetitions(3);

        when(mapper.findById("c1")).thenReturn(card);

        var result = service.review("c1", 2); // quality < 3

        assertEquals(0, result.get("repetitions"));
        assertEquals(1, result.get("interval"));
    }

    @Test
    void reviewQualityThreeSuccessFirstRep() {
        FlashCard card = new FlashCard();
        card.setId("c1");
        card.setEaseFactor(2.5);
        card.setInterval(0);
        card.setRepetitions(0);

        when(mapper.findById("c1")).thenReturn(card);

        var result = service.review("c1", 3); // reps becomes 1

        assertEquals(1, result.get("repetitions"));
        assertEquals(1, result.get("interval")); // reps==1 → interval=1
    }

    @Test
    void reviewSuccessSecondRepIntervalSix() {
        FlashCard card = new FlashCard();
        card.setId("c1");
        card.setEaseFactor(2.5);
        card.setInterval(1);
        card.setRepetitions(1);

        when(mapper.findById("c1")).thenReturn(card);

        var result = service.review("c1", 4); // reps becomes 2

        assertEquals(2, result.get("repetitions"));
        assertEquals(6, result.get("interval")); // reps==2 → interval=6
    }

    @Test
    void reviewSuccessThirdRepUsesEaseFactor() {
        FlashCard card = new FlashCard();
        card.setId("c1");
        card.setEaseFactor(2.5);
        card.setInterval(6);
        card.setRepetitions(2);

        when(mapper.findById("c1")).thenReturn(card);

        var result = service.review("c1", 4); // reps=3 → interval = 6 * 2.5 = 15

        assertEquals(3, result.get("repetitions"));
        assertEquals(15, result.get("interval"));
    }

    @Test
    void reviewEaseFactorFloorsAt1Point3() {
        FlashCard card = new FlashCard();
        card.setId("c1");
        card.setEaseFactor(1.35); // already low
        card.setInterval(5);
        card.setRepetitions(5);

        when(mapper.findById("c1")).thenReturn(card);

        // quality=0: ease += 0.1 - 5*(0.08+5*0.02) = 0.1 - 5*(0.18) = 0.1 - 0.9 = -0.8
        // new ease = 1.35 - 0.8 = 0.55 → floored to 1.3
        var result = service.review("c1", 0);

        assertEquals(1.3, (double) result.get("easeFactor"), 0.001);
    }

    @Test
    void reviewNextReviewDateSet() {
        FlashCard card = new FlashCard();
        card.setId("c1");
        card.setEaseFactor(2.5);
        card.setInterval(0);
        card.setRepetitions(0);

        when(mapper.findById("c1")).thenReturn(card);

        service.review("c1", 4);

        assertNotNull(card.getNextReview());
        assertTrue(card.getNextReview().isAfter(LocalDateTime.now().minusMinutes(1)));
        assertTrue(card.getNextReview().isBefore(LocalDateTime.now().plusDays(2)));
    }

    @Test
    void reviewNonexistentCardThrows() {
        when(mapper.findById("nope")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class, () -> service.review("nope", 3));
        assertEquals("闪卡不存在", ex.getMessage());
    }

    // ========== getTodayCards() ==========

    @Test
    void getTodayCardsReturnsStats() {
        FlashCard card = new FlashCard();
        card.setId("c1");
        when(mapper.findDueCards("u1", 20)).thenReturn(List.of(card));
        when(mapper.countByUserId("u1")).thenReturn(100);
        when(mapper.countDue("u1")).thenReturn(15);

        var result = service.getTodayCards("u1");

        assertEquals(1, ((List<?>) result.get("cards")).size());
        @SuppressWarnings("unchecked")
        var stats = (Map<String, Object>) result.get("stats");
        assertEquals(100, stats.get("total"));
        assertEquals(15, stats.get("due"));
    }

    // ========== generateForDocument() ==========

    @Test
    void generateForDocumentCreatesCardsFromKnowledgePoints() {
        KnowledgePoint p1 = new KnowledgePoint();
        p1.setId("kp1");
        p1.setTitle("什么是Spring");
        p1.setContent("Spring是一个Java框架");
        KnowledgePoint p2 = new KnowledgePoint();
        p2.setId("kp2");
        p2.setTitle("IoC含义");
        p2.setContent("控制反转，将对象创建交给容器");

        when(knowledgeMapper.findByDocumentId("doc1")).thenReturn(List.of(p1, p2));

        int count = service.generateForDocument("doc1");

        assertEquals(2, count);
        verify(mapper, times(2)).insert(any(FlashCard.class));
    }

    @Test
    void generateForDocumentWithNoPoints() {
        when(knowledgeMapper.findByDocumentId("doc1")).thenReturn(List.of());

        int count = service.generateForDocument("doc1");

        assertEquals(0, count);
        verify(mapper, never()).insert(any());
    }

    // ========== delete() ==========

    @Test
    void deleteDelegatesToMapper() {
        service.delete("c1");
        verify(mapper).deleteById("c1");
    }
}
