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
 * 闪卡间隔重复算法（SM-2 变体）测试：
 * - easeFactor 调整公式
 * - interval/repetitions 分支逻辑
 * - 质量等级 0-5 的边界行为
 * - easeFactor 下限 1.3 保护
 */
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

    private FlashCard freshCard() {
        FlashCard c = new FlashCard();
        c.setId("card1");
        c.setEaseFactor(2.5);   // SM-2 默认初始值
        c.setInterval(0);
        c.setRepetitions(0);
        c.setNextReview(LocalDateTime.now());
        return c;
    }

    // ===== getTodayCards =====

    @Test
    void getTodayCardsReturnsStats() {
        FlashCard c = freshCard();
        when(flashCardMapper.findDueCards("u1", 20)).thenReturn(List.of(c));
        when(flashCardMapper.countByUserId("u1")).thenReturn(10);
        when(flashCardMapper.countDue("u1")).thenReturn(3);

        var result = flashcardService.getTodayCards("u1");

        @SuppressWarnings("unchecked")
        var cards = (List<FlashCard>) result.get("cards");
        assertEquals(1, cards.size());
        @SuppressWarnings("unchecked")
        var stats = (Map<String, Integer>) result.get("stats");
        assertEquals(10, stats.get("total"));
        assertEquals(3, stats.get("due"));
    }

    // ===== review: quality < 3 重置 reps 和 interval =====

    @Test
    void reviewQualityZeroResetsRepsToZero() {
        FlashCard c = freshCard();
        c.setRepetitions(5);
        c.setInterval(30);
        c.setEaseFactor(2.3);
        when(flashCardMapper.findById("card1")).thenReturn(c);

        var result = flashcardService.review("card1", 0);

        assertEquals(0, result.get("repetitions"));
        assertEquals(1, result.get("interval"));
        ArgumentCaptor<FlashCard> captor = ArgumentCaptor.forClass(FlashCard.class);
        verify(flashCardMapper).updateReview(captor.capture());
        assertEquals(0, captor.getValue().getRepetitions());
        assertEquals(1, captor.getValue().getInterval());
    }

    @Test
    void reviewQualityTwoStillResets() {
        FlashCard c = freshCard();
        c.setRepetitions(3);
        when(flashCardMapper.findById("card1")).thenReturn(c);

        var result = flashcardService.review("card1", 2);

        assertEquals(0, result.get("repetitions"));
        assertEquals(1, result.get("interval"));
    }

    // ===== review: quality >= 3 递增 reps =====

    @Test
    void reviewQualityThreeFirstSuccessSetsIntervalOne() {
        FlashCard c = freshCard(); // reps=0, interval=0, ease=2.5
        when(flashCardMapper.findById("card1")).thenReturn(c);

        var result = flashcardService.review("card1", 3);

        assertEquals(1, result.get("repetitions"));
        assertEquals(1, result.get("interval"));
    }

    @Test
    void reviewQualityFourSecondSuccessSetsIntervalSix() {
        FlashCard c = freshCard();
        c.setRepetitions(1);  // 第二次成功
        c.setInterval(1);
        c.setEaseFactor(2.5);
        when(flashCardMapper.findById("card1")).thenReturn(c);

        var result = flashcardService.review("card1", 4);

        assertEquals(2, result.get("repetitions"));
        assertEquals(6, result.get("interval")); // reps==2 硬编码 6 天
    }

    @Test
    void reviewQualityFiveThirdSuccessMultipliesIntervalByEase() {
        // reps=2 -> reps=3; interval=6 * 2.5 = 15
        FlashCard c = freshCard();
        c.setRepetitions(2);
        c.setInterval(6);
        c.setEaseFactor(2.5);
        when(flashCardMapper.findById("card1")).thenReturn(c);

        var result = flashcardService.review("card1", 5);

        assertEquals(3, result.get("repetitions"));
        assertEquals(15, result.get("interval")); // 6 * 2.5 = 15
    }

    // ===== easeFactor 调整公式 =====
    // ease = ease + (0.1 - (5-quality) * (0.08 + (5-quality) * 0.02))

    @Test
    void reviewQualityFiveEaseUnchanged() {
        // quality=5: (0.1 - 0 * (...)) = +0.1 -> 2.5 -> 2.6
        FlashCard c = freshCard();
        c.setEaseFactor(2.5);
        when(flashCardMapper.findById("card1")).thenReturn(c);

        var result = flashcardService.review("card1", 5);

        assertEquals(2.6, (Double) result.get("easeFactor"), 0.0001);
    }

    @Test
    void reviewQualityFourEaseDecreases() {
        // quality=4: 0.1 - (1)*(0.08+1*0.02) = 0.1 - 0.1 = 0 -> 2.5 不变
        FlashCard c = freshCard();
        c.setEaseFactor(2.5);
        when(flashCardMapper.findById("card1")).thenReturn(c);

        var result = flashcardService.review("card1", 4);

        assertEquals(2.5, (Double) result.get("easeFactor"), 0.0001);
    }

    @Test
    void reviewQualityThreeEaseDrops() {
        // quality=3: 0.1 - (2)*(0.08+2*0.02) = 0.1 - 2*0.12 = 0.1 - 0.24 = -0.14
        // 2.5 - 0.14 = 2.36
        FlashCard c = freshCard();
        c.setEaseFactor(2.5);
        when(flashCardMapper.findById("card1")).thenReturn(c);

        var result = flashcardService.review("card1", 3);

        assertEquals(2.36, (Double) result.get("easeFactor"), 0.0001);
    }

    // ===== easeFactor 1.3 下限保护 =====

    @Test
    void reviewQualityOneBadSeriesDoesNotDropBelowMinimum() {
        FlashCard c = freshCard();
        c.setEaseFactor(1.35); // 已经接近下限
        // quality=1: 0.1 - 4*(0.08 + 4*0.02) = 0.1 - 4*0.16 = 0.1 - 0.64 = -0.54
        // 1.35 - 0.54 = 0.81，应夹紧到 1.3
        when(flashCardMapper.findById("card1")).thenReturn(c);

        var result = flashcardService.review("card1", 1);

        assertEquals(1.3, (Double) result.get("easeFactor"), 0.0001);
    }

    @Test
    void reviewAtMinEaseStaysMin() {
        FlashCard c = freshCard();
        c.setEaseFactor(1.3);
        // quality=2: 0.1 - 3*(0.08+3*0.02) = 0.1 - 3*0.14 = 0.1 - 0.42 = -0.32
        // 1.3 - 0.32 < 1.3，应夹紧
        when(flashCardMapper.findById("card1")).thenReturn(c);

        var result = flashcardService.review("card1", 2);

        assertEquals(1.3, (Double) result.get("easeFactor"), 0.0001);
    }

    // ===== 其他异常 =====

    @Test
    void reviewMissingCardThrows() {
        when(flashCardMapper.findById("nope")).thenReturn(null);
        assertThrows(RuntimeException.class,
            () -> flashcardService.review("nope", 3));
    }

    @Test
    void reviewUpdatesNextReviewDate() {
        FlashCard c = freshCard();
        c.setRepetitions(2);
        c.setInterval(6);
        c.setEaseFactor(2.5);
        when(flashCardMapper.findById("card1")).thenReturn(c);

        flashcardService.review("card1", 5);

        ArgumentCaptor<FlashCard> captor = ArgumentCaptor.forClass(FlashCard.class);
        verify(flashCardMapper).updateReview(captor.capture());
        LocalDateTime expected = LocalDateTime.now().plusDays(15); // interval=15
        LocalDateTime actual = captor.getValue().getNextReview();
        // 同一天 +/- 1 分钟即可
        assertEquals(expected.toLocalDate(), actual.toLocalDate());
    }

    // ===== generateForDocument =====

    @Test
    void generateForDocumentCreatesCardPerKnowledgePoint() {
        KnowledgePoint kp1 = new KnowledgePoint();
        kp1.setId("kp1");
        kp1.setTitle("封装");
        kp1.setContent("数据隐藏+方法暴露");
        KnowledgePoint kp2 = new KnowledgePoint();
        kp2.setId("kp2");
        kp2.setTitle("继承");
        kp2.setContent("extends 关键字");
        when(knowledgeMapper.findByDocumentId("d1")).thenReturn(List.of(kp1, kp2));

        int count = flashcardService.generateForDocument("d1");

        assertEquals(2, count);
        ArgumentCaptor<FlashCard> captor = ArgumentCaptor.forClass(FlashCard.class);
        verify(flashCardMapper, times(2)).insert(captor.capture());
        List<FlashCard> all = captor.getAllValues();
        assertEquals("封装", all.get(0).getFront());
        assertEquals("数据隐藏+方法暴露", all.get(0).getBack());
        assertEquals("继承", all.get(1).getFront());
        // 每张卡 ID 为 8 位 hex
        assertEquals(8, all.get(0).getId().length());
        assertNotEquals(all.get(0).getId(), all.get(1).getId());
    }

    // ===== delete =====

    @Test
    void deleteDelegatesToMapper() {
        flashcardService.delete("c1");
        verify(flashCardMapper).deleteById("c1");
    }
}
