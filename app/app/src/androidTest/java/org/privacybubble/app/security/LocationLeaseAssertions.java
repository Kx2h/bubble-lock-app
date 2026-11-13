package org.privacybubble.app.security;

import android.app.Instrumentation;
import android.os.SystemClock;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.privacybubble.app.location.KeyringLocation;
import org.privacybubble.app.ui.LocationReadLease;

public final class LocationLeaseAssertions {
    private LocationLeaseAssertions() { }

    public static int run(Instrumentation instrumentation) throws Exception {
        Class<?> type=Class.forName("org.privacybubble.app.MainActivity$ScreenState");
        Constructor<?> constructor=type.getDeclaredConstructor(); constructor.setAccessible(true);
        Object state=constructor.newInstance();
        AtomicInteger callbacks=new AtomicInteger(); AtomicReference<Throwable> failure=new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try {
                set(type,state,"locations",Collections.singletonList(new KeyringLocation("lease-test","Isolated lease fixture","available",37.0,127.0,5.0,"2026-11-14T00:00:00Z",null,"2026-11-14T00:00:05Z","gps",null,null)));
                set(type,state,"selectedId","lease-test"); set(type,state,"loading",true); set(type,state,"generation",19L);
                set(type,state,"listener",(Runnable)callbacks::incrementAndGet);
                LocationReadLease lease=(LocationReadLease)get(type,state,"readLease");
                check(lease.install(System.currentTimeMillis()+350,System.currentTimeMillis(),SystemClock.elapsedRealtime()),"install-lease");
                invoke(type,state,"scheduleReadLease");
            } catch (Throwable problem) { failure.set(problem); }
        });
        if (failure.get()!=null) throw new AssertionError("lease-setup");
        Thread.sleep(700); instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> {
            try {
                check(((java.util.List<?>)get(type,state,"locations")).isEmpty(),"expiry-clears-coordinates");
                check(get(type,state,"selectedId")==null,"expiry-clears-selection");
                check((Boolean)get(type,state,"loading"),"expiry-keeps-renewal-pending");
                check((Long)get(type,state,"generation")==19L,"expiry-independent-from-network-generation");
                check((Boolean)get(type,state,"readLeaseExpired") && callbacks.get()>0,"expiry-notifies-renderer");
                invoke(type,state,"detach");
                check(!((LocationReadLease)get(type,state,"readLease")).active(),"detached-state-remains-expired");
                invoke(type,state,"close");
            } catch (Throwable problem) { failure.set(problem); }
        });
        if (failure.get()!=null) throw new AssertionError("lease-state");
        return 7;
    }

    private static Object get(Class<?> type,Object target,String name) throws Exception { Field field=type.getDeclaredField(name); field.setAccessible(true); return field.get(target); }
    private static void set(Class<?> type,Object target,String name,Object value) throws Exception { Field field=type.getDeclaredField(name); field.setAccessible(true); field.set(target,value); }
    private static void invoke(Class<?> type,Object target,String name) throws Exception { Method method=type.getDeclaredMethod(name); method.setAccessible(true); method.invoke(target); }
    private static void check(boolean value,String name) { if (!value) throw new AssertionError(name); }
}
