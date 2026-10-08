package dev.exodaily.core.service;

/** Presentation-independent status of one claim position for one viewer. */
public enum PositionStatus {
    /** Can be claimed now. */
    AVAILABLE,
    /** Already delivered. */
    CLAIMED,
    /** Requires premium, which the viewer does not currently have. */
    LOCKED,
    /** A claim is being processed right now. */
    PROCESSING,
    /** Delivery was interrupted; awaiting staff reconciliation. */
    REVIEW
}
