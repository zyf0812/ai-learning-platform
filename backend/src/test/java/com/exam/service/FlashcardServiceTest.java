package com.exam.service;

import com.exam.mapper.FlashCardMapper;
import com.exam.mapper.KnowledgeMapper;
import com.exam.model.FlashCard;
import com.exam.model.KnowledgePoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
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

    private FlashCard createCard(String id, double easeFactor, int interval, int repetitions) {
        FlashCard card = new FlashCard();
        card.setId(id);
        card.setEaseFactor(easeFactor);
        card.setInterval(interval);
        card.setRepetitions(repetitions);
        card.setFront("Front content");
        card.setBack("Back content");
        return card;
    }

    @Nested
    @DisplayName("review() tests - spaced repetition algorithm")
    class ReviewTests {

        @Test
        @DisplayName("quality < 3 resets repetitions and sets interval to 1")
        void qualityBelow3Resets() {
            FlashCard card = createCard("fc-1", 2.5, 10, 5);
            when(flashCardMapper.findById("fc-1")).thenReturn(card);

            var result = flashcardService.review("fc-1", 2);

            assertEquals(1, result.get("interval"));
            assertEquals(0, result.get("repetitions"));
        }

        @Test
        @DisplayName("quality >= 3 increments repetitions")
        void qualityAbove3Increments() {
            FlashCard card = createCard("fc-1", 2.5, 10, 3);
            when(flashCardMapper.findById("fc-1")).thenReturn(card);

            var result = flashcardService.review("fc-1", 5);

            assertEquals(4, result.get("repetitions"));
        }

        @Test
        @DisplayName("first successful review (reps=0 -> 1) sets interval to 1")
        void firstSuccessInterval1() {
            FlashCard card = createCard("fc-1", 2.5, 0, 0);
            when(flashCardMapper.findById("fc-1")).thenReturn(card);

            var result = flashcardService.review("fc-1", 4);

            assertEquals(1, result.get("repetitions"));
            assertEquals(1, result.get("interval"));
        }

        @Test
        @DisplayName("second successful review (reps=1 -> 2) sets interval to 6")
        void secondSuccessInterval6() {
            FlashCard card = createCard("fc-1", 2.5, 1, 1);
            when(flashCardMapper.findById("fc-1")).thenReturn(card);

            var result = flashcardService.review("fc-1", 4);

            assertEquals(2, result.get("repetitions"));
            assertEquals(6, result.get("interval"));
        }

        @Test
        @DisplayName("third successful review uses ease factor calculation")
        void thirdSuccessUsesEaseFactor() {
            FlashCard card = createCard("fc-1", 2.5, 6, 2);
            when(flashCardMapper.findById("fc-1")).thenReturn(card);

            var result = flashcardService.review("fc-1", 4);

            assertEquals(3, result.get("repetitions"));
            int expectedInterval = (int) Math.round(6 * 2.5);
            assertEquals(expectedInterval, result.get("interval"));
        }

        @Test
        @DisplayName("ease factor decreases with poor quality")
        void easeFactorDecreasesWithPoorQuality() {
            FlashCard card = createCard("fc-1", 2.5, 10, 5);
            when(flashCardMapper.findById("fc-1")).thenReturn(card);

            var result = flashcardService.review("fc-1", 1);

            assertTrue(((Number) result.get("easeFactor")).doubleValue() < 2.5);
        }

        @Test
        @DisplayName("ease factor increases with good quality")
        void easeFactorIncreasesWithGoodQuality() {
            FlashCard card = createCard("fc-1", 2.5, 10, 5);
            when(flashCardMapper.findById("fc-1")).thenReturn(card);

            var result = flashcardService.review("fc-1", 5);

            assertTrue(((Number) result.get("easeFactor")).doubleValue() > 2.5);
        }

        @Test
        @DisplayName("ease factor has minimum of 1.3")
        void easeFactorMinimumIs13() {
            FlashCard card = createCard("fc-1", 1.3, 10, 5);
            when(flashCardMapper.findById("fc-1")).thenReturn(card);

            var result = flashcardService.review("fc-1", 1);

            double ease = ((Number) result.get("easeFactor")).doubleValue();
            assertTrue(ease >= 1.3, "Ease factor should not be below 1.3, got: " + ease);
        }

        @Test
        @DisplayName("quality of 3 is the threshold boundary between reset and increment")
        void quality3IsThreshold() {
            FlashCard card = createCard("fc-1", 2.5, 10, 5);
            when(flashCardMapper.findById("fc-1")).thenReturn(card);

            var result = flashcardService.review("fc-1", 3);

            assertEquals(6, result.get("repetitions"));
            assertNotEquals(1, result.get("interval"));
        }

        @Test
        @DisplayName("quality of 2 is reset boundary")
        void quality2ResetBoundary() {
            FlashCard card = createCard("fc-1", 2.5, 10, 5);
            when(flashCardMapper.findById("fc-1")).thenReturn(card);

            var result = flashcardService.review("fc-1", 2);

            assertEquals(0, result.get("repetitions"));
            assertEquals(1, result.get("interval"));
        }

        @Test
        @DisplayName("throws when card not found")
        void throwsWhenCardNotFound() {
            when(flashCardMapper.findById("unknown")).thenReturn(null);

            assertThrows(RuntimeException.class,
                () -> flashcardService.review("unknown", 5));
        }

        @Test
        @DisplayName("nextReview is set based on interval")
        void nextReviewSetFromInterval() {
            FlashCard card = createCard("fc-1", 2.5, 10, 5);
            when(flashCardMapper.findById("fc-1")).thenReturn(card);

            flashcardService.review("fc-1", 5);

            ArgumentCaptor<FlashCard> captor = ArgumentCaptor.forClass(FlashCard.class);
            verify(flashCardMapper).updateReview(captor.capture());

            FlashCard updated = captor.getValue();
            assertNotNull(updated.getNextReview());
        }

        @Test
        @DisplayName("quality 5 gives maximum ease factor increase")
        void quality5MaxEase() {
            FlashCard card = createCard("fc-1", 2.5, 10, 5);
            when(flashCardMapper.findById("fc-1")).thenReturn(card);

            var r1 = flashcardService.review("fc-1", 5);
            double ease5 = ((Number) r1.get("easeFactor")).doubleValue();

            FlashCard card2 = createCard("fc-1", 2.5, 10, 5);
            when(flashCardMapper.findById("fc-1")).thenReturn(card2);
            var r2 = flashcardService.review("fc-1", 4);
            double ease4 = ((Number) r2.get("easeFactor")).doubleValue();

            assertTrue(ease5 > ease4, "Quality 5 should produce higher ease factor than quality 4");
        }

        @Test
        @DisplayName("quality 1 gives minimum ease factor (capped at 1.3)")
        void quality1MinEase() {
            FlashCard card = createCard("fc-1", 1.3, 10, 5);
            when(flashCardMapper.findById("fc-1")).thenReturn(card);

            var result = flashcardService.review("fc-1", 1);
            double ease = ((Number) result.get("easeFactor")).doubleValue();

            assertTrue(ease <= 1.3, "Quality 1 with base 1.3 should stay at 1.3");
        }
    }

    @Nested
    @DisplayName("getTodayCards tests")
    class GetTodayCardsTests {

        @Test
        @DisplayName("returns cards with stats")
        void returnsCardsWithStats() {
            FlashCard card = createCard("fc-1", 2.5, 1, 0);
            when(flashCardMapper.findDueCards("user-1", 20)).thenReturn(List.of(card));
            when(flashCardMapper.countByUserId("user-1")).thenReturn(10);
            when(flashCardMapper.countDue("user-1")).thenReturn(3);

            var result = flashcardService.getTodayCards("user-1");

            assertNotNull(result.get("cards"));
            assertEquals(1, ((List<?>) result.get("cards")).size());

            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> stats = (java.util.Map<String, Object>) result.get("stats");
            assertEquals(10, stats.get("total"));
            assertEquals(3, stats.get("due"));
        }

        @Test
        @DisplayName("handles empty card list")
        void handlesEmptyList() {
            when(flashCardMapper.findDueCards("user-1", 20)).thenReturn(new ArrayList<>());
            when(flashCardMapper.countByUserId("user-1")).thenReturn(0);
            when(flashCardMapper.countDue("user-1")).thenReturn(0);

            var result = flashcardService.getTodayCards("user-1");

            assertTrue(((List<?>) result.get("cards")).isEmpty());
        }
    }

    @Nested
    @DisplayName("generateForDocument tests")
    class GenerateForDocumentTests {

        @Test
        @DisplayName("generates flashcards for knowledge points")
        void generatesCardsForKnowledgePoints() {
            KnowledgePoint kp1 = new KnowledgePoint();
            kp1.setId("kp-1");
            kp1.setTitle("Title 1");
            kp1.setContent("Content 1");

            KnowledgePoint kp2 = new KnowledgePoint();
            kp2.setId("kp-2");
            kp2.setTitle("Title 2");
            kp2.setContent("Content 2");

            when(knowledgeMapper.findByDocumentId("doc-1")).thenReturn(List.of(kp1, kp2));

            int count = flashcardService.generateForDocument("doc-1");

            assertEquals(2, count);
            verify(flashCardMapper, times(2)).insert(any(FlashCard.class));
        }

        @Test
        @DisplayName("returns 0 when no knowledge points")
        void returnsZeroForNoKnowledgePoints() {
            when(knowledgeMapper.findByDocumentId("doc-1")).thenReturn(new ArrayList<>());

            int count = flashcardService.generateForDocument("doc-1");

            assertEquals(0, count);
            verify(flashCardMapper, never()).insert(any(FlashCard.class));
        }

        @Test
        @DisplayName("generated cards have correct front/back from knowledge points")
        void generatedCardsHaveCorrectFrontBack() {
            KnowledgePoint kp = new KnowledgePoint();
            kp.setId("kp-1");
            kp.setTitle("Question Title");
            kp.setContent("Answer Content");

            when(knowledgeMapper.findByDocumentId("doc-1")).thenReturn(List.of(kp));

            flashcardService.generateForDocument("doc-1");

            ArgumentCaptor<FlashCard> captor = ArgumentCaptor.forClass(FlashCard.class);
            verify(flashCardMapper).insert(captor.capture());

            FlashCard inserted = captor.getValue();
            assertEquals("Question Title", inserted.getFront());
            assertEquals("Answer Content", inserted.getBack());
            assertEquals("kp-1", inserted.getKnowledgePointId());
        }
    }

    @Nested
    @DisplayName("delete() tests")
    class DeleteTests {

        @Test
        @DisplayName("deletes flashcard")
        void deletesFlashcard() {
            assertDoesNotThrow(() -> flashcardService.delete("fc-1"));
            verify(flashCardMapper).deleteById("fc-1");
        }
    }
}