package steambridge.core;

import org.apache.logging.log4j.Logger;

public final class ProblemLog {
    private ProblemLog() {}

    public static void warn(
            Logger logger, LogLimiter limiter, String event, String message, Object... arguments) {
        emit(logger, limiter, event, message, false, arguments);
    }

    public static void error(
            Logger logger, LogLimiter limiter, String event, String message, Object... arguments) {
        emit(logger, limiter, event, message, true, arguments);
    }

    private static void emit(
            Logger logger,
            LogLimiter limiter,
            String event,
            String message,
            boolean error,
            Object[] arguments) {
        long suppressed = limiter.acquire(event);
        if (suppressed < 0) return;
        int count = arguments.length;
        Throwable failure =
                count > 0 && arguments[count - 1] instanceof Throwable
                        ? (Throwable) arguments[--count]
                        : null;
        Object[] context = new Object[count + (failure == null ? 2 : 3)];
        context[0] = event;
        context[1] = suppressed;
        for (int i = 0; i < count; i++)
            context[i + 2] =
                    arguments[i] instanceof String
                            ? LogText.safe((String) arguments[i])
                            : arguments[i];
        if (failure != null) {
            context[context.length - 1] = LogText.safe(failure.toString());
            message += " cause={}";
        }
        String format = "{} (suppressed={}): " + message;
        if (error) logger.error(format, context);
        else logger.warn(format, context);
        if (failure != null) logger.debug(event + " details", failure);
    }
}
