package dev.runreviq.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Classification proposals acquire authority through publication, not this value type. */
public record EvidenceProfile(int schemaVersion, HttpScope httpScope, List<Unit> units) {
    public EvidenceProfile {
        if (schemaVersion != 1 || units == null || units.size() > 20) throw new IllegalArgumentException("Invalid evidence profile");
        units = List.copyOf(units);
        int previous = 0;
        for (Unit unit : units) {
            if (unit.startLine() <= previous) throw new IllegalArgumentException("Overlapping evidence units");
            previous = unit.endLine();
        }
    }

    public record HttpScope(String mode, List<Integer> codes) {
        public HttpScope {
            if (mode == null || !Set.of("general", "only").contains(mode) || codes == null || codes.size() > 20
                    || codes.stream().anyMatch(c -> c == null || c < 100 || c > 599)
                    || new HashSet<>(codes).size() != codes.size()
                    || mode.equals("only") == codes.isEmpty()) throw new IllegalArgumentException("Invalid HTTP scope");
            codes = List.copyOf(codes);
        }
    }

    public record Unit(String role, int startLine, int endLine) {
        public Unit {
            if (role == null || !Set.of("procedure", "prohibition", "historical", "monitoring").contains(role)
                    || startLine < 1 || endLine < startLine || (long) endLine - startLine >= 20) {
                throw new IllegalArgumentException("Invalid evidence range");
            }
        }
    }
}
