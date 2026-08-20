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
    private FlashCardMapper flashCardMapper;

    @Mock
    private KnowledgeMapper knowledgeMapper;

    private FlashcardService service;

    @BeforeEach
    void setUp() {
        service = new FlashcardService(flashCardMapper, knowledgeMapper);
    }

    private FlashCard makeCard(double ease, int interval, int reps) {
        FlashCard c = new FlashCard();
        c.setId("fc1");
        c.setEaseFactor(ease);
        c.setInterval(interval);
        c.setRepetitions(reps);
        c.setNextReview(LocalDateTime.now().minusDays(1));
        return c;
    }

    // ==================== getTodayCards ====================

    @Test
    void getTodayCardsReturnsCardsAndStats() {
        FlashCard c = makeCard(2.5, 3, 5);
        when(flashCardMapper.findDueCards("u1", 20)).thenReturn(List.of(c));
        when(flashCardMapper.countByUserId("u1")).thenReturn(10);
        when(flashCardMapper.countDue("u1")).thenReturn(3);

        Map<String, Object> result = service.getTodayCards("u1");

        @SuppressWarnings("unchecked")
        List<FlashCard> cards = (List<FlashCard>) result.get("cards");
        @SuppressWarnings("unchecked")
        Map<String, Integer> stats = (Map<String, Integer>) result.get("stats");

        assertEquals(1, cards.size());
        assertEquals(10, stats.get("total"));
        assertEquals(3, stats.get("due"));
    }

    // ==================== review: SM-2 algorithm ====================

    @Test
    void reviewQuality3FirstRepetitionInterval1() {
        // reps == 0, quality >= 3 → reps becomes 1, interval = 1
        FlashCard c = makeCard(2.5, 0, 0);
        when(flashCardMapper.findById("fc1")).thenReturn(c);

        Map<String, Object> r = service.review("fc1", 3);
        assertEquals(1, r.get("interval"));
        assertEquals(1, r.get("repetitions"));
    }

    @Test
    void reviewQuality4SecondRepetitionInterval6() {
        // reps == 1, quality >= 3 → reps becomes 2, interval = 6
        FlashCard c = makeCard(2.5, 1, 1);
        when(flashCardMapper.findById("fc1")).thenReturn(c);

        Map<String, Object> r = service.review("fc1", 4);
        assertEquals(6, r.get("interval"));
        assertEquals(2, r.get("repetitions"));
    }

    @Test
    void reviewQuality5ThirdRepetitionUsesIntervalTimesEase() {
        // reps == 2 → new reps = 3; ease first updated to 2.6; interval = round(6 * 2.6) = 16
        FlashCard c = makeCard(2.5, 6, 2);
        when(flashCardMapper.findById("fc1")).thenReturn(c);

        Map<String, Object> r = service.review("fc1", 5);
        assertEquals(3, r.get("repetitions"));
        assertEquals(16, r.get("interval"));
    }

    @Test
    void reviewQualityBelow3ResetsRepsAndSetsInterval1() {
        FlashCard c = makeCard(2.5, 10, 10);
        when(flashCardMapper.findById("fc1")).thenReturn(c);

        Map<String, Object> r = service.review("fc1", 2);
        assertEquals(0, r.get("repetitions"));
        assertEquals(1, r.get("interval"));
    }

    @Test
    void reviewQuality0AlsoResets() {
        FlashCard c = makeCard(2.5, 10, 10);
        when(flashCardMapper.findById("fc1")).thenReturn(c);

        Map<String, Object> r = service.review("fc1", 0);
        assertEquals(0, r.get("repetitions"));
        assertEquals(1, r.get("interval"));
    }

    @Test
    void reviewEaseFactorMinimum13() {
        // quality = 0 → ease drop below 1.3 should floor to 1.3
        // formula: ease + 0.1 - (5 - quality)*(0.08 + (5-quality)*0.02)
        // q=0: 5-0=5 → 0.08 + 5*0.02 = 0.18 → 5*0.18=0.9 → ease + 0.1 - 0.9 = ease - 0.8
        // start with 1.5 → result 0.7 → floored to 1.3
        FlashCard c = makeCard(1.5, 1, 1);
        when(flashCardMapper.findById("fc1")).thenReturn(c);

        Map<String, Object> r = service.review("fc1", 0);
        assertEquals(1.3, (Double) r.get("easeFactor"), 0.001);
    }

    @Test
    void reviewQuality5IncreasesEase() {
        // q=5 → 5-5=0 → ease + 0.1 - 0 = ease + 0.1
        FlashCard c = makeCard(2.5, 3, 2);
        when(flashCardMapper.findById("fc1")).thenReturn(c);

        Map<String, Object> r = service.review("fc1", 5);
        assertEquals(2.6, (Double) r.get("easeFactor"), 0.001);
    }

    @Test
    void reviewPersistsUpdatedCard() {
        FlashCard c = makeCard(2.5, 1, 1);
        when(flashCardMapper.findById("fc1")).thenReturn(c);

        service.review("fc1", 4);

        ArgumentCaptor<FlashCard> captor = ArgumentCaptor.forClass(FlashCard.class);
        verify(flashCardMapper).updateReview(captor.capture());
        FlashCard updated = captor.getValue();
        assertEquals("fc1", updated.getId());
        assertEquals(2, updated.getRepetitions());
        assertEquals(6, updated.getInterval());
        assertNotNull(updated.getNextReview());
    }

    @Test
    void reviewNonExistentCardThrows() {
        when(flashCardMapper.findById("missing")).thenReturn(null);
        assertThrows(RuntimeException.class, () -> service.review("missing", 3));
    }

    // ==================== generateForDocument ====================

    @Test
    void generateForDocumentCreatesCardsFromKnowledgePoints() {
        KnowledgePoint kp1 = new KnowledgePoint();
        kp1.setId("kp1");
        kp1.setTitle("What is OOP?");
        kp1.setContent("Object-oriented programming...");

        KnowledgePoint kp2 = new KnowledgePoint();
        kp2.setId("kp2");
        kp2.setTitle("SOLID principles");
        kp2.setContent("Single responsibility, Open/Closed...");

        when(knowledgeMapper.findByDocumentId("doc1")).thenReturn(List.of(kp1, kp2));

        int count = service.generateForDocument("doc1");

        assertEquals(2, count);
        ArgumentCaptor<FlashCard> captor = ArgumentCaptor.forClass(FlashCard.class);
        verify(flashCardMapper, times(2)).insert(captor.capture());
        List<FlashCard> inserted = captor.getAllValues();

        assertEquals("kp1", inserted.get(0).getKnowledgePointId());
        assertEquals("What is OOP?", inserted.get(0).getFront());
        assertEquals("Object-oriented programming...", inserted.get(0).getBack());

        assertEquals("kp2", inserted.get(1).getKnowledgePointId());
        assertEquals("SOLID principles", inserted.get(1).getFront());
        assertEquals("Single responsibility, Open/Closed...", inserted.get(1).getBack());
    }

    @Test
    void generateForDocumentNoKnowledgePointsReturnsZero() {
        when(knowledgeMapper.findByDocumentId("doc-empty")).thenReturn(List.of());
        assertEquals(0, service.generateForDocument("doc-empty"));
        verify(flashCardMapper, never()).insert(any());
    }

    // ==================== delete ====================

    @Test
    void deleteDelegatesToMapper() {
        service.delete("fc1");
        verify(flashCardMapper).deleteById("fc1");
    }
}