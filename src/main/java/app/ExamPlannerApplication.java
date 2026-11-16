package app;

import persistence.DatabaseBootstrap;
import ui.MainView;

import javafx.application.Application;
import javafx.scene.control.Alert;
import javafx.stage.Stage;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Application entry point. */
public final class ExamPlannerApplication extends Application {
    private static final Logger LOGGER = Logger.getLogger(ExamPlannerApplication.class.getName());

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        Path databasePath = resolveDatabasePath();
        try {
            DatabaseBootstrap.initialize(databasePath);
            new MainView().show(stage, databasePath);
        } catch (Exception exception) {
            LOGGER.log(Level.SEVERE, "Could not initialize the application database", exception);
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Startup failed");
            alert.setHeaderText("The local database could not be prepared.");
            String details = exception.getMessage();
            alert.setContentText("Database: " + databasePath
                    + "\nCheck that its folder is writable and that the database file is not locked."
                    + (details == null || details.isBlank() ? "" : "\n\nDetails: " + details));
            alert.showAndWait();
            stage.close();
            return;
        }

    }

    private Path resolveDatabasePath() {
        if (!getParameters().getRaw().isEmpty() && !getParameters().getRaw().getFirst().isBlank()) {
            return Path.of(getParameters().getRaw().getFirst()).toAbsolutePath().normalize();
        }
        String userHome = System.getProperty("user.home");
        return Path.of(userHome, ".thapar-exam-planner", "exam-planner.db");
    }
}
