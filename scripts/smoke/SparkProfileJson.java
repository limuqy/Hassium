import java.nio.file.*;
import java.util.*;
import java.lang.reflect.*;

/** Decode local spark protobuf with the exact producer JAR; no uploads. */
public final class SparkProfileJson {
    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char ch : text.toCharArray()) {
            if (ch == '\\' || ch == '"') out.append('\\').append(ch);
            else if (ch < 32) out.append(String.format("\\u%04x", (int) ch));
            else out.append(ch);
        }
        return out.append('"').toString();
    }
    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof List<?> list) return "[" + String.join(",", list.stream().map(SparkProfileJson::json).toList()) + "]";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?,?> map) {
            List<String> fields = new ArrayList<>();
            map.forEach((key, item) -> fields.add(quote(key.toString()) + ":" + json(item)));
            return "{" + String.join(",", fields) + "}";
        }
        if (value.getClass().getName().startsWith("me.lucko.spark.proto.") && !value.getClass().isEnum()) {
            List<String> fields = new ArrayList<>();
            for (Method method : value.getClass().getDeclaredMethods()) {
                String name = method.getName();
                if (method.getParameterCount() != 0 || !Modifier.isPublic(method.getModifiers()) || Modifier.isStatic(method.getModifiers())
                        || !name.startsWith("get") || name.endsWith("Count") || name.endsWith("Bytes")
                        || name.endsWith("Value") || name.endsWith("OrBuilder") || name.endsWith("OrBuilderList")
                        || name.equals("getSerializedSize") || name.equals("getParserForType") || name.equals("getDefaultInstanceForType")) continue;
                try {
                    String key = name.substring(3).replaceAll("(List|Map)$", "");
                    key = Character.toLowerCase(key.charAt(0)) + key.substring(1);
                    fields.add(quote(key) + ":" + json(method.invoke(value)));
                } catch (Exception error) { throw new IllegalStateException(name, error); }
            }
            return "{" + String.join(",", fields) + "}";
        }
        return quote(value.toString());
    }
    public static void main(String[] args) throws Exception {
        var type = Class.forName("me.lucko.spark.proto.SparkSamplerProtos$SamplerData");
        var profile = type.getMethod("parseFrom", byte[].class).invoke(null, (Object) Files.readAllBytes(Path.of(args[0])));
        Files.writeString(Path.of(args[1]), json(profile));
    }
}
