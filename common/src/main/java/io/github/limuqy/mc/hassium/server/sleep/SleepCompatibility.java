package io.github.limuqy.mc.hassium.server.sleep;

import java.util.Collection;
import java.util.Set;

/** Server-side mods only. Client-only input mods can keep using their existing native inputs. */
public final class SleepCompatibility {
    private static final Set<String> REVIEWED = Set.of("minecraft","forge","neoforge","hassium",
            "javafml","lowcodefml","mclanguage","fml");
    private SleepCompatibility() {}
    public static String firstUnsupported(Collection<String> ids) {
        return ids.stream().filter(id->!REVIEWED.contains(id)).sorted().findFirst()
                .map(id->"Sleep has no reviewed world-payload policy for mod: "+id).orElse(null);
    }
}
