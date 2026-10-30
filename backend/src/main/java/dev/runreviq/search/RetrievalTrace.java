package dev.runreviq.search;

import java.util.*;
import java.util.function.Consumer;

/** Explicit opt-in observer. No registry, logging, filesystem or HTTP configuration. */
public final class RetrievalTrace {
    private RetrievalTrace() {}
    public static Map<String,Object> key(Map<String,Object> passage) {
        var key=new TreeMap<String,Object>();
        for(String field:List.of("document_id","revision","start_line","end_line"))key.put(field,passage.get(field));
        return Collections.unmodifiableMap(key);
    }
    public static void emit(Consumer<Map<String,Object>> sink,String stage,Object... fields) {
        if(sink==null)return;
        var event=new TreeMap<String,Object>();event.put("stage",stage);
        for(int i=0;i<fields.length;i+=2)event.put((String)fields[i],freeze(fields[i+1]));
        sink.accept(Collections.unmodifiableMap(event));
    }
    private static Object freeze(Object value) {
        if(value instanceof Map<?,?> map){var copy=new TreeMap<String,Object>();map.forEach((k,v)->copy.put((String)k,freeze(v)));return Collections.unmodifiableMap(copy);}
        if(value instanceof Set<?> set)return set.stream().map(RetrievalTrace::freeze).sorted(Comparator.comparing(Object::toString)).toList();
        if(value instanceof Collection<?> list)return list.stream().map(RetrievalTrace::freeze).toList();
        if(value==null||value instanceof String||value instanceof Number||value instanceof Boolean)return value;
        throw new IllegalArgumentException("Unsupported trace value");
    }
}
