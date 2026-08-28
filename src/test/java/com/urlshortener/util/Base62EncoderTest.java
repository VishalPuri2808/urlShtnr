package com.urlshortener.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Base62EncoderTest {

    private final Base62Encoder encoder = new Base62Encoder();

    @Test
    void encode_one_returnsSingleCharacter() {
        assertThat(encoder.encode(1)).isEqualTo("1");
    }

    @Test
    void encode_62_returnsCarryOver() {
        // 62 in base62 is "10" — same carry-over as 10 in base10 is "10"
        assertThat(encoder.encode(62)).isEqualTo("10");
    }

    @Test
    void encode_63_returns11() {
        assertThat(encoder.encode(63)).isEqualTo("11");
    }

    @Test
    void encode_maxAlphabetChar_returnsZ() {
        // Position 61 in the alphabet is 'Z'
        assertThat(encoder.encode(61)).isEqualTo(String.valueOf(Base62Encoder.ALPHABET.charAt(61)));
    }

    @ParameterizedTest(name = "decode(encode({0})) == {0}")
    @ValueSource(longs = {1, 61, 62, 63, 3843, 3844, 100_000, 999_999_999L})
    void encode_roundTrip(long id) {
        assertThat(encoder.decode(encoder.encode(id))).isEqualTo(id);
    }

    @Test
    void encode_zero_throwsIllegalArgument() {
        assertThatThrownBy(() -> encoder.encode(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void encode_negative_throwsIllegalArgument() {
        assertThatThrownBy(() -> encoder.encode(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void encode_noCollisionsForFirstThousandSequentialIds() {
        // The core contract: sequential IDs must produce unique codes
        Set<String> codes = LongStream.rangeClosed(1, 1000)
                .mapToObj(encoder::encode)
                .collect(Collectors.toSet());
        assertThat(codes).hasSize(1000);
    }

    @Test
    void encode_outputIsAlphanumericOnly() {
        for (long id : new long[]{1, 100, 10_000, 999_999}) {
            assertThat(encoder.encode(id)).matches("[0-9a-zA-Z]+");
        }
    }

    @Test
    void encode_codeGrowsLogarithmically() {
        // 6-char code should cover > 56 billion IDs
        assertThat(encoder.encode(56_800_235_583L).length()).isLessThanOrEqualTo(6);
    }
}
