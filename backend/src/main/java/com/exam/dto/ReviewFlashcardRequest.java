package com.exam.dto;

public class ReviewFlashcardRequest {
    private String cardId;
    private int quality;

    public String getCardId() { return cardId; }
    public void setCardId(String cardId) { this.cardId = cardId; }
    public int getQuality() { return quality; }
    public void setQuality(int quality) { this.quality = quality; }
}
