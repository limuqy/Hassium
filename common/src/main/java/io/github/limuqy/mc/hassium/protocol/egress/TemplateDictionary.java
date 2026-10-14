package io.github.limuqy.mc.hassium.protocol.egress;

import java.util.*;

/** Opaque per-packet templates. Definitions and removals commit only with the selected frame. */
final class TemplateDictionary {
    static final int MAX_ENTRIES = 2048, MAX_STORAGE = 2 * 1024 * 1024;
    static final int MAX_SEEDS = 256, MAX_SEED_BYTES = 512 * 1024, MAX_SEARCH = 24;
    private LinkedHashMap<Integer, Entry> entries = new LinkedHashMap<>();
    private final LinkedHashMap<String, ArrayDeque<Model>> seeds = new LinkedHashMap<>();
    private final LinkedHashMap<Model, Boolean> recentModels = new LinkedHashMap<>(16, .75f, true);
    private int backoff, cooldown, initialFrames, referenceRatio = -1;
    private long traffic;
    private boolean attempted;
    private static final class Model {
        final String key, bucket;
        final byte[] base;
        byte[] last;
        List<Slot> slots = List.of();
        int observations = 1, changes;
        Model(String key, byte[] bytes) { this.key = key; bucket = seedKey(key, bytes.length); base = last = bytes; }
        int cost() { return base.length * 2 + key.length() * 2 + 256; }
        Entry entry(int id) { return new Entry(id, key, base, slots); }
        boolean confirmed() { return slots.isEmpty() ? observations >= 3 : changes >= 2; }
    }
    private int nextId = 1, seedBytes, seedCount;

    private record Slot(int offset, int length) {}
    private record Entry(int id, String key, byte[] base, List<Slot> slots) {
        int cost() { return base.length + slots.size() * 8 + 64 + key.length() * 2; }
        int variables() { return TemplateDictionary.variables(slots); }
        boolean matches(byte[] bytes) {
            if (bytes.length != base.length) return false;
            if (constantFingerprint(bytes) != constantFingerprint(base)) return false;
            int pos = 0;
            for (Slot slot : slots) {
                for (; pos < slot.offset; pos++) if (bytes[pos] != base[pos]) return false;
                pos += slot.length;
            }
            for (; pos < base.length; pos++) if (bytes[pos] != base[pos]) return false;
            return true;
        }
        private int constantFingerprint(byte[] bytes) {
            int hash = 1, count = 0, slotIndex = 0;
            for (int i = 0; i < bytes.length && count < 8;) {
                if (slotIndex < slots.size() && i == slots.get(slotIndex).offset) { i += slots.get(slotIndex++).length; continue; }
                hash = hash * 31 + bytes[i++]; count++;
            }
            return hash;
        }
    }
    final class Transaction {
        private final byte[] bytes;
        private final LinkedHashMap<Integer, Entry> table;
        private final int next;
        final int exact, partial, definitions;
        Transaction(byte[] bytes, LinkedHashMap<Integer, Entry> table, int next, int exact, int partial, int definitions) {
            this.bytes = bytes; this.table = table; this.next = next;
            this.exact = exact; this.partial = partial; this.definitions = definitions;
        }
        byte[] bytes() { return bytes; }
        void commit() { entries = table; nextId = next; }
    }
    /** A failed probe backs off at most 32 frames; a different packet mix wakes learning immediately. */
    boolean shouldAttempt(List<EgressFrameCodec.PacketBytes> packets, int frameBytes, int logicalBytes) {
        long fingerprint = 0;
        for (var packet : packets) {
            int length = packet.content().length;
            if (length < 8 || length > 4096 || packet.key().length() > 256) continue;
            int hash = 31 * packet.key().hashCode() + (31 - Integer.numberOfLeadingZeros(length));
            fingerprint |= 1L << (hash ^ hash >>> 16);
        }
        // Remember observed categories: an occasional missing event is not a workload transition.
        if ((fingerprint & ~traffic) != 0) { traffic |= fingerprint; backoff = cooldown = 0; }
        int ratio = (int)Math.min(100, (long)frameBytes * 100 / logicalBytes);
        if (logicalBytes >= 256 && (referenceRatio < 0 || Math.abs(ratio - referenceRatio) >= 20)) {
            referenceRatio = ratio; backoff = cooldown = 0;
        }
        boolean initial = fingerprint != 0 && initialFrames < 32;
        if (initial) initialFrames++;
        attempted = fingerprint != 0 && (initial || cooldown-- <= 0);
        return attempted;
    }
    void selected(boolean mapped) {
        if (!attempted) return;
        if (mapped) backoff = cooldown = 0;
        else { backoff = Math.min(32, backoff == 0 ? 1 : backoff * 2); cooldown = backoff; }
    }
    Transaction prepare(List<EgressFrameCodec.PacketBytes> packets) {
        // Learn before admission, so all references use the union of changes observed in this batch.
        Model[] observations = new Model[packets.size()];
        for (int i = 0; i < packets.size(); i++) {
            var packet = packets.get(i);
            byte[] bytes = packet.content();
            if (bytes.length >= 8 && bytes.length <= 4096 && packet.key().length() <= 256) observations[i] = observe(packet.key(), bytes);
        }
        Map<String, ArrayDeque<Entry>> index = new HashMap<>();
        for (Entry e : entries.values()) index.computeIfAbsent(seedKey(e.key, e.base.length), ignored -> new ArrayDeque<>()).addFirst(e);
        Entry[] references = new Entry[packets.size()];
        Model[] candidates = new Model[packets.size()];
        Map<Model, Integer> uses = new LinkedHashMap<>();
        for (int i = 0; i < packets.size(); i++) {
            var packet = packets.get(i); byte[] bytes = packet.content();
            if (bytes.length < 8 || bytes.length > 4096 || packet.key().length() > 256) continue;
            String bucket = seedKey(packet.key(), bytes.length); int checked = 0;
            var existing = index.get(bucket);
            if (existing != null) for (Entry e : existing) {
                if (++checked > MAX_SEARCH) break;
                if (e.matches(bytes) && (references[i] == null || e.variables() < references[i].variables())) references[i] = e;
            }
            if (references[i] != null) continue;
            // observe already chose the nearest of at most 24 models. Its variable union can
            // only expand, so retain that result instead of searching or allocating it again.
            Model model = observations[i];
            if (model != null && model.confirmed() && model.entry(0).matches(bytes)) candidates[i] = model;
            if (candidates[i] != null) uses.merge(candidates[i], 1, Integer::sum);
        }
        // Count the complete definition and conservative ID cost; never admit from presumed future hits.
        List<Model> admissible = new ArrayList<>();
        int admissionId = (int)Math.min(Integer.MAX_VALUE, (long)nextId + 15);
        for (var use : uses.entrySet()) if (profit(use.getKey(), use.getValue(), admissionId) > 0) admissible.add(use.getKey());
        admissible.sort(Comparator.comparingLong((Model m) -> profit(m, uses.get(m), admissionId)).reversed());
        LinkedHashMap<Integer, Entry> table = new LinkedHashMap<>(entries);
        Set<Integer> used = new HashSet<>();
        for (Entry e : references) if (e != null) used.add(e.id);
        Map<Model, Entry> admitted = new IdentityHashMap<>();
        EgressBytes.Writer commands = new EgressBytes.Writer(), records = new EgressBytes.Writer();
        int next = nextId, commandCount = 0, exact = 0, partial = 0, definitions = 0;
        int storage = 0; for (Entry e : table.values()) storage += e.cost();
        for (Model model : admissible) {
            if (admitted.size() == 16) break;
            if (next >= Integer.MAX_VALUE - 16) throw EgressBytes.invalid("Template IDs exhausted");
            Entry entry = model.entry(next++);
            var iterator = table.entrySet().iterator();
            while (table.size() >= MAX_ENTRIES || storage + entry.cost() > MAX_STORAGE) {
                if (!iterator.hasNext()) { entry = null; break; }
                var old = iterator.next(); if (used.contains(old.getKey())) continue;
                commands.octet(2).integer(old.getKey()); commandCount++;
                storage -= old.getValue().cost(); iterator.remove();
            }
            if (entry == null) continue;
            int before = commands.size(); writeDefinition(commands, entry); commandCount++; definitions += commands.size() - before;
            table.put(entry.id, entry); storage += entry.cost(); admitted.put(model, entry); used.add(entry.id);
        }
        for (int i = 0; i < packets.size(); i++) {
            byte[] bytes = packets.get(i).content();
            Entry match = references[i] != null ? references[i] : admitted.get(candidates[i]);
            if (match == null) records.octet(0).integer(bytes.length).bytes(bytes);
            else {
                table.remove(match.id); table.put(match.id, match);
                records.octet(1).integer(match.id);
                for (Slot slot : match.slots) records.write(bytes, slot.offset, slot.length);
                if (match.slots.isEmpty()) exact++; else partial++;
            }
        }
        if (exact + partial == 0) return null;
        EgressBytes.Writer out = new EgressBytes.Writer();
        out.integer(packets.size()).integer(commandCount).bytes(commands.toByteArray()).bytes(records.toByteArray());
        return new Transaction(out.toByteArray(), table, next, exact, partial, definitions);
    }
    private static long profit(Model model, int count, int id) {
        Entry entry = model.entry(id); var definition = new EgressBytes.Writer(); writeDefinition(definition, entry);
        int saved = model.base.length + varIntSize(model.base.length) - 1 - varIntSize(id) - entry.variables();
        return (long)count * saved - definition.size();
    }
    private static int varIntSize(int n) { int result = 1; while ((n >>>= 7) != 0) result++; return result; }
    private static int variables(List<Slot> slots) { int result = 0; for (Slot slot : slots) result += slot.length; return result; }
    record LearningStats(int models, int bytes) {}
    LearningStats learningStats() { return new LearningStats(seedCount, seedBytes); }
    private static String seedKey(String key, int length) { return key + '\u0000' + length; }
    private Model observe(String key, byte[] bytes) {
        String bucket = seedKey(key, bytes.length);
        var recent = seeds.computeIfAbsent(bucket, ignored -> new ArrayDeque<>());
        Model best = null; List<Slot> bestSlots = null; int bestVariables = Integer.MAX_VALUE, checked = 0;
        for (Model model : recent) {
            if (++checked > MAX_SEARCH) break;
            List<Slot> slots = learn(model.base, bytes, model.slots);
            if (slots == null) continue;
            int n = variables(slots);
            if (n < bestVariables) { best = model; bestSlots = slots; bestVariables = n; if (n == 0) break; }
        }
        if (best == null) {
            best = new Model(key, bytes); seedCount++; seedBytes += best.cost();
            recent.addFirst(best);
        } else {
            best.slots = bestSlots;
            if (best.observations < Integer.MAX_VALUE) best.observations++;
            if (!Arrays.equals(best.last, bytes)) { if (best.changes < Integer.MAX_VALUE) best.changes++; best.last = bytes; }
            recent.remove(best); recent.addFirst(best);
        }
        recentModels.put(best, Boolean.TRUE);
        while (recent.size() > MAX_SEARCH) forget(recent.getLast());
        while (seedCount > MAX_SEEDS || seedBytes > MAX_SEED_BYTES) forget(recentModels.keySet().iterator().next());
        return best;
    }
    private void forget(Model model) {
        var bucket = seeds.get(model.bucket); bucket.remove(model); if (bucket.isEmpty()) seeds.remove(model.bucket);
        recentModels.remove(model); seedCount--; seedBytes -= model.cost();
    }
    private static List<Slot> learn(byte[] sample, byte[] bytes, List<Slot> previous) {
        if (Arrays.equals(sample, bytes)) return previous;
        List<Slot> slots = new ArrayList<>(); int variables = 0, slotIndex = 0;
        for (int i = 0; i < bytes.length;) {
            boolean old = slotIndex < previous.size() && i >= previous.get(slotIndex).offset;
            if (!old && sample[i] == bytes[i]) { i++; continue; }
            int start = i;
            do {
                if (slotIndex < previous.size() && i >= previous.get(slotIndex).offset) {
                    i = previous.get(slotIndex).offset + previous.get(slotIndex).length; slotIndex++;
                } else i++;
            } while (i < bytes.length && (sample[i] != bytes[i] || slotIndex < previous.size() && i >= previous.get(slotIndex).offset));
            variables += i - start; slots.add(new Slot(start, i - start));
            if (slots.size() > 8 || variables > 128) return null;
        }
        return bytes.length - variables >= 8 ? List.copyOf(slots) : null;
    }
    private static void writeDefinition(EgressBytes.Writer w, Entry entry) {
        w.octet(1).integer(entry.id).integer(entry.base.length).bytes(entry.base).integer(entry.slots.size());
        for (Slot slot : entry.slots) w.integer(slot.offset).integer(slot.length);
    }
    final class Decoded {
        private final byte[] bytes;
        private final LinkedHashMap<Integer, Entry> table;
        private final int next;
        Decoded(byte[] bytes, LinkedHashMap<Integer, Entry> table, int next) { this.bytes = bytes; this.table = table; this.next = next; }
        byte[] bytes() { return bytes; }
        void commit() { entries = table; nextId = next; }
    }
    Decoded decode(byte[] payload) {
        EgressBytes.Reader r = new EgressBytes.Reader(payload);
        int count = r.integer(EgressFrameCodec.MAX_PACKETS);
        if (count == 0) throw EgressBytes.invalid("Empty mapped batch");
        int commands = r.integer(EgressFrameCodec.MAX_PACKETS * 2);
        LinkedHashMap<Integer, Entry> table = new LinkedHashMap<>(entries);
        int next = nextId, storage = table.values().stream().mapToInt(Entry::cost).sum();
        for (int c = 0; c < commands; c++) {
            int op = r.octet(), id = r.integer(Integer.MAX_VALUE);
            if (op == 2) {
                Entry removed = table.remove(id);
                if (removed == null) throw EgressBytes.invalid("Removing missing template");
                storage -= removed.cost();
            } else if (op == 1) {
                if (id < next || id == Integer.MAX_VALUE) throw EgressBytes.invalid("Reused template ID");
                next = id + 1;
                int size = r.integer(4096); if (size < 8) throw EgressBytes.invalid("Invalid template size");
                byte[] base = r.bytes(size); int n = r.integer(8), end = 0, variables = 0;
                List<Slot> slots = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    int offset = r.integer(size), length = r.integer(128);
                    if (offset < end || length == 0 || offset + length > size) throw EgressBytes.invalid("Invalid template slots");
                    end = offset + length; variables += length; slots.add(new Slot(offset, length));
                }
                if (variables > 128 || size - variables < 8) throw EgressBytes.invalid("Invalid template constants");
                Entry entry = new Entry(id, "", base, List.copyOf(slots));
                storage += entry.cost(); table.put(id, entry);
                if (table.size() > MAX_ENTRIES || storage > MAX_STORAGE) throw EgressBytes.invalid("Template budget exceeded");
            } else throw EgressBytes.invalid("Unknown template command");
        }
        EgressBytes.Writer logical = new EgressBytes.Writer(); logical.integer(count);
        for (int i = 0; i < count; i++) {
            int op = r.octet(); byte[] bytes;
            if (op == 0) {
                int size = r.integer(EgressFrameCodec.MAX_FRAME_BYTES);
                if (size == 0) throw EgressBytes.invalid("Empty literal"); bytes = r.bytes(size);
            } else if (op == 1) {
                Entry entry = table.get(r.integer(Integer.MAX_VALUE));
                if (entry == null) throw EgressBytes.invalid("Missing template");
                bytes = entry.base.clone();
                for (Slot slot : entry.slots) System.arraycopy(r.bytes(slot.length), 0, bytes, slot.offset, slot.length);
            } else throw EgressBytes.invalid("Unknown mapped record");
            logical.integer(bytes.length).bytes(bytes);
            if (logical.size() > EgressFrameCodec.MAX_LOGICAL_BYTES) throw EgressBytes.invalid("Mapped batch too large");
        }
        r.end(); return new Decoded(logical.toByteArray(), table, next);
    }
    void clear() { entries.clear(); seeds.clear(); recentModels.clear(); seedBytes = seedCount = backoff = cooldown = initialFrames = 0; referenceRatio = -1; traffic = 0; attempted = false; nextId = 1; }
    void replace(TemplateDictionary other) { entries = new LinkedHashMap<>(other.entries); nextId = other.nextId; }
}
