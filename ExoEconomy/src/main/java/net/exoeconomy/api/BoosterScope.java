package net.exoeconomy.api;

/** Who a booster affects. */
public enum BoosterScope {
    /** Only the owner (the purchaser or the player an admin granted it to). */
    PERSONAL,
    /** Every eligible player online now or joining while it is active. */
    GLOBAL
}
