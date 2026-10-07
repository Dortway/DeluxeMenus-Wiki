package dev.exo.dailyspinner.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SmallCapsTest {

    @Test
    void convertsLetters() {
        assertEquals("ᴅᴀɪʟʏ ꜱᴘɪɴɴᴇʀ", SmallCaps.convert("Daily Spinner"));
        assertEquals("ʀᴇᴡᴀʀᴅꜱ", SmallCaps.convert("REWARDS"));
    }

    @Test
    void onlyConvertsMarkedRegionsAndKeepsTags() {
        assertEquals("<primary>ꜱᴘɪɴ ɴᴏᴡ</primary> now",
                SmallCaps.process("<sc><primary>Spin Now</primary></sc> now", true));
        assertEquals("ʀᴇᴀᴅʏ ɪɴ <time>", SmallCaps.process("<sc>Ready in <time></sc>", true));
    }

    @Test
    void fallbackStripsMarkers() {
        assertEquals("<gold>Spin Now", SmallCaps.process("<gold><sc>Spin Now</sc>", false));
        assertEquals("plain", SmallCaps.process("plain", true));
    }
}
