package ui;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/** Minimal helper for user-safe error handling and local technical logging. */
public final class UiFeedback {
    private static final Logger LOGGER = createLogger();

    private UiFeedback() {}

    public static String error(String title, Throwable exception) {
        String errorId = generateErrorId();
        logFailure(errorId, title, exception);

        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText("An unexpected error occurred. Check the local application log and provide this reference ID: " + errorId);
        alert.showAndWait();
        return errorId;
    }

    public static void error(String title, String message) {
        String errorId = generateErrorId();
        LOGGER.log(Level.WARNING, "[" + errorId + "] UI message: " + title);

        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText("Reference ID: " + errorId + "\n\n" + message);
        alert.showAndWait();
    }

    public static void info(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.showAndWait();
    }

    private static String generateErrorId() {
        return "ERR-" + Long.toHexString(System.currentTimeMillis()).toUpperCase().substring(4)
                + "-" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000)).toUpperCase();
    }

    private static Logger createLogger() {
        Logger logger = Logger.getLogger("examplanner.ui");
        try {
            Path directory = Path.of(System.getProperty("user.home"), ".thapar-exam-planner");
            Files.createDirectories(directory);
            FileHandler file = new FileHandler(directory.resolve("application.log").toString(), true);
            file.setFormatter(new SimpleFormatter());
            logger.addHandler(file);
            logger.setUseParentHandlers(false);
        } catch (IOException exception) {
            logger.log(Level.WARNING, "Local application log could not be opened (" + exception.getClass().getSimpleName() + ")");
        }
        return logger;
    }

    private static void logFailure(String errorId, String title, Throwable exception) {
        LOGGER.log(Level.SEVERE, "[" + errorId + "] UI error: " + title + " · "
                + (exception == null ? "unknown" : exception.getClass().getName()));
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            for (StackTraceElement frame : cause.getStackTrace()) LOGGER.log(Level.SEVERE, "  at " + frame);
        }
    }
}
