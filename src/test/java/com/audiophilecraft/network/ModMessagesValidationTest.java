package com.audiophilecraft.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

class ModMessagesValidationTest {
    @Test
    void acceptsOnlySupportedAudioUrlSchemesWithAHost() {
        assertTrue(ModMessages.isValidAudioUrl("https://example.com/audio.ogg"));
        assertTrue(ModMessages.isValidAudioUrl("HTTP://example.com/live"));

        assertFalse(ModMessages.isValidAudioUrl("ftp://example.com/audio.ogg"));
        assertFalse(ModMessages.isValidAudioUrl("file:///tmp/audio.ogg"));
        assertFalse(ModMessages.isValidAudioUrl("/relative/audio.ogg"));
        assertFalse(ModMessages.isValidAudioUrl("https:///missing-host"));
    }

    @Test
    void rejectsBlankOversizedMalformedAndCredentialBearingUrls() {
        assertFalse(ModMessages.isValidAudioUrl(null));
        assertFalse(ModMessages.isValidAudioUrl(""));
        assertFalse(ModMessages.isValidAudioUrl("   "));
        assertFalse(ModMessages.isValidAudioUrl("https://user:secret@example.com/audio.ogg"));
        assertFalse(ModMessages.isValidAudioUrl("https://exa mple.com/audio.ogg"));
        assertFalse(ModMessages.isValidAudioUrl("https://example.com/" + "a".repeat(2_048)));
    }

    @Test
    void finiteRangeValidationIncludesBoundsAndRejectsNonFiniteValues() {
        assertTrue(ModMessages.isFiniteInRange(-9.0f, -9.0f, 9.0f));
        assertTrue(ModMessages.isFiniteInRange(9.0f, -9.0f, 9.0f));
        assertTrue(ModMessages.isFiniteInRange(0.0f, -9.0f, 9.0f));

        assertFalse(ModMessages.isFiniteInRange(-9.01f, -9.0f, 9.0f));
        assertFalse(ModMessages.isFiniteInRange(9.01f, -9.0f, 9.0f));
        assertFalse(ModMessages.isFiniteInRange(Float.NaN, -9.0f, 9.0f));
        assertFalse(ModMessages.isFiniteInRange(Float.POSITIVE_INFINITY, -9.0f, 9.0f));
        assertFalse(ModMessages.isFiniteInRange(Float.NEGATIVE_INFINITY, -9.0f, 9.0f));
    }

    @Test
    void handOrdinalsMatchTheMinecraftHandEnumExactly() {
        for (Hand hand : Hand.values()) {
            assertTrue(ModMessages.isValidHandOrdinal(hand.ordinal()));
        }

        assertFalse(ModMessages.isValidHandOrdinal(-1));
        assertFalse(ModMessages.isValidHandOrdinal(Hand.values().length));
    }

    @Test
    void speakerTypesAndEqBandsUseTheProtocolWhitelist() {
        assertTrue(ModMessages.isValidSpeakerType("normal"));
        assertTrue(ModMessages.isValidSpeakerType("sub"));
        assertTrue(ModMessages.isValidSpeakerType("mid"));
        assertTrue(ModMessages.isValidSpeakerType("line"));
        assertFalse(ModMessages.isValidSpeakerType("LINE"));
        assertFalse(ModMessages.isValidSpeakerType("unknown"));
        assertFalse(ModMessages.isValidSpeakerType(null));

        for (int band = 0; band < 5; band++) {
            assertTrue(ModMessages.isValidEqBand(band));
        }
        assertFalse(ModMessages.isValidEqBand(-1));
        assertFalse(ModMessages.isValidEqBand(5));
    }

    @Test
    void playbackDimensionMustMatchTheClientsCurrentDimension() {
        Identifier overworld = new Identifier("minecraft", "overworld");
        Identifier nether = new Identifier("minecraft", "the_nether");
        assertTrue(ModMessages.isMatchingDimension(overworld, overworld));

        assertFalse(ModMessages.isMatchingDimension(overworld, nether));
        assertFalse(ModMessages.isMatchingDimension(null, overworld));
        assertFalse(ModMessages.isMatchingDimension(overworld, null));
    }
}
