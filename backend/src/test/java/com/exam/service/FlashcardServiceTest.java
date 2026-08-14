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

    private FlashcardService service;

    @BeforeEach
    void setUp() {
        service = new FlashcardService(flashCardMapper, knowledgeMapper);
    }

    private FlashCard makeCard(Double ease, int interval, int reps) {
        FlashCard c = new FlashCard();
        c.setId("c1");
        c.setKnowledgePointId("kp1");
        c.setFront("问题");
        c.setBack("答案");
        c.setEaseFactor(ease);
        c.setInterval(interval);
        c.setRepetitions(reps);
        c.setNextReview(LocalDateTime.now());
        return c;
    }

    // --- SM-2 间隔重复算法核心逻辑 ---

    @Test
    void reviewQuality0ResetsIntervalAndRepetitions() {
        FlashCard card = makeCard(2.5, 10, 5);
        when(flashCardMapper.findById("c1")).thenReturn(card);

        var result = service.review("c1", 0);

        assertEquals(1, result.get("interval"));
        assertEquals(0, result.get("repetitions"));
        // Ease factor: 2.5 + (0.1 - (5-0)*(0.08 + (5-0)*0.02))
        // = 2.5 + (0.1 - 5*0.18) = 2.5 + (0.1 - 0.9) = 2.5 - 0.8 = 1.7
        double expectedEase = 2.5 + (0.1 - 5 * (0.08 + 5 * 0.02));
        assertEquals(expectedEase, (Double) result.get("easeFactor"), 0.001);
        verify(flashCardMapper).updateReview(any());
    }

    @Test
    void reviewQuality2StillResetsProgress() {
        // quality < 3 → reset to reps=0, interval=1
        FlashCard card = makeCard(2.5, 10, 5);
        when(flashCardMapper.findById("c1")).thenReturn(card);

        var result = service.review("c1", 2);

        assertEquals(1, result.get("interval"));
        assertEquals(0, result.get("repetitions"));
    }

    @Test
    void reviewQuality3PassesAdvancesProgress() {
        // quality >= 3 → advance
        // First success (reps=0 → reps=1): interval=1 day
        FlashCard card = makeCard(2.5, 0, 0);
        when(flashCardMapper.findById("c1")).thenReturn(card);

        var result = service.review("c1", 3);

        assertEquals(1, result.get("repetitions"));
        assertEquals(1, result.get("interval"));
    }

    @Test
    void reviewSecondSuccessSets6Days() {
        // reps=1 → reps=2: interval=6 (SM-2 default second interval)
        FlashCard card = makeCard(2.5, 1, 1);
        when(flashCardMapper.findById("c1")).thenReturn(card);

        var result = service.review("c1", 4);

        assertEquals(2, result.get("repetitions"));
        assertEquals(6, result.get("interval"));
    }

    @Test
    void reviewThirdSuccessUsesIntervalTimesEase() {
        // reps=2 → reps=3+ : interval = previous_interval * ease (rounded)
        // interval=6, ease=2.5 → 6*2.5=15 → round=15
        FlashCard card = makeCard(2.5, 6, 2);
        when(flashCardMapper.findById("c1")).thenReturn(card);

        var result = service.review("c1", 4);

        assertEquals(3, result.get("repetitions"));
        assertEquals(15, result.get("interval"));  // 6 * 2.5 = 15
    }

    @Test
    void reviewIntervalRoundsCorrectly() {
        // interval=10, ease=1.3 → 13 (exact)
        FlashCard card = makeCard(1.3, 10, 5);
        when(flashCardMapper.findById("c1")).thenReturn(card);

        var result = service.review("c1", 4);

        assertEquals(13, result.get("interval"));  // 10 * 1.3 = 13
    }

    @Test
    void reviewIntervalWithDecimalRounds() {
        // interval=7, ease=2.5 → 17.5 → round to 18
        FlashCard card = makeCard(2.5, 7, 3);
        when(flashCardMapper.findById("c1")).thenReturn(card);

        var result = service.review("c1", 4);

        assertEquals(18, result.get("interval"));  // Math.round(7 * 2.5) = Math.round(17.5) = 18
    }

    // --- Ease factor 边界：最小值保护 ---

    @Test
    void reviewEaseFactorFlooredAt1300() {
        // Very low quality (0) on already low ease
        FlashCard card = makeCard(1.4, 5, 3);
        when(flashCardMapper.findById("c1")).thenReturn(card);

        // Compute: 1.4 + (0.1 - 5*(0.08 + 5*0.02)) = 1.4 + (0.1 - 0.9) = 1.4 - 0.8 = 0.6
        // This is below 1.3 → should be floored to 1.3
        var result = service.review("c1", 0);

        assertEquals(1.3, (Double) result.get("easeFactor"), 0.001,
                "Ease factor should never drop below 1.3 minimum");
    }

    @Test
    void reviewQuality5BoostsEase() {
        // Quality 5 is best rating, should increase ease
        // delta = 0.1 - 0 = 0.1 (since (5-quality)=0)
        FlashCard card = makeCard(2.5, 10, 5);
        when(flashCardMapper.findById("c1")).thenReturn(card);

        var result = service.review("c1", 5);

        assertEquals(2.6, (Double) result.get("easeFactor"), 0.001);  // 2.5 + 0.1
    }

    @Test
    void reviewCardUpdatesNextReview() {
        FlashCard card = makeCard(2.5, 1, 1);
        when(flashCardMapper.findById("c1")).thenReturn(card);

        service.review("c1", 4);  // interval becomes 6 days

        // The card object should have been updated
        assertNotNull(card.getNextReview());
        assertTrue(card.getNextReview().isAfter(LocalDateTime.now().plusDays(5)));
        assertTrue(card.getNextReview().isBefore(LocalDateTime.now().plusDays(7)));
    }

    @Test
    void reviewCardPersistsEaseFactor() {
        FlashCard card = makeCard(2.5, 6, 2);
        when(flashCardMapper.findById("c1")).thenReturn(card);

        service.review("c1", 4);

        // Verify mapper was called with updated card
        verify(flashCardMapper).updateReview(card);
        assertEquals(2.5, card.getEaseFactor());  // unchanged for q=4
        assertEquals(15, card.getInterval());
    }

    // --- 越界/错误情况 ---

    @Test
    void reviewNonExistentCardThrows() {
        when(flashCardMapper.findById("no-such")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
                () -> service.review("no-such", 3));
        assertEquals("闪卡不存在", ex.getMessage());
    }

    // --- generateForDocument ---

    @Test
    void generateForDocumentCreatesFlashCards() {
        KnowledgePoint kp1 = new KnowledgePoint();
        kp1.setId("kp1");
        kp1.setTitle("知识点1标题");
        kp1.setContent("知识点1内容");

        KnowledgePoint kp2 = new KnowledgePoint();
        kp2.setId("kp2");
        kp2.setTitle("知识点2标题");
        kp2.setContent("知识点2内容");

        when(knowledgeMapper.findByDocumentId("doc1")).thenReturn(List.of(kp1, kp2));

        int count = service.generateForDocument("doc1");

        assertEquals(2, count);
        verify(flashCardMapper, times(2)).insert(any(FlashCard.class));
    }

    @Test
    void generateForDocumentNoKnowledgePointsZeroCreated() {
        when(knowledgeMapper.findByDocumentId("doc-empty")).thenReturn(List.of());

        int count = service.generateForDocument("doc-empty");

        assertEquals(0, count);
        verify(flashCardMapper, never()).insert(any());
    }

    @Test
    void generateForDocumentSetsFrontAndBackFromKnowledgePoint() {
        KnowledgePoint kp = new KnowledgePoint();
        kp.setId("kp42");
        kp.setTitle("封装");
        kp.setContent("将数据和操作数据的方法绑定在一起，对外隐藏实现细节。");

        when(knowledgeMapper.findByDocumentId("doc1")).thenReturn(List.of(kp));

        service.generateForDocument("doc1");

        verify(flashCardMapper).insert(argThat(card ->
                "封装".equals(card.getFront()) &&
                "将数据和操作数据的方法绑定在一起，对外隐藏实现细节。".equals(card.getBack()) &&
                "kp42".equals(card.getKnowledgePointId()) &&
                card.getId() != null
        ));
    }

    // --- delete & getTodayCards ---

    @Test
    void deleteDelegatesToMapper() {
        doNothing().when(flashCardMapper).deleteById("c1");

        service.delete("c1");

        verify(flashCardMapper).deleteById("c1");
    }

    @Test
    void getTodayCardsReturnsCardsAndStats() {
        FlashCard c1 = makeCard(2.5, 1, 0);
        c1.setId("c1");
        FlashCard c2 = makeCard(2.5, 1, 0);
        c2.setId("c2");

        when(flashCardMapper.findDueCards("u1", 20)).thenReturn(List.of(c1, c2));
        when(flashCardMapper.countByUserId("u1")).thenReturn(50);
        when(flashCardMapper.countDue("u1")).thenReturn(5);

        var result = service.getTodayCards("u1");

        @SuppressWarnings("unchecked")
        var cards = (List<FlashCard>) result.get("cards");
        @SuppressWarnings("unchecked")
        var stats = (Map<String, Integer>) result.get("stats");

        assertEquals(2, cards.size());
        assertEquals(50, stats.get("total"));
        assertEquals(5, stats.get("due"));
    }
}
