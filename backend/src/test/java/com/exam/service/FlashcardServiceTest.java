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
        c.setId("c1");
        c.setEaseFactor(2.5);  // SM-2 初始默认值
        c.setInterval(0);
        c.setRepetitions(0);
        c.setNextReview(LocalDateTime.now());
        return c;
    }

    // ==================== review: quality < 3 重置 reps ====================

    @Test
    void reviewQualityZeroResetsRepetitionsAndInterval() {
        FlashCard c = freshCard();
        c.setRepetitions(5);
        c.setInterval(30);
        when(flashCardMapper.findById("c1")).thenReturn(c);

        Map<String, Object> res = flashcardService.review("c1", 0);

        assertEquals(0, res.get("repetitions"));
        assertEquals(1, res.get("interval"));
        // ease 降低：2.5 + (0.1 - (5-0)*(0.08+5*0.02)) = 2.5 + (0.1 - 5*0.18) = 2.5 + (0.1-0.9) = 2.5-0.8 = 1.7
        // 但 quality 0 时 ease 仍然计算，只是 interval=1 reps=0
        assertEquals(1.7, (Double) res.get("easeFactor"), 0.001);
        verify(flashCardMapper).updateReview(c);
    }

    @Test
    void reviewQualityTwoStillFailsResetsReps() {
        FlashCard c = freshCard();
        c.setRepetitions(3);
        c.setInterval(10);
        c.setEaseFactor(2.5);
        when(flashCardMapper.findById("c1")).thenReturn(c);

        // quality 2 < 3 -> 重置
        Map<String, Object> res = flashcardService.review("c1", 2);
        assertEquals(0, res.get("repetitions"));
        assertEquals(1, res.get("interval"));
        // ease = 2.5 + 0.1 - (5-2)*(0.08+3*0.02) = 2.5+0.1 - 3*0.14 = 2.6 - 0.42 = 2.18
        assertEquals(2.18, (Double) res.get("easeFactor"), 0.001);
    }

    // ==================== review: quality >= 3 正常晋升 ====================

    @Test
    void reviewReps0ToReps1Interval1() {
        FlashCard c = freshCard(); // reps=0
        when(flashCardMapper.findById("c1")).thenReturn(c);

        Map<String, Object> res = flashcardService.review("c1", 4); // quality=4 -> good

        assertEquals(1, res.get("repetitions"));
        assertEquals(1, res.get("interval"));
        // ease: 2.5 + 0.1 - (5-4)*(0.08 + 1*0.02) = 2.6 - 1*0.1 = 2.5
        assertEquals(2.5, (Double) res.get("easeFactor"), 0.001);
    }

    @Test
    void reviewReps1ToReps2Interval6() {
        FlashCard c = freshCard();
        c.setRepetitions(1);
        c.setInterval(1);
        when(flashCardMapper.findById("c1")).thenReturn(c);

        Map<String, Object> res = flashcardService.review("c1", 4);

        assertEquals(2, res.get("repetitions"));
        assertEquals(6, res.get("interval"));
    }

    @Test
    void reviewReps2PlusIntervalScaledByEase() {
        FlashCard c = freshCard();
        c.setRepetitions(2);
        c.setInterval(6);
        c.setEaseFactor(2.5);
        when(flashCardMapper.findById("c1")).thenReturn(c);

        Map<String, Object> res = flashcardService.review("c1", 5); // quality 5 max

        assertEquals(3, res.get("repetitions"));
        // reps>=2, interval = round(prev_interval * ease)
        // ease 5 时：2.5 + 0.1 - 0 = 2.6
        // interval = round(6 * 2.6) = round(15.6) = 16
        assertEquals(16, res.get("interval"));
        assertEquals(2.6, (Double) res.get("easeFactor"), 0.001);
    }

    // ==================== ease 最小值 1.3 钳制 ====================

    @Test
    void reviewEaseFactorFlooredAt1300() {
        FlashCard c = freshCard();
        c.setEaseFactor(1.35); // 已经接近最低
        c.setRepetitions(5);
        when(flashCardMapper.findById("c1")).thenReturn(c);

        // quality 0 再次大幅降低 ease
        // ease = 1.35 + 0.1 - 5*0.18 = 1.45 - 0.9 = 0.55 -> 钳制到 1.3
        Map<String, Object> res = flashcardService.review("c1", 0);
        assertEquals(1.3, (Double) res.get("easeFactor"), 0.0001);
    }

    @Test
    void reviewLowQualityMultipleTimesEventuallyHitsFloor() {
        FlashCard c = freshCard();
        c.setEaseFactor(2.5);
        c.setRepetitions(10);
        when(flashCardMapper.findById("c1")).thenReturn(c);

        // 每次 quality=1：ease = ease + 0.1 - 4*(0.08+4*0.02) = ease + 0.1 - 4*0.16 = ease + 0.1 - 0.64 = ease - 0.54
        // 2.5 - 0.54 = 1.96 (未到1.3)
        Map<String, Object> r1 = flashcardService.review("c1", 1);
        assertEquals(1.96, (Double) r1.get("easeFactor"), 0.001);

        // 更新 mock 的 ease 到下一轮
        c.setEaseFactor(1.96);
        // 1.96 - 0.54 = 1.42 (仍高于 1.3)
        Map<String, Object> r2 = flashcardService.review("c1", 1);
        assertEquals(1.42, (Double) r2.get("easeFactor"), 0.001);

        c.setEaseFactor(1.42);
        // 1.42 - 0.54 = 0.88 -> 钳制到 1.3
        Map<String, Object> r3 = flashcardService.review("c1", 1);
        assertEquals(1.3, (Double) r3.get("easeFactor"), 0.001);
    }

    // ==================== nextReview 更新正确 ====================

    @Test
    void reviewUpdatesNextReviewByInterval() {
        FlashCard c = freshCard();
        c.setRepetitions(1);
        when(flashCardMapper.findById("c1")).thenReturn(c);

        LocalDateTime before = LocalDateTime.now().minusSeconds(1);
        flashcardService.review("c1", 4); // reps 1->2, interval=6
        LocalDateTime after = LocalDateTime.now().plusSeconds(1);

        // nextReview 应该是 6 天后
        LocalDateTime expectedMin = before.plusDays(6);
        LocalDateTime expectedMax = after.plusDays(6);
        assertTrue(!c.getNextReview().isBefore(expectedMin) && !c.getNextReview().isAfter(expectedMax),
            "nextReview should be ~6 days after now, got: " + c.getNextReview());
    }

    // ==================== 不存在的闪卡 review 抛异常 ====================

    @Test
    void reviewNonexistentCardThrows() {
        when(flashCardMapper.findById("nope")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class, () -> flashcardService.review("nope", 3));
        assertEquals("闪卡不存在", ex.getMessage());
    }

    // ==================== generateForDocument: 根据知识点创建闪卡 ====================

    @Test
    void generateForDocumentCreatesCardPerKnowledgePoint() {
        KnowledgePoint k1 = new KnowledgePoint();
        k1.setId("k1");
        k1.setTitle("什么是 Spring");
        k1.setContent("Spring 是一个 IoC/AOP 框架");

        KnowledgePoint k2 = new KnowledgePoint();
        k2.setId("k2");
        k2.setTitle("IoC 是什么");
        k2.setContent("控制反转，将对象创建交给容器");

        when(knowledgeMapper.findByDocumentId("d1")).thenReturn(List.of(k1, k2));

        int count = flashcardService.generateForDocument("d1");
        assertEquals(2, count);
        verify(flashCardMapper, times(2)).insert(any(FlashCard.class));
    }

    @Test
    void generateForDocumentNoKnowledgePointsReturnsZero() {
        when(knowledgeMapper.findByDocumentId("empty")).thenReturn(List.of());
        assertEquals(0, flashcardService.generateForDocument("empty"));
        verify(flashCardMapper, never()).insert(any());
    }

    @Test
    void generateForDocumentCardFrontBackMapCorrectly() {
        KnowledgePoint kp = new KnowledgePoint();
        kp.setId("kp1");
        kp.setTitle("问：HTTP 状态码 301？");
        kp.setContent("答：永久重定向");

        when(knowledgeMapper.findByDocumentId("d1")).thenReturn(List.of(kp));

        flashcardService.generateForDocument("d1");

        verify(flashCardMapper).insert(argThat(card ->
            "问：HTTP 状态码 301？".equals(card.getFront())
            && "答：永久重定向".equals(card.getBack())
            && "kp1".equals(card.getKnowledgePointId())
            && card.getId() != null
        ));
    }

    // ==================== getTodayCards: 统计返回正确结构 ====================

    @Test
    void getTodayCardsReturnsCardsAndStats() {
        FlashCard c = freshCard();
        when(flashCardMapper.findDueCards("u1", 20)).thenReturn(List.of(c));
        when(flashCardMapper.countByUserId("u1")).thenReturn(100);
        when(flashCardMapper.countDue("u1")).thenReturn(15);

        Map<String, Object> res = flashcardService.getTodayCards("u1");

        @SuppressWarnings("unchecked")
        List<FlashCard> cards = (List<FlashCard>) res.get("cards");
        assertEquals(1, cards.size());

        @SuppressWarnings("unchecked")
        Map<String, Integer> stats = (Map<String, Integer>) res.get("stats");
        assertEquals(100, stats.get("total"));
        assertEquals(15, stats.get("due"));
    }

    // ==================== delete: 透传删除 ====================

    @Test
    void deleteDelegatesToMapper() {
        flashcardService.delete("c1");
        verify(flashCardMapper).deleteById("c1");
    }
}
