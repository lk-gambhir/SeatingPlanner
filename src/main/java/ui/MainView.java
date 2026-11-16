package ui;

import java.nio.file.Path;
import java.sql.SQLException;
import persistence.ExamRepository;
import persistence.FeasibilityRepository;
import persistence.PlanRepository;
import persistence.RoomRepository;
import persistence.SqliteConnectionFactory;
import persistence.StaffRepository;
import persistence.StudentRepository;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
/** Main JavaFX window and dashboard wiring. */
public final class MainView {
    public void show(Stage stage, Path databasePath) throws SQLException {
        Label eyebrow = new Label("EXAM OPERATIONS");
        eyebrow.getStyleClass().add("eyebrow");

        Label title = new Label("Seating and invigilation planner");
        title.getStyleClass().add("page-title");

        Label subtitle = new Label("Manage campus exam logistics from one place.");
        subtitle.getStyleClass().add("page-subtitle");

        VBox header = new VBox(6, eyebrow, title, subtitle);
        header.getStyleClass().add("page-header");

        SqliteConnectionFactory connections = new SqliteConnectionFactory(databasePath);
        StaffDashboard staffDashboard = new StaffDashboard(new StaffRepository(connections));
        Tab invigilators = new Tab("Invigilators", staffDashboard);
        invigilators.setClosable(false);
        RoomDashboard roomDashboard = new RoomDashboard(new RoomRepository(connections));
        Tab rooms = new Tab("Rooms", roomDashboard);
        rooms.setClosable(false);
        StudentDashboard studentDashboard = new StudentDashboard(new StudentRepository(connections));
        Tab students = new Tab("Students", studentDashboard);
        students.setClosable(false);
        Tab exams = new Tab("Exam planning", new ExamImportDashboard(
                new ExamRepository(connections), new FeasibilityRepository(connections), new PlanRepository(connections),
                new StudentRepository(connections)));
        exams.setClosable(false);
        TabPane tabs = new TabPane(
                invigilators,
                rooms,
                students,
                exams);
        tabs.getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) -> {
            if (selected == students) studentDashboard.refreshData();
            else if (selected == invigilators) staffDashboard.refreshData();
            else if (selected == rooms) roomDashboard.refreshData();
        });
        tabs.getStyleClass().add("workspace-tabs");

        Label database = new Label("Local database  ·  " + databasePath);
        database.getStyleClass().add("status-bar");

        BorderPane layout = new BorderPane();
        layout.setTop(header);
        layout.setCenter(tabs);
        layout.setBottom(database);
        BorderPane.setMargin(tabs, new Insets(0, 28, 16, 28));

        Scene scene = new Scene(layout, 1120, 760);
        scene.getStylesheets().add(getClass().getResource("/ui/app.css").toExternalForm());
        stage.setTitle("Thapar Exam Planner");
        stage.setMinWidth(900);
        stage.setMinHeight(620);
        stage.setScene(scene);
        stage.show();
    }
}
