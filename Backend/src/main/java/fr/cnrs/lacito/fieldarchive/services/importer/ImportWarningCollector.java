package fr.cnrs.lacito.fieldarchive.services.importer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Collects the warnings logged during an import, so that they can be shown to the user: the
 * plugin API has no warning channel, and plugins (lameta2rico) log what they skip or guess with
 * SLF4J. Only the events of the importing thread, at WARN or above, and not from FieldArchive
 * itself, are kept.
 */
public final class ImportWarningCollector implements AutoCloseable {

    static final int MAX_WARNINGS = 200;
    private static final String OWN_PACKAGE = "fr.cnrs.lacito.fieldarchive";

    private final List<String> warnings = Collections.synchronizedList(new ArrayList<>());
    private int dropped;
    private final Logger root;
    private final AppenderBase<ILoggingEvent> appender;

    private ImportWarningCollector(Logger root, String threadName) {
        this.root = root;
        if (root == null) {
            this.appender = null;
            return;
        }
        this.appender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                if (!threadName.equals(event.getThreadName())) return;
                if (!event.getLevel().isGreaterOrEqual(Level.WARN)) return;
                if (event.getLoggerName().startsWith(OWN_PACKAGE)) return;
                synchronized (warnings) {
                    if (warnings.size() < MAX_WARNINGS) warnings.add(event.getFormattedMessage());
                    else dropped++;
                }
            }
        };
        appender.setName("import-warnings-" + threadName);
        appender.setContext(root.getLoggerContext());
        appender.start();
        root.addAppender(appender);
    }

    /** Starts collecting on the current thread; a no-op when the logging backend is not Logback. */
    public static ImportWarningCollector start() {
        Logger root = LoggerFactory.getILoggerFactory() instanceof LoggerContext context
                ? context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
                : null;
        return new ImportWarningCollector(root, Thread.currentThread().getName());
    }

    /** The warnings so far, plus a last line counting the ones not kept. */
    public List<String> warnings() {
        synchronized (warnings) {
            List<String> out = new ArrayList<>(warnings);
            if (dropped > 0) out.add("… and " + dropped + " more warning(s), see the application log.");
            return out;
        }
    }

    @Override
    public void close() {
        if (appender != null) {
            root.detachAppender(appender);
            appender.stop();
        }
    }
}
