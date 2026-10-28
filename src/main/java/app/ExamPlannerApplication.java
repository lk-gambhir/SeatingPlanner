package app;

import persistence.DatabaseBootstrap;
import ui.MainFrame;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Application entry point. */
public final class ExamPlannerApplication {
    private static final Logger LOGGER = Logger.getLogger(ExamPlannerApplication.class.getName());

    private ExamPlannerApplication() {
    }

    public static void main(String[] args) {
        Path databasePath = resolveDatabasePath(args);
        try {
            DatabaseBootstrap.initialize(databasePath);
        } catch (Exception exception) {
            LOGGER.log(Level.SEVERE, "Could not initialize the application database", exception);
            if (!GraphicsEnvironment.isHeadless()) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                        null,
                        "The application could not prepare its local database.\n\n"
                                + exception.getMessage(),
                        "Startup failed",
                        JOptionPane.ERROR_MESSAGE));
            }
            return;
        }

        SwingUtilities.invokeLater(() -> {
            setSystemLookAndFeel();
            new MainFrame(databasePath).setVisible(true);
        });
    }

    private static Path resolveDatabasePath(String[] args) {
        if (args.length > 0 && !args[0].isBlank()) {
            return Path.of(args[0]).toAbsolutePath().normalize();
        }
        String userHome = System.getProperty("user.home");
        return Path.of(userHome, ".thapar-exam-planner", "exam-planner.db");
    }

    private static void setSystemLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception exception) {
            LOGGER.log(Level.FINE, "Using the default Swing look and feel", exception);
        }
    }
}
