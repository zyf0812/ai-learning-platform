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

    private FlashcardService flashcardService;

    @BeforeEach
    void setUp() {
        flashcardService = new FlashcardService(mapper, knowledgeMapper);
    }

    // ===== SM-2 间隔重复算法 review() =====

    private FlashCard freshCard() {
        FlashCard c = new FlashCard();
        c.setId("c1");
        c.setEaseFactor(2.5);        // SM-2 默认初始值
        c.setInterval(0);
        c.setRepetitions(0);
        c.setNextReview(LocalDateTime.now());
        return c;
    }

    @Test
    void reviewQuality5GoodProgressesSchedule() {
        FlashCard c = freshCard();
        when(mapper.findById("c1")).thenReturn(c);
        doNothing().when(mapper).updateReview(any(FlashCard.class));

        // Rep 1: quality=5 >=3 → reps=1, interval=1
        var r1 = flashcardService.review("c1", 5);
        assertEquals(1, r1.get("repetitions"));
        assertEquals(1, r1.get("interval"));
        assertTrue((Double) r1.get("easeFactor") > 2.5); // ease 增加

        // Rep 2: reps=2, interval=6
        when(mapper.findById("c1")).thenReturn(c);
        c.setRepetitions(1);
        c.setInterval(1);
        var r2 = flashcardService.review("c1", 5);
        assertEquals(2, r2.get("repetitions"));
        assertEquals(6, r2.get("interval"));

        // Rep 3+: interval = 上次间隔 * ease
        c.setRepetitions(2);
        c.setInterval(6);
        c.setEaseFactor(2.6);
        when(mapper.findById("c1")).thenReturn(c);
        var r3 = flashcardService.review("c1", 5);
        assertEquals(3, r3.get("repetitions"));
        assertEquals((int) Math.round(6 * 2.6), r3.get("interval"));
    }

    @Test
    void reviewQuality3MinimumCorrect() {
        // quality=3 是及格线（>=3 视为通过）
        FlashCard c = freshCard();
        when(mapper.findById("c1")).thenReturn(c);
        doNothing().when(mapper).updateReview(any(FlashCard.class));

        var r = flashcardService.review("c1", 3);
        assertEquals(1, r.get("repetitions"));
        assertEquals(1, r.get("interval"));
        // ease = 2.5 + (0.1 - 2*(0.08 + 2*0.02)) = 2.5 + (0.1 - 2*0.12) = 2.5 - 0.14 = 2.36
        assertEquals(2.36, (Double) r.get("easeFactor"), 0.001);
    }

    @Test
    void reviewQuality2FailResetsSchedule() {
        // quality < 3 → interval=1, reps=0, ease 降低
        FlashCard c = freshCard();
        c.setRepetitions(5);
        c.setInterval(30);
        c.setEaseFactor(2.5);
        when(mapper.findById("c1")).thenReturn(c);
        doNothing().when(mapper).updateReview(any(FlashCard.class));

        var r = flashcardService.review("c1", 2);
        assertEquals(0, r.get("repetitions"));
        assertEquals(1, r.get("interval"));
        // ease = 2.5 + (0.1 - 3*(0.08 + 3*0.02)) = 2.5 + (0.1 - 3*0.14) = 2.5 - 0.32 = 2.18
        assertEquals(2.18, (Double) r.get("easeFactor"), 0.001);
    }

    @Test
    void reviewQuality0FailHardEaseDrop() {
        FlashCard c = freshCard();
        c.setEaseFactor(2.5);
        when(mapper.findById("c1")).thenReturn(c);
        doNothing().when(mapper).updateReview(any(FlashCard.class));

        var r = flashcardService.review("c1", 0);
        assertEquals(0, r.get("repetitions"));
        assertEquals(1, r.get("interval"));
        // ease = 2.5 + (0.1 - 5*(0.08 + 5*0.02)) = 2.5 + (0.1 - 5*0.18) = 2.5 - 0.8 = 1.7
        assertEquals(1.7, (Double) r.get("easeFactor"), 0.001);
    }

    @Test
    void reviewEaseFactorHasFloorOf1Point3() {
        // 极端连续差评时，ease 不能低于 1.3
        FlashCard c = freshCard();
        c.setEaseFactor(1.35);  // 已经接近底部
        when(mapper.findById("c1")).thenReturn(c);
        doNothing().when(mapper).updateReview(any(FlashCard.class));

        var r = flashcardService.review("c1", 0);  // 最严厉惩罚
        assertTrue((Double) r.get("easeFactor") >= 1.3,
            "easeFactor 不应低于 1.3，实际: " + r.get("easeFactor"));
        assertEquals(1.3, r.get("easeFactor"));
    }

    @Test
    void reviewCardNotFoundThrows() {
        when(mapper.findById("missing")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class, () -> flashcardService.review("missing", 4));
        assertEquals("闪卡不存在", ex.getMessage());
    }

    @Test
    void reviewPersistsChanges() {
        FlashCard c = freshCard();
        when(mapper.findById("c1")).thenReturn(c);

        flashcardService.review("c1", 5);

        verify(mapper).updateReview(c);
        assertNotNull(c.getNextReview());
        // nextReview 应该在今天之后 interval 天
        assertTrue(c.getNextReview().isAfter(LocalDateTime.now().minusSeconds(1)));
    }

    // ===== generateForDocument =====

    @Test
    void generateForDocumentCreatesCards() {
        KnowledgePoint p1 = new KnowledgePoint();
        p1.setId("kp1");
        p1.setTitle("标题1");
        p1.setContent("内容1");
        KnowledgePoint p2 = new KnowledgePoint();
        p2.setId("kp2");
        p2.setTitle("标题2");
        p2.setContent("内容2");

        when(knowledgeMapper.findByDocumentId("doc1")).thenReturn(List.of(p1, p2));
        doNothing().when(mapper).insert(any(FlashCard.class));

        int count = flashcardService.generateForDocument("doc1");
        assertEquals(2, count);
        verify(mapper, times(2)).insert(any(FlashCard.class));
    }

    @Test
    void generateForDocumentNoPointsReturnsZero() {
        when(knowledgeMapper.findByDocumentId("doc-empty")).thenReturn(List.of());
        int count = flashcardService.generateForDocument("doc-empty");
        assertEquals(0, count);
        verify(mapper, never()).insert(any());
    }

    // ===== getTodayCards =====

    @Test
    void getTodayCardsReturnsStats() {
        FlashCard c1 = new FlashCard();
        c1.setId("c1");
        when(mapper.findDueCards("u1", 20)).thenReturn(List.of(c1));
        when(mapper.countByUserId("u1")).thenReturn(10);
        when(mapper.countDue("u1")).thenReturn(3);

        Map<String, Object> result = flashcardService.getTodayCards("u1");
        @SuppressWarnings("unchecked")
        var cards = (List<FlashCard>) result.get("cards");
        @SuppressWarnings("unchecked")
        var stats = (Map<String, Integer>) result.get("stats");

        assertEquals(1, cards.size());
        assertEquals(10, stats.get("total"));
        assertEquals(3, stats.get("due"));
    }

    // ===== delete =====

    @Test
    void deleteCardCallsMapper() {
        doNothing().when(mapper).deleteById("c1");
        flashcardService.delete("c1");
        verify(mapper).deleteById("c1");
    }
}
