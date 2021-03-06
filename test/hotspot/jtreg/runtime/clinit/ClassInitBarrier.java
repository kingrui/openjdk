/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * @test
 * @library /test/lib
 *
 * @requires !vm.graal.enabled
 *
 * @run main/othervm/native -Xbatch -XX:CompileCommand=dontinline,*::test* -Xint                   -DTHROW=false ClassInitBarrier
 * @run main/othervm/native -Xbatch -XX:CompileCommand=dontinline,*::test* -Xint                   -DTHROW=true  ClassInitBarrier
 *
 * @run main/othervm/native -Xbatch -XX:CompileCommand=dontinline,*::test* -XX:TieredStopAtLevel=1 -DTHROW=false ClassInitBarrier
 * @run main/othervm/native -Xbatch -XX:CompileCommand=dontinline,*::test* -XX:TieredStopAtLevel=1 -DTHROW=true  ClassInitBarrier
 *
 * @run main/othervm/native -Xbatch -XX:CompileCommand=dontinline,*::test* -XX:-TieredCompilation  -DTHROW=false ClassInitBarrier
 * @run main/othervm/native -Xbatch -XX:CompileCommand=dontinline,*::test* -XX:-TieredCompilation  -DTHROW=true  ClassInitBarrier
 *
 * @run main/othervm/native -Xbatch -XX:CompileCommand=dontinline,*::test* -XX:TieredStopAtLevel=1 -DTHROW=false -XX:CompileCommand=dontinline,*::static* ClassInitBarrier
 * @run main/othervm/native -Xbatch -XX:CompileCommand=dontinline,*::test* -XX:TieredStopAtLevel=1 -DTHROW=true  -XX:CompileCommand=dontinline,*::static* ClassInitBarrier
 * @run main/othervm/native -Xbatch -XX:CompileCommand=dontinline,*::test* -XX:-TieredCompilation  -DTHROW=false -XX:CompileCommand=dontinline,*::static* ClassInitBarrier
 * @run main/othervm/native -Xbatch -XX:CompileCommand=dontinline,*::test* -XX:-TieredCompilation  -DTHROW=true  -XX:CompileCommand=dontinline,*::static* ClassInitBarrier
 *
 * @run main/othervm/native -Xbatch -XX:CompileCommand=dontinline,*::test* -XX:TieredStopAtLevel=1 -DTHROW=false -XX:CompileCommand=exclude,*::static* ClassInitBarrier
 * @run main/othervm/native -Xbatch -XX:CompileCommand=dontinline,*::test* -XX:TieredStopAtLevel=1 -DTHROW=true  -XX:CompileCommand=exclude,*::static* ClassInitBarrier
 * @run main/othervm/native -Xbatch -XX:CompileCommand=dontinline,*::test* -XX:-TieredCompilation  -DTHROW=false -XX:CompileCommand=exclude,*::static* ClassInitBarrier
 * @run main/othervm/native -Xbatch -XX:CompileCommand=dontinline,*::test* -XX:-TieredCompilation  -DTHROW=true  -XX:CompileCommand=exclude,*::static* ClassInitBarrier
 */

import jdk.test.lib.Asserts;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class ClassInitBarrier {
    static {
        System.loadLibrary("ClassInitBarrier");

        if (!init()) {
            throw new Error("init failed");
        }
    }

    static native boolean init();

    static final boolean THROW = Boolean.getBoolean("THROW");

    static class Test {
        static class A {
            static {
                changePhase(Phase.IN_PROGRESS);
                runTests();      // interpreted mode
                warmup();        // trigger compilation
                runTests();      // compiled mode

                ensureBlocked(); // ensure still blocked
                maybeThrow();    // fail initialization if needed

                changePhase(Phase.FINISHED);
            }

            static              void staticM(Runnable action) { action.run(); }
            static synchronized void staticS(Runnable action) { action.run(); }
            static native       void staticN(Runnable action);

            static int staticF;

            int f;
            void m() {}
        }

        static class B extends A {}

        static void testInvokeStatic(Runnable action)        { A.staticM(action); }
        static void testInvokeStaticSync(Runnable action)    { A.staticS(action); }
        static void testInvokeStaticNative(Runnable action)  { A.staticN(action); }

        static int  testGetStatic(Runnable action)    { int v = A.staticF; action.run(); return v;   }
        static void testPutStatic(Runnable action)    { A.staticF = 1;     action.run(); }
        static A    testNewInstanceA(Runnable action) { A obj = new A();   action.run(); return obj; }
        static B    testNewInstanceB(Runnable action) { B obj = new B();   action.run(); return obj; }

        static int  testGetField(A recv, Runnable action)      { int v = recv.f; action.run(); return v; }
        static void testPutField(A recv, Runnable action)      { recv.f = 1;     action.run(); }
        static void testInvokeVirtual(A recv, Runnable action) { recv.m();       action.run(); }

        static void runTests() {
            checkBlockingAction(Test::testInvokeStatic);       // invokestatic
            checkBlockingAction(Test::testInvokeStaticNative); // invokestatic
            checkBlockingAction(Test::testInvokeStaticSync);   // invokestatic
            checkBlockingAction(Test::testGetStatic);          // getstatic
            checkBlockingAction(Test::testPutStatic);          // putstatic
            checkBlockingAction(Test::testNewInstanceA);       // new

            A recv = testNewInstanceB(NON_BLOCKING.get()); // trigger B initialization
            checkNonBlockingAction(Test::testNewInstanceB); // new: NO BLOCKING: same thread: A being initialized, B fully initialized

            checkNonBlockingAction(recv, Test::testGetField);      // getfield
            checkNonBlockingAction(recv, Test::testPutField);      // putfield
            checkNonBlockingAction(recv, Test::testInvokeVirtual); // invokevirtual
        }

        static void warmup() {
            for (int i = 0; i < 20_000; i++) {
                testInvokeStatic(      NON_BLOCKING_WARMUP);
                testInvokeStaticNative(NON_BLOCKING_WARMUP);
                testInvokeStaticSync(  NON_BLOCKING_WARMUP);
                testGetStatic(         NON_BLOCKING_WARMUP);
                testPutStatic(         NON_BLOCKING_WARMUP);
                testNewInstanceA(      NON_BLOCKING_WARMUP);
                testNewInstanceB(      NON_BLOCKING_WARMUP);

                testGetField(new B(),      NON_BLOCKING_WARMUP);
                testPutField(new B(),      NON_BLOCKING_WARMUP);
                testInvokeVirtual(new B(), NON_BLOCKING_WARMUP);
            }
        }

        static void run() {
            execute(ExceptionInInitializerError.class, () -> triggerInitialization(A.class));

            ensureFinished();
        }
    }

    // ============================================================================================================== //

    static void execute(Class<? extends Throwable> expectedExceptionClass, Runnable action) {
        try {
            action.run();
            if (THROW)  throw new AssertionError("no exception thrown");
        } catch (Throwable e) {
            if (THROW) {
                if (e.getClass() == expectedExceptionClass) {
                    // expected
                } else {
                    String msg = String.format("unexpected exception thrown: expected %s, caught %s",
                            expectedExceptionClass.getName(), e.getClass().getName());
                    throw new AssertionError(msg, e);
                }
            } else {
                throw new AssertionError("no exception expected", e);
            }
        }
    }

    static final List<Thread> BLOCKED_THREADS = Collections.synchronizedList(new ArrayList<>());
    static final Consumer<Thread> ON_BLOCK = BLOCKED_THREADS::add;

    static final Map<Thread,Throwable> FAILED_THREADS = Collections.synchronizedMap(new HashMap<>());
    static final Thread.UncaughtExceptionHandler ON_FAILURE = FAILED_THREADS::put;

    private static void ensureBlocked() {
        for (Thread thr : BLOCKED_THREADS) {
            try {
                thr.join(100);
                if (!thr.isAlive()) {
                    dump(thr);
                    throw new AssertionError("not blocked");
                }
            } catch (InterruptedException e) {
                throw new Error(e);
            }
        }
    }


    private static void ensureFinished() {
        for (Thread thr : BLOCKED_THREADS) {
            try {
                thr.join(15_000);
            } catch (InterruptedException e) {
                throw new Error(e);
            }
            if (thr.isAlive()) {
                dump(thr);
                throw new AssertionError(thr + ": still blocked");
            }
        }
        for (Thread thr : BLOCKED_THREADS) {
            if (THROW) {
                if (!FAILED_THREADS.containsKey(thr)) {
                    throw new AssertionError(thr + ": exception not thrown");
                }

                Throwable ex = FAILED_THREADS.get(thr);
                if (ex.getClass() != NoClassDefFoundError.class) {
                    throw new AssertionError(thr + ": wrong exception thrown", ex);
                }
            } else {
                if (FAILED_THREADS.containsKey(thr)) {
                    Throwable ex = FAILED_THREADS.get(thr);
                    throw new AssertionError(thr + ": exception thrown", ex);
                }
            }
        }
        if (THROW) {
            Asserts.assertEquals(BLOCKING_COUNTER.get(), 0);
        } else {
            Asserts.assertEquals(BLOCKING_COUNTER.get(), BLOCKING_ACTIONS.get());
        }

        dumpInfo();
    }

    interface TestCase0 {
        void run(Runnable runnable);
    }

    interface TestCase1<T> {
        void run(T arg, Runnable runnable);
    }

    enum Phase { BEFORE_INIT, IN_PROGRESS, FINISHED, INIT_FAILURE }

    static volatile Phase phase = Phase.BEFORE_INIT;

    static void changePhase(Phase newPhase) {
        dumpInfo();

        Phase oldPhase = phase;
        switch (oldPhase) {
            case BEFORE_INIT:
                Asserts.assertEquals(NON_BLOCKING_ACTIONS.get(), 0);
                Asserts.assertEquals(NON_BLOCKING_COUNTER.get(), 0);

                Asserts.assertEquals(BLOCKING_ACTIONS.get(),     0);
                Asserts.assertEquals(BLOCKING_COUNTER.get(),     0);
                break;
            case IN_PROGRESS:
                Asserts.assertEquals(NON_BLOCKING_COUNTER.get(), NON_BLOCKING_ACTIONS.get());

                Asserts.assertEquals(BLOCKING_COUNTER.get(), 0);
                break;
            default: throw new Error("wrong phase transition " + oldPhase);
        }
        phase = newPhase;
    }

    static void dumpInfo() {
        System.out.println("Phase: " + phase);
        System.out.println("Non-blocking actions: " + NON_BLOCKING_COUNTER.get() + " / " + NON_BLOCKING_ACTIONS.get());
        System.out.println("Blocking actions:     " + BLOCKING_COUNTER.get()     + " / " + BLOCKING_ACTIONS.get());
    }

    static final Runnable NON_BLOCKING_WARMUP = () -> {
        if (phase != Phase.IN_PROGRESS) {
            throw new AssertionError("NON_BLOCKING: wrong phase: " + phase);
        }
    };

    static Runnable disposableAction(final Phase validPhase, final AtomicInteger invocationCounter, final AtomicInteger actionCounter) {
        actionCounter.incrementAndGet();

        final AtomicBoolean cnt = new AtomicBoolean(false);
        return () -> {
            if (cnt.getAndSet(true)) {
                throw new Error("repeated invocation");
            }
            invocationCounter.incrementAndGet();
            if (phase != validPhase) {
                throw new AssertionError("NON_BLOCKING: wrong phase: " + phase);
            }
        };
    }

    @FunctionalInterface
    interface Factory<V> {
        V get();
    }

    static final AtomicInteger NON_BLOCKING_COUNTER = new AtomicInteger(0);
    static final AtomicInteger NON_BLOCKING_ACTIONS = new AtomicInteger(0);
    static final Factory<Runnable> NON_BLOCKING = () -> disposableAction(Phase.IN_PROGRESS, NON_BLOCKING_COUNTER, NON_BLOCKING_ACTIONS);

    static final AtomicInteger BLOCKING_COUNTER = new AtomicInteger(0);
    static final AtomicInteger BLOCKING_ACTIONS = new AtomicInteger(0);
    static final Factory<Runnable> BLOCKING     = () -> disposableAction(Phase.FINISHED, BLOCKING_COUNTER, BLOCKING_ACTIONS);

    static void checkBlockingAction(TestCase0 r) {
        r.run(NON_BLOCKING.get()); // same thread
        checkBlocked(ON_BLOCK, ON_FAILURE, r); // different thread
    }

    static void checkNonBlockingAction(TestCase0 r) {
        r.run(NON_BLOCKING.get());
        checkNotBlocked(r); // different thread
    }

    static <T> void checkNonBlockingAction(T recv, TestCase1<T> r) {
        r.run(recv, NON_BLOCKING.get()); // same thread
        checkNotBlocked((action) -> r.run(recv, action)); // different thread
    }

    static void triggerInitialization(Class<?> cls) {
        try {
            Class<?> loadedClass = Class.forName(cls.getName(), true, cls.getClassLoader());
            if (loadedClass != cls) {
                throw new Error("wrong class");
            }
        } catch (ClassNotFoundException e) {
            throw new Error(e);
        }
    }

    static void checkBlocked(Consumer<Thread> onBlockHandler, Thread.UncaughtExceptionHandler onException, TestCase0 r) {
        Thread thr = new Thread(() -> {
            try {
                r.run(BLOCKING.get());
                System.out.println("Thread " + Thread.currentThread() + ": Finished successfully");
            } catch(Throwable e) {
                System.out.println("Thread " + Thread.currentThread() + ": Exception thrown: " + e);
                if (!THROW) {
                    e.printStackTrace();
                }
                throw e;
            }
        } );
        thr.setUncaughtExceptionHandler(onException);

        thr.start();
        try {
            thr.join(100);

            dump(thr);
            if (thr.isAlive()) {
                onBlockHandler.accept(thr); // blocked
            } else {
                throw new AssertionError("not blocked");
            }
        } catch (InterruptedException e) {
            throw new Error(e);
        }
    }

    static void checkNotBlocked(TestCase0 r) {
        Thread thr = new Thread(() -> r.run(NON_BLOCKING.get()));

        thr.start();
        try {
            thr.join(15_000);
            if (thr.isAlive()) {
                dump(thr);
                throw new AssertionError("blocked");
            }
        } catch (InterruptedException e) {
            throw new Error(e);
        }
    }

    static void maybeThrow() {
        if (THROW) {
            changePhase(Phase.INIT_FAILURE);
            throw new RuntimeException("failed class initialization");
        }
    }

    private static void dump(Thread thr) {
        System.out.println("Thread: " + thr);
        System.out.println("Thread state: " + thr.getState());
        if (thr.isAlive()) {
            for (StackTraceElement frame : thr.getStackTrace()) {
                System.out.println(frame);
            }
        } else {
            if (FAILED_THREADS.containsKey(thr)) {
                System.out.println("Failed with an exception: ");
                FAILED_THREADS.get(thr).toString();
            } else {
                System.out.println("Finished successfully");
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Test.run();
        System.out.println("TEST PASSED");
    }
}
