package dev.runreviq.domain;

import java.math.BigInteger;

/** Exact major.minor.patch comparison, with Python-compatible unbounded integers. */
public record SoftwareVersion(BigInteger major, BigInteger minor, BigInteger patch)
        implements Comparable<SoftwareVersion> {
    public static SoftwareVersion parse(String value) {
        if (value == null || !value.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) {
            throw new IllegalArgumentException("Use an exact major.minor.patch version");
        }
        String[] parts = value.split("\\.");
        return new SoftwareVersion(new BigInteger(parts[0]), new BigInteger(parts[1]), new BigInteger(parts[2]));
    }

    @Override public int compareTo(SoftwareVersion other) {
        int result = major.compareTo(other.major);
        if (result == 0) result = minor.compareTo(other.minor);
        return result == 0 ? patch.compareTo(other.patch) : result;
    }
}
