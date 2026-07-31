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

    private FlashCard makeCard(double ease, int interval, int reps) {
        FlashCard c = new FlashCard();
        c.setId("card1");
        c.setEaseFactor(ease);
        c.setInterval(interval);
        c.setRepetitions(reps);
        c.setNextReview(LocalDateTime.now());
        return c;
    }

    // ---------- SM-2: ease factor ----------

    @Test
    void reviewQuality5IncreasesEase() {
        // Default ease 2.5, interval 1, reps 1, quality 5 (best)
        // ease = 2.5 + (0.1 - 0*(0.08+0*0.02)) = 2.5 + 0.1 = 2.6
        FlashCard c = makeCard(2.5, 1, 1);
        when(flashCardMapper.findById("card1")).thenReturn(c);

        var result = flashcardService.review("card1", 5);

        assertEquals(2.6, (Double) result.get("easeFactor"), 0.0001);
    }

    @Test
    void reviewQuality4SlightlyIncreasesEase() {
        // ease = 2.5 + (0.1 - 1*(0.08 + 1*0.02)) = 2.5 + (0.1 - 0.1) = 2.5
        FlashCard c = makeCard(2.5, 1, 1);
        when(flashCardMapper.findById("card1")).thenReturn(c);

        var result = flashcardService.review("card1", 4);
        assertEquals(2.5, (Double) result.get("easeFactor"), 0.0001);
    }

    @Test
    void reviewQuality3DecreasesEase() {
        // ease = 2.5 + (0.1 - 2*(0.08 + 2*0.02)) = 2.5 + (0.1 - 2*0.12) = 2.5 + (0.1 - 0.24) = 2.36
        FlashCard c = makeCard(2.5, 10, 3);
        when(flashCardMapper.findById("card1")).thenReturn(c);

        var result = flashcardService.review("card1", 3);
        assertEquals(2.36, (Double) result.get("easeFactor"), 0.0001);
    }

    @Test
    void reviewLowQualityFloorsEaseAt1_3() {
        // With very low quality many times, ease would drop below 1.3 -> floor it
        // Start at 1.35, quality 0: 1.35 + (0.1 - 5*(0.08 + 5*0.02))
        // = 1.35 + (0.1 - 5*0.18) = 1.35 + (0.1 - 0.9) = 1.35 - 0.8 = 0.55 -> floored to 1.3
        FlashCard c = makeCard(1.35, 1, 1);
        when(flashCardMapper.findById("card1")).thenReturn(c);

        var result = flashcardService.review("card1", 0);
        assertEquals(1.3, (Double) result.get("easeFactor"), 0.0001);
    }

    // ---------- SM-2: interval and repetitions ----------

    @Test
    void qualityBelow3ResetsRepsAndSetsInterval1() {
        FlashCard c = makeCard(2.5, 30, 10);
        when(flashCardMapper.findById("card1")).thenReturn(c);

        var result = flashcardService.review("card1", 2);

        assertEquals(0, result.get("repetitions"));
        assertEquals(1, result.get("interval"));
    }

    @Test
    void firstGoodReviewSetsInterval1() {
        // reps 0 -> after success becomes 1 -> interval = 1
        FlashCard c = makeCard(2.5, 0, 0);
        when(flashCardMapper.findById("card1")).thenReturn(c);

        var result = flashcardService.review("card1", 4);
        assertEquals(1, result.get("repetitions"));
        assertEquals(1, result.get("interval"));
    }

    @Test
    void secondGoodReviewSetsInterval6() {
        // reps was 1 -> after success becomes 2 -> interval = 6
        FlashCard c = makeCard(2.5, 1, 1);
        when(flashCardMapper.findById("card1")).thenReturn(c);

        var result = flashcardService.review("card1", 4);
        assertEquals(2, result.get("repetitions"));
        assertEquals(6, result.get("interval"));
    }

    @Test
    void thirdAndBeyondUsesIntervalTimesEase() {
        // reps was 2 -> after success becomes 3 -> interval = prevInterval * ease
        // prev=6, ease=2.5 -> 6*2.5 = 15
        FlashCard c = makeCard(2.5, 6, 2);
        when(flashCardMapper.findById("card1")).thenReturn(c);

        var result = flashcardService.review("card1", 4);
        assertEquals(3, result.get("repetitions"));
        assertEquals(15, result.get("interval"));
    }

    @Test
    void intervalUsesRoundedInteger() {
        // interval * ease = 10 * 2.3 = 23.0 -> round -> 23
        FlashCard c = makeCard(2.3, 10, 3);
        when(flashCardMapper.findById("card1")).thenReturn(c);

        var result = flashcardService.review("card1", 4);
        assertEquals(23, result.get("interval"));
    }

    // ---------- Persistence ----------

    @Test
    void reviewUpdatesCardFieldsAndSaves() {
        FlashCard c = makeCard(2.5, 6, 2);
        when(flashCardMapper.findById("card1")).thenReturn(c);

        flashcardService.review("card1", 4);

        ArgumentCaptor<FlashCard> captor = ArgumentCaptor.forClass(FlashCard.class);
        verify(flashCardMapper).updateReview(captor.capture());

        FlashCard saved = captor.getValue();
        assertEquals(2.5, saved.getEaseFactor());
        assertEquals(15, saved.getInterval());
        assertEquals(3, saved.getRepetitions());
        assertNotNull(saved.getNextReview());
        assertTrue(saved.getNextReview().isAfter(LocalDateTime.now().minusMinutes(1)));
    }

    // ---------- Not found ----------

    @Test
    void reviewNonexistentCardThrows() {
        when(flashCardMapper.findById("nope")).thenReturn(null);
        assertThrows(RuntimeException.class, () -> flashcardService.review("nope", 4));
    }

    // ---------- getTodayCards ----------

    @Test
    void getTodayCardsReturnsCardsAndStats() {
        FlashCard c = new FlashCard();
        c.setId("c1");
        when(flashCardMapper.findDueCards("user1", 20)).thenReturn(List.of(c));
        when(flashCardMapper.countByUserId("user1")).thenReturn(100);
        when(flashCardMapper.countDue("user1")).thenReturn(15);

        var result = flashcardService.getTodayCards("user1");

        @SuppressWarnings("unchecked")
        var cards = (List<FlashCard>) result.get("cards");
        assertEquals(1, cards.size());
        @SuppressWarnings("unchecked")
        var stats = (java.util.Map<String, Object>) result.get("stats");
        assertEquals(100, stats.get("total"));
        assertEquals(15, stats.get("due"));
    }

    // ---------- generateForDocument ----------

    @Test
    void generateForDocumentCreatesFlashcardsFromKnowledgePoints() {
        KnowledgePoint p1 = new KnowledgePoint();
        p1.setId("kp1");
        p1.setTitle("标题1");
        p1.setContent("内容1");
        KnowledgePoint p2 = new KnowledgePoint();
        p2.setId("kp2");
        p2.setTitle("标题2");
        p2.setContent("内容2");

        when(knowledgeMapper.findByDocumentId("doc1")).thenReturn(List.of(p1, p2));

        int count = flashcardService.generateForDocument("doc1");
        assertEquals(2, count);

        ArgumentCaptor<FlashCard> captor = ArgumentCaptor.forClass(FlashCard.class);
        verify(flashCardMapper, times(2)).insert(captor.capture());

        var all = captor.getAllValues();
        assertEquals("kp1", all.get(0).getKnowledgePointId());
        assertEquals("标题1", all.get(0).getFront());
        assertEquals("内容1", all.get(0).getBack());
        assertNotNull(all.get(0).getId());

        assertEquals("kp2", all.get(1).getKnowledgePointId());
        assertEquals("标题2", all.get(1).getFront());
        assertEquals("内容2", all.get(1).getBack());
    }

    @Test
    void generateForDocumentNoKnowledgePointsReturnsZero() {
        when(knowledgeMapper.findByDocumentId("empty")).thenReturn(List.of());
        assertEquals(0, flashcardService.generateForDocument("empty"));
        verify(flashCardMapper, never()).insert(any());
    }

    // ---------- delete ----------

    @Test
    void deleteCallsMapperDeleteById() {
        flashcardService.delete("card-to-delete");
        verify(flashCardMapper).deleteById("card-to-delete");
    }
}
