package dev.prc.vendetta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

final class AngelWaveTypes {
    static final List<String> ALL = List.of("crazy", "imperfect", "perfect", "ascendant", "wanderer");

    private AngelWaveTypes() { }

    static List<String> compose(Set<String> excluded, int count, ThreadLocalRandom random) {
        if (count <= 0) return List.of();
        List<String> eligible = new ArrayList<>(ALL);
        eligible.removeAll(excluded);
        if (eligible.isEmpty()) return List.of();
        List<String> result = new ArrayList<>(eligible);
        while (result.size() < count)
            result.add(eligible.get(random.nextInt(eligible.size())));
        Collections.shuffle(result, random);
        return List.copyOf(result);
    }
}
