package com.example.engine

/**
 * High-level delivery classification for every AutoMate AI / Smart Advisor response.
 */
enum class ResponseType {
    TEXT_MESSAGE,
    PRODUCT_IMAGE,
    PRODUCT_LIST,
    CATEGORY_LINK,
    PRODUCT_COMPARISON
}

/**
 * Deterministic delivery lifecycle for WhatsApp Accessibility product image + caption messages.
 */
enum class ImageDeliveryState {
    QUEUED,
    DOWNLOADING_IMAGE,
    IMAGE_READY,
    OPENING_ATTACHMENTS,
    IMAGE_SELECTED,
    CAPTION_ENTERED,
    SENDING,
    SENT,
    FAILED,
    DELIVERY_UNKNOWN
}
