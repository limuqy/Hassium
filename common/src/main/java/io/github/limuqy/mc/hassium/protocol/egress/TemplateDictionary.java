package io.github.limuqy.mc.hassium.protocol.egress;

import java.util.*;

/** Opaque per-packet templates. Definitions and removals commit only with the selected frame. */
final class TemplateDictionary {
    static final int MAX_ENTRIES = 2048, MAX_STORAGE = 2 * 1024 * 1024;
    static final int MAX_SEEDS = 256, MAX_SEED_BYTES = 512 * 1024, MAX_SEARCH = 24;
    private LinkedHashMap<Integer, Entry> entries = new LinkedHashMap<>();
    private final LinkedHashMap<String, ArrayDeque<byte[]>> seeds = new LinkedHashMap<>(16, .75f, true);
    private int nextId = 1, seedBytes, seedCount;

    private record Slot(int offset, int length) {}
    private record Entry(int id, String key, byte[] base, List<Slot> slots) {
        int cost() { return base.length + slots.size() * 8 + 64 + key.length() * 2; }
        int variables() { return slots.stream().mapToInt(Slot::length).sum(); }
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
    Transaction prepare(List<EgressFrameCodec.PacketBytes> packets) {
        LinkedHashMap<Integer, Entry> table = new LinkedHashMap<>(entries);
        Set<Integer> used = new HashSet<>();
        EgressBytes.Writer commands = new EgressBytes.Writer(), records = new EgressBytes.Writer();
        int next = nextId, commandCount = 0, exact = 0, partial = 0, definitions = 0;
        for (var packet : packets) {
            byte[] bytes = packet.bytes();
            String key = packet.key();
            Entry match = null;
            if (bytes.length <= 4096 && key.length() <= 256) {
                int checked = 0;
                for (Entry e : table.reversed().values()) {
                    if (!e.key.equals(key) || e.base.length != bytes.length) continue;
                    if (++checked > MAX_SEARCH) break;
                    if (e.matches(bytes)) { match = e; break; }
                }
                if (match == null) {
                    var recent = seeds.get(seedKey(key, bytes.length));
                    if (recent != null) {
                        checked = 0;
                        for (byte[] sample : recent) {
                            if (++checked > MAX_SEARCH) break;
                            List<Slot> slots = learn(sample, bytes);
                            if (slots == null) continue;
                            if (next == Integer.MAX_VALUE) throw EgressBytes.invalid("Template IDs exhausted");
                            match = new Entry(next++, key, sample, slots);
                            int storage = table.values().stream().mapToInt(Entry::cost).sum();
                            var iterator = table.entrySet().iterator();
                            while (table.size() >= MAX_ENTRIES || storage + match.cost() > MAX_STORAGE) {
                                if (!iterator.hasNext()) { match = null; break; }
                                var old = iterator.next();
                                if (used.contains(old.getKey())) continue;
                                commands.octet(2).integer(old.getKey()); commandCount++;
                                storage -= old.getValue().cost(); iterator.remove();
                            }
                            if (match != null) {
                                int before = commands.size();
                                writeDefinition(commands, match); commandCount++; definitions += commands.size() - before;
                                table.put(match.id, match);
                            }
                            break;
                        }
                    }
                }
                remember(key, bytes);
            }
            if (match == null) records.octet(0).integer(bytes.length).bytes(bytes);
            else {
                used.add(match.id);
                // Refresh recency only in the provisional table.
                table.remove(match.id); table.put(match.id, match);
                records.octet(1).integer(match.id);
                for (Slot slot : match.slots) records.write(bytes, slot.offset, slot.length);
                if (match.slots.isEmpty()) exact++; else partial++;
            }
        }
        EgressBytes.Writer out = new EgressBytes.Writer();
        out.integer(packets.size()).integer(commandCount).bytes(commands.toByteArray()).bytes(records.toByteArray());
        return new Transaction(out.toByteArray(), table, next, exact, partial, definitions);
    }
    private static String seedKey(String key, int length) { return key + '\u0000' + length; }
    private void remember(String key, byte[] bytes) {
        var recent = seeds.computeIfAbsent(seedKey(key, bytes.length), ignored -> new ArrayDeque<>());
        if (recent.stream().anyMatch(sample -> Arrays.equals(sample, bytes))) return;
        recent.addFirst(bytes); seedCount++; seedBytes += bytes.length + key.length() * 2 + 64;
        while (recent.size() > MAX_SEARCH) {
            byte[] old = recent.removeLast(); seedCount--; seedBytes -= old.length + key.length() * 2 + 64;
        }
        while (seedCount > MAX_SEEDS || seedBytes > MAX_SEED_BYTES) {
            var iterator = seeds.entrySet().iterator(); var oldest = iterator.next();
            String originalKey = oldest.getKey().substring(0, oldest.getKey().lastIndexOf('\u0000'));
            for (byte[] old : oldest.getValue()) { seedCount--; seedBytes -= old.length + originalKey.length() * 2 + 64; }
            iterator.remove();
        }
    }
    private static List<Slot> learn(byte[] sample, byte[] bytes) {
        List<Slot> slots = new ArrayList<>(); int variables = 0;
        for (int i = 0; i < bytes.length;) {
            if (sample[i] == bytes[i]) { i++; continue; }
            int start = i++;
            while (i < bytes.length && sample[i] != bytes[i]) i++;
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
    void clear() { entries.clear(); seeds.clear(); seedBytes = seedCount = 0; nextId = 1; }
    void replace(TemplateDictionary other) { entries = new LinkedHashMap<>(other.entries); nextId = other.nextId; }
}
