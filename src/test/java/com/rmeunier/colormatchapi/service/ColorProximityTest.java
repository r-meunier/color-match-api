package com.rmeunier.colormatchapi.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class ColorProximityTest {

    private static final int[] BLACK = {0, 0, 0};
    private static final int[] WHITE = {255, 255, 255};
    private static final int[] RED = {255, 0, 0};
    private static final int[] DARK_RED = {139, 0, 0};
    private static final int[] BLUE = {0, 0, 255};

    private final ColorProximity colorProximity = new ColorProximity();

    @Test
    void rgb2labConvertsBlack() {
        assertThat(colorProximity.rgb2lab(BLACK)).containsExactly(0f, 0f, 0f);
    }

    @Test
    void rgb2labConvertsWhiteWithLightnessScaledTo255() {
        // L* is scaled from 0..100 to 0..255, a* and b* stay neutral for white
        assertThat(colorProximity.rgb2lab(WHITE)).containsExactly(255f, 0f, 0f);
    }

    @Test
    void rgb2labRejectsVectorsShorterThanThree() {
        assertThatThrownBy(() -> colorProximity.rgb2lab(new int[]{1, 2}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rgb2labIgnoresValuesAfterTheThirdOne() {
        assertThat(colorProximity.rgb2lab(new int[]{255, 0, 0, 42}))
                .containsExactly(colorProximity.rgb2lab(RED));
    }

    @Test
    void proximityOfSameColorIsZero() {
        assertThat(colorProximity.proximity(RED, RED)).isZero();
    }

    @Test
    void proximityBetweenBlackAndWhiteIsTheLightnessRange() {
        assertThat(colorProximity.proximity(BLACK, WHITE)).isCloseTo(255.0, within(0.001));
    }

    @Test
    void proximityIsSymmetric() {
        assertThat(colorProximity.proximity(RED, BLUE))
                .isEqualTo(colorProximity.proximity(BLUE, RED));
    }

    @Test
    void similarColorsAreCloserThanDifferentOnes() {
        assertThat(colorProximity.proximity(RED, DARK_RED))
                .isLessThan(colorProximity.proximity(RED, BLUE));
    }
}
