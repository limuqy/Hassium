package io.github.limuqy.mc.hassium.network;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Local channel admission only; this is never a remote acknowledgement or a blocking send. */
public final class PreparedClientSend {
    private PreparedClientSend() {}
    public static boolean trySend(Object connection,Supplier<?> current,BooleanSupplier available,
                                  Runnable send,Consumer<RuntimeException> failed) {
        try {
            if(connection==null || current.get()!=connection || !available.getAsBoolean()) return false;
            send.run();
            return current.get()==connection;
        } catch(RuntimeException failure) { failed.accept(failure); return false; }
    }
}
