package dev.orbit.cosmetic.client;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.nio.file.Path;

/**
 * Orbit's own log in {@code gg.orbit.mod/logs} (see {@link OrbitData#logFile()}): everything the "Orbit" logger
 * writes also goes there, besides Minecraft's log.
 */
public final class OrbitLog {
	private static boolean installed;

	private OrbitLog() {
	}

	public static synchronized void install() {
		if (installed) {
			return;
		}
		installed = true;
		try {
			Path file = OrbitData.logFile();
			LoggerContext context = (LoggerContext) LogManager.getContext(false);
			Configuration configuration = context.getConfiguration();
			PatternLayout layout = PatternLayout.newBuilder().withConfiguration(configuration)
				.withPattern("[%d{yyyy-MM-dd HH:mm:ss}] [%t/%level] %msg%n%throwable").build();
			FileAppender appender = FileAppender.newBuilder().setName("OrbitFile").withFileName(file.toString()).withAppend(false).setLayout(layout)
				.setConfiguration(configuration).build();
			appender.start();
			configuration.addAppender(appender);
			LoggerConfig logger = configuration.getLoggerConfig("Orbit");
			if (!"Orbit".equals(logger.getName())) {
				// Its own logger config, passing on to Minecraft's as before.
				logger = new LoggerConfig("Orbit", Level.INFO, true);
				configuration.addLogger("Orbit", logger);
			}
			logger.addAppender(appender, Level.INFO, null);
			context.updateLoggers();
		} catch (Exception | LinkageError e) {
			OrbitClient.LOGGER.warn("Orbit's own log is off: {}", e.toString());
		}
	}
}
