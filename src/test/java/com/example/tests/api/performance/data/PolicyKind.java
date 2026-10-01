package com.example.tests.api.performance.data;

import java.util.Arrays;
import java.util.Optional;

/** Where a test policy came from; its letter is part of the policy name. */
public enum PolicyKind {
    SEED('s'),
    MIXED_WRITE('w'),
    CREATE_RUN('c'),
    DELETE_RUN('d'),
    SMOKE('k');

    private final char letter;

    PolicyKind(char letter) {
        this.letter = letter;
    }

    public char letter() {
        return letter;
    }

    public static Optional<PolicyKind> fromLetter(char letter) {
        return Arrays.stream(values()).filter(kind -> kind.letter == letter).findFirst();
    }

    /** All letters as a regex character class, e.g. {@code [swcdk]}. */
    public static String letterClass() {
        StringBuilder letters = new StringBuilder("[");
        Arrays.stream(values()).forEach(kind -> letters.append(kind.letter));
        return letters.append(']').toString();
    }
}
