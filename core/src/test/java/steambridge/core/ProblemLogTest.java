package steambridge.core;

import static org.junit.Assert.*;

import org.apache.logging.log4j.Logger;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class ProblemLogTest {
    private static Logger logger(List<Object[]> events) {
        return (Logger)
                Proxy.newProxyInstance(
                        Logger.class.getClassLoader(),
                        new Class<?>[] {Logger.class},
                        (proxy, method, arguments) -> {
                            if (method.getName().equals("warn")
                                    || method.getName().equals("error")
                                    || method.getName().equals("debug"))
                                events.add(new Object[] {method.getName(), arguments});
                            return null;
                        });
    }

    @Test
    public void repeatedProblemIsSilentAndNextMessageCarriesSuppressionCount() {
        AtomicLong clock = new AtomicLong();
        LogLimiter limiter = new LogLimiter(4, 1000, clock::get);
        List<Object[]> events = new ArrayList<>();
        Logger logger = logger(events);
        ProblemLog.warn(logger, limiter, "voice-receive", "conn={}", 5);
        ProblemLog.warn(logger, limiter, "voice-receive", "conn={}", 5);
        assertEquals(1, events.size());
        clock.set(1_000_000_000L);
        ProblemLog.warn(logger, limiter, "voice-receive", "conn={}", 5);
        Object[] invocation = (Object[]) events.get(1)[1];
        Object[] context = (Object[]) invocation[1];
        assertEquals("voice-receive", context[0]);
        assertEquals(1L, context[1]);
        assertEquals(5, context[2]);
    }

    @Test
    public void errorKeepsConciseCauseAndMovesStackTraceToDebug() {
        List<Object[]> events = new ArrayList<>();
        Logger logger = logger(events);
        RuntimeException failure = new IllegalStateException("native\n${value}");
        ProblemLog.error(logger, new LogLimiter(1, 1000), "receive-loop", "conn={}", 7, failure);
        assertEquals(2, events.size());
        assertEquals("error", events.get(0)[0]);
        Object[] invocation = (Object[]) events.get(0)[1];
        Object[] context = (Object[]) invocation[1];
        assertEquals("java.lang.IllegalStateException: native $ {value}", context[3]);
        assertEquals("debug", events.get(1)[0]);
        assertSame(failure, ((Object[]) events.get(1)[1])[1]);
    }

    @Test
    public void externalTextCannotInjectExtraLinesOrLookups() {
        List<Object[]> events = new ArrayList<>();
        ProblemLog.warn(
                logger(events), new LogLimiter(1, 1000), "login", "reason={}", "bad\r\n${test}");
        Object[] invocation = (Object[]) events.get(0)[1];
        assertEquals("bad  $ {test}", ((Object[]) invocation[1])[2]);
    }
}
