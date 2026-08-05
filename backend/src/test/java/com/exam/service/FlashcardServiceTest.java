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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FlashcardServiceTest {

    @Mock
    private FlashCardMapper flashCardMapper;

    @Mock
    private KnowledgeMapper knowledgeMapper;

    private FlashcardService flashcardService;

    @BeforeEach
    void setUp() {
        flashcardService = new FlashcardService(flashCardMapper, knowledgeMapper);
    }

    // ========== SM-2 间隔重复算法：失败复习 (quality < 3) ==========

    @Test
    void reviewQualityZeroResetsProgress() {
        FlashCard card = new FlashCard();
        card.setId("c1");
        card.setEaseFactor(2.5);
        card.setInterval(10);
        card.setRepetitions(5);

        when(flashCardMapper.findById("c1")).thenReturn(card);
        doNothing().when(flashCardMapper).updateReview(any());

        var result = flashcardService.review("c1", 0);

        assertEquals(1, result.get("interval"));
        assertEquals(0, result.get("repetitions"));
        // ease = 2.5 + 0.1 - (5-0) * (0.08 + 5*0.02) = 2.6 - 5 * 0.18 = 2.6 - 0.9 = 1.7
        assertEquals(1.7, (Double) result.get("easeFactor"), 0.001);

        verify(flashCardMapper).updateReview(argThat(c ->
            c.getInterval() == 1
                && c.getRepetitions() == 0
                && Math.abs(c.getEaseFactor() - 1.7) < 0.001
        ));
    }

    @Test
    void reviewQualityTwoResetsProgress() {
        FlashCard card = new FlashCard();
        card.setId("c1");
        card.setEaseFactor(2.5);
        card.setInterval(20);
        card.setRepetitions(10);

        when(flashCardMapper.findById("c1")).thenReturn(card);
        doNothing().when(flashCardMapper).updateReview(any());

        var result = flashcardService.review("c1", 2);

        assertEquals(1, result.get("interval"));
        assertEquals(0, result.get("repetitions"));
        // ease = 2.5 + 0.1 - 3 * (0.08 + 3*0.02) = 2.6 - 3 * 0.14 = 2.6 - 0.42 = 2.18
        assertEquals(2.18, (Double) result.get("easeFactor"), 0.001);
    }

    // ========== SM-2 间隔重复算法：成功复习 (quality >= 3) ==========

    @Test
    void reviewQualityThreeFirstRepetition() {
        FlashCard card = new FlashCard();
        card.setId("c1");
        card.setEaseFactor(2.5);
        card.setInterval(0);
        card.setRepetitions(0);

        when(flashCardMapper.findById("c1")).thenReturn(card);
        doNothing().when(flashCardMapper).updateReview(any());

        var result = flashcardService.review("c1", 3);

        assertEquals(1, result.get("interval"));
        assertEquals(1, result.get("repetitions"));
        // ease = 2.5 + 0.1 - 2 * (0.08 + 2*0.02) = 2.6 - 2 * 0.12 = 2.6 - 0.24 = 2.36
        assertEquals(2.36, (Double) result.get("easeFactor"), 0.001);
    }

    @Test
    void reviewQualityFourSecondRepetition() {
        // 第2次成功复习 → interval = 6 天
        FlashCard card = new FlashCard();
        card.setId("c1");
        card.setEaseFactor(2.36);
        card.setInterval(1);
        card.setRepetitions(1);

        when(flashCardMapper.findById("c1")).thenReturn(card);
        doNothing().when(flashCardMapper).updateReview(any());

        var result = flashcardService.review("c1", 4);

        assertEquals(6, result.get("interval"));
        assertEquals(2, result.get("repetitions"));
        // ease = 2.36 + 0.1 - 1 * (0.08 + 1*0.02) = 2.46 - 0.1 = 2.36
        assertEquals(2.36, (Double) result.get("easeFactor"), 0.001);
    }

    @Test
    void reviewQualityFiveThirdRepetition() {
        // 第3+次成功复习 → interval = round(interval * ease)
        FlashCard card = new FlashCard();
        card.setId("c1");
        card.setEaseFactor(2.5);
        card.setInterval(6);
        card.setRepetitions(2);

        when(flashCardMapper.findById("c1")).thenReturn(card);
        doNothing().when(flashCardMapper).updateReview(any());

        var result = flashcardService.review("c1", 5);

        // interval = round(6 * 2.5) = round(15) = 15
        assertEquals(15, result.get("interval"));
        assertEquals(3, result.get("repetitions"));
        // ease = 2.5 + 0.1 - 0 * (...) = 2.6
        assertEquals(2.6, (Double) result.get("easeFactor"), 0.001);
    }

    @Test
    void reviewQualityFiveLaterRepetitionIntervalGrows() {
        FlashCard card = new FlashCard();
        card.setId("c1");
        card.setEaseFactor(2.6);
        card.setInterval(15);
        card.setRepetitions(3);

        when(flashCardMapper.findById("c1")).thenReturn(card);
        doNothing().when(flashCardMapper).updateReview(any());

        var result = flashcardService.review("c1", 5);

        // interval = round(15 * 2.6) = round(39) = 39
        assertEquals(39, result.get("interval"));
        assertEquals(4, result.get("repetitions"));
    }

    @Test
    void reviewIntervalRoundsHalfUp() {
        // round(7 * 2.36) = round(16.52) = 17
        FlashCard card = new FlashCard();
        card.setId("c1");
        card.setEaseFactor(2.36);
        card.setInterval(7);
        card.setRepetitions(2);

        when(flashCardMapper.findById("c1")).thenReturn(card);
        doNothing().when(flashCardMapper).updateReview(any());

        var result = flashcardService.review("c1", 4);

        // round(7 * 2.36) = round(16.52) = 17
        assertEquals(17, result.get("interval"));
    }

    // ========== easeFactor 下限：1.3 ==========

    @Test
    void reviewEaseFactorDoesNotGoBelowMinimum() {
        FlashCard card = new FlashCard();
        card.setId("c1");
        card.setEaseFactor(1.35); // 已经接近下限
        card.setInterval(5);
        card.setRepetitions(3);

        when(flashCardMapper.findById("c1")).thenReturn(card);
        doNothing().when(flashCardMapper).updateReview(any());

        // quality=0 会大幅降低 ease
        var result = flashcardService.review("c1", 0);

        // ease = 1.35 + 0.1 - 5*0.18 = 1.45 - 0.9 = 0.55 → 被限制为 1.3
        assertEquals(1.3, (Double) result.get("easeFactor"), 0.001);
    }

    @Test
    void reviewManyFailuresEaseStaysAtMinimum() {
        FlashCard card = new FlashCard();
        card.setId("c1");
        card.setEaseFactor(1.3);
        card.setInterval(1);
        card.setRepetitions(0);

        when(flashCardMapper.findById("c1")).thenReturn(card);
        doNothing().when(flashCardMapper).updateReview(any());

        // 继续失败，ease 不应低于 1.3
        flashcardService.review("c1", 1);
        verify(flashCardMapper).updateReview(argThat(c ->
            c.getEaseFactor() >= 1.3
        ));
    }

    // ========== nextReview 日期设置 ==========

    @Test
    void reviewSetsNextReviewToIntervalDays() {
        FlashCard card = new FlashCard();
        card.setId("c1");
        card.setEaseFactor(2.5);
        card.setInterval(6);
        card.setRepetitions(2);

        when(flashCardMapper.findById("c1")).thenReturn(card);
        doNothing().when(flashCardMapper).updateReview(any());

        LocalDateTime before = LocalDateTime.now();
        flashcardService.review("c1", 5);
        LocalDateTime after = LocalDateTime.now();

        verify(flashCardMapper).updateReview(argThat(c -> {
            LocalDateTime expectedMin = before.plusDays(15).minusSeconds(1);
            LocalDateTime expectedMax = after.plusDays(15).plusSeconds(1);
            return !c.getNextReview().isBefore(expectedMin)
                && !c.getNextReview().isAfter(expectedMax);
        }));
    }

    // ========== 异常情况 ==========

    @Test
    void reviewNonExistentCardThrows() {
        when(flashCardMapper.findById("nonexistent")).thenReturn(null);

        assertThrows(RuntimeException.class,
            () -> flashcardService.review("nonexistent", 4));
    }

    // ========== 今日到期卡片 ==========

    @Test
    void getTodayCardsReturnsStats() {
        FlashCard card1 = new FlashCard(); card1.setId("c1");
        FlashCard card2 = new FlashCard(); card2.setId("c2");
        List<FlashCard> dueCards = List.of(card1, card2);

        when(flashCardMapper.findDueCards("u1", 20)).thenReturn(dueCards);
        when(flashCardMapper.countByUserId("u1")).thenReturn(100);
        when(flashCardMapper.countDue("u1")).thenReturn(15);

        var result = flashcardService.getTodayCards("u1");

        assertEquals(dueCards, result.get("cards"));
        @SuppressWarnings("unchecked")
        var stats = (java.util.Map<String, Object>) result.get("stats");
        assertEquals(100, stats.get("total"));
        assertEquals(15, stats.get("due"));
    }

    // ========== 根据知识点生成闪卡 ==========

    @Test
    void generateForDocumentCreatesCardsForEachKnowledgePoint() {
        KnowledgePoint p1 = new KnowledgePoint();
        p1.setId("kp1");
        p1.setTitle("什么是Java");
        p1.setContent("Java是一种编程语言");

        KnowledgePoint p2 = new KnowledgePoint();
        p2.setId("kp2");
        p2.setTitle("Spring是什么");
        p2.setContent("Spring是Java开发框架");

        KnowledgePoint p3 = new KnowledgePoint();
        p3.setId("kp3");
        p3.setTitle("OOP特性");
        p3.setContent("封装、继承、多态");

        when(knowledgeMapper.findByDocumentId("d1")).thenReturn(List.of(p1, p2, p3));
        when(flashCardMapper.insert(any())).thenReturn(1);

        int count = flashcardService.generateForDocument("d1");

        assertEquals(3, count);
        verify(flashCardMapper, times(3)).insert(any());

        verify(flashCardMapper).insert(argThat(c ->
            "kp1".equals(c.getKnowledgePointId())
                && "什么是Java".equals(c.getFront())
                && "Java是一种编程语言".equals(c.getBack())
        ));

        verify(flashCardMapper).insert(argThat(c ->
            "kp3".equals(c.getKnowledgePointId())
                && "OOP特性".equals(c.getFront())
                && "封装、继承、多态".equals(c.getBack())
        ));
    }

    @Test
    void generateForDocumentNoKnowledgePointsReturnsZero() {
        when(knowledgeMapper.findByDocumentId("d1")).thenReturn(List.of());

        int count = flashcardService.generateForDocument("d1");

        assertEquals(0, count);
        verify(flashCardMapper, never()).insert(any());
    }

    // ========== 删除闪卡 ==========

    @Test
    void deleteCardCallsMapper() {
        doNothing().when(flashCardMapper).deleteById("c1");

        flashcardService.delete("c1");

        verify(flashCardMapper).deleteById("c1");
    }
}
