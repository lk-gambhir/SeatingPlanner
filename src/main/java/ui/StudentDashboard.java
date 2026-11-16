package ui;

import domain.Student;
import importexport.StudentCsvImporter;
import importexport.StudentImportPreview;
import importexport.EnrollmentCsvImporter;
import importexport.EnrollmentImportPreview;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import persistence.StudentRepository;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Student profile list, filters, and registration summary. */
public final class StudentDashboard extends BorderPane {
    private final StudentRepository repository;
    private final ObservableList<Student> allStudents = FXCollections.observableArrayList();
    private final FilteredList<Student> visibleStudents = new FilteredList<>(allStudents);
    private final TableView<Student> table = new TableView<>();
    private final TableView<StudentRepository.CourseDemand> demandTable = new TableView<>();
    private final TextField search = new TextField();
    private final ChoiceBox<String> program = new ChoiceBox<>();
    private final ChoiceBox<String> semester = new ChoiceBox<>();
    private final ChoiceBox<String> section = new ChoiceBox<>();
    private final ChoiceBox<String> active = new ChoiceBox<>(FXCollections.observableArrayList("All", "Active", "Inactive"));
    private final ChoiceBox<String> enrollmentEvent = new ChoiceBox<>();
    private final Button importEnrollments = new Button("Import enrollments CSV");
    private final Label enrollmentStatus = new Label("Select an event to import registrations.");
    private final Label summary = new Label();
    private final Label count = new Label();
    private final Label demandSummary = new Label("Choose an imported exam to view its PDF course counts.");
    private final ListView<StudentRepository.Registration> registrations = new ListView<>();
    private boolean eventHasRoster;

    public StudentDashboard(StudentRepository repository) throws SQLException {
        this.repository = repository;
        buildTable();
        buildDemandTable();
        buildControls();
        table.getSelectionModel().selectedItemProperty().addListener((observable, oldStudent, student) -> showRegistrations(student));
        setCenter(buildViews());
        BorderPane.setMargin(getCenter(), new Insets(10, 24, 0, 24));
        registrations.setPlaceholder(new Label("No registrations for this student."));
        registrations.setCellFactory(list -> new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(StudentRepository.Registration item, boolean empty) {
                super.updateItem(item, empty); setText(empty || item == null ? null : item.toString());
            }
        });
        Button registrationStatus = new Button("Withdraw / restore registration");
        registrationStatus.setOnAction(event -> changeRegistrationStatus());
        VBox details = new VBox(6, new Label("Selected student registrations"), registrations, registrationStatus);
        details.setPadding(new Insets(10, 24, 14, 24));
        registrations.setPrefHeight(110);
        setBottom(details);
        refresh();
    }

    private void buildTable() {
        column("Student ID", Student::studentId, 160);
        column("Roll number", Student::rollNumber, 160);
        column("Name", Student::fullName, 230);
        column("Program", student -> text(student.program()), 120);
        column("Semester", student -> student.semester() == null ? "" : student.semester().toString(), 90);
        column("Section", student -> text(student.section()), 100);
        column("Registrations", student -> String.valueOf(student.enrollmentCount()), 110);
        column("Status", student -> student.active() ? "Active" : "Inactive", 90);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setItems(visibleStudents);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("No students match these filters."));
    }

    private void buildDemandTable() {
        demandColumn("Date", StudentRepository.CourseDemand::sessionDate, 130);
        demandColumn("Start", StudentRepository.CourseDemand::startTime, 100);
        demandColumn("Course", StudentRepository.CourseDemand::courseCode, 120);
        demandColumn("Course name", item -> text(item.courseName()), 240);
        demandColumn("PDF candidates", item -> String.valueOf(item.candidateCount()), 120);
        demandColumn("Registered", item -> String.valueOf(item.registeredCount()), 100);
        demandColumn("Roster check", this::demandStatus, 190);
        demandTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        demandTable.setPlaceholder(new Label("No course demand for this exam."));
    }

    private void demandColumn(String title, java.util.function.Function<StudentRepository.CourseDemand, String> value,
            double width) {
        TableColumn<StudentRepository.CourseDemand, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        column.setPrefWidth(width);
        demandTable.getColumns().add(column);
    }

    private String demandStatus(StudentRepository.CourseDemand demand) {
        if (!eventHasRoster) return "Count-only · no roster";
        int difference = demand.registeredCount() - demand.candidateCount();
        return difference == 0 ? "Matched" : "Mismatch · " + (difference > 0 ? "+" : "") + difference;
    }

    private TabPane buildViews() {
        Tab profiles = new Tab("Student profiles", table);
        Tab demand = new Tab("Exam course demand", new VBox(10, demandSummary, demandTable));
        profiles.setClosable(false);
        demand.setClosable(false);
        return new TabPane(profiles, demand);
    }

    private void column(String title, java.util.function.Function<Student, String> value, double width) {
        TableColumn<Student, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        column.setPrefWidth(width);
        table.getColumns().add(column);
    }

    private void buildControls() {
        search.setPromptText("Search student ID, roll number, or name");
        search.textProperty().addListener((observable, oldValue, newValue) -> applyFilter());
        for (ChoiceBox<String> filter : List.of(program, semester, section, active)) filter.setOnAction(event -> applyFilter());
        Button add = new Button("Add student"); add.setOnAction(event -> editStudent(null));
        Button edit = new Button("Edit selected"); edit.setOnAction(event -> {
            if (table.getSelectionModel().getSelectedItems().size() != 1) {
                info("Select one student", "Choose exactly one student row to edit."); return;
            }
            editStudent(table.getSelectionModel().getSelectedItem());
        });
        Button status = new Button("Change status"); status.setOnAction(event -> toggleSelected());
        Button selectVisible = new Button("Select visible");
        selectVisible.setOnAction(event -> { table.getSelectionModel().clearSelection(); table.getSelectionModel().selectAll(); });
        Button clearSelection = new Button("Clear selection");
        clearSelection.setOnAction(event -> table.getSelectionModel().clearSelection());
        Button setActive = new Button("Set selected active");
        Button setInactive = new Button("Set selected inactive");
        setActive.setOnAction(event -> setSelectedStatus(true, setActive, setInactive));
        setInactive.setOnAction(event -> setSelectedStatus(false, setActive, setInactive));
        Button importCsv = new Button("Import CSV"); importCsv.setOnAction(event -> importCsv(importCsv));
        Button exportCsv = new Button("Export CSV"); exportCsv.setOnAction(event -> exportCsv(exportCsv));
        HBox filters = new HBox(10, search, program, semester, section, active, count);
        importEnrollments.setDisable(true);
        enrollmentEvent.setOnAction(event -> {
            importEnrollments.setDisable(enrollmentEvent.getValue() == null);
            refreshCourseDemand();
        });
        importEnrollments.setOnAction(event -> importEnrollments());
        Button exportEnrollments = new Button("Export enrollments CSV");
        exportEnrollments.setOnAction(event -> exportEnrollments());
        HBox actions = new HBox(8, add, edit, status, importCsv, exportCsv);
        HBox selectionActions = new HBox(8, selectVisible, clearSelection, setActive, setInactive);
        HBox enrollmentControls = new HBox(8, new Label("Target exam"), enrollmentEvent,
                importEnrollments, exportEnrollments, enrollmentStatus);
        Label countOnlyNote = new Label("Named registrations are optional. Without a roster, seating uses imported course counts and anonymous candidate labels.");
        VBox controls = new VBox(12, summary, filters, actions, selectionActions, enrollmentControls, countOnlyNote);
        controls.setPadding(new Insets(18, 24, 8, 24));
        setTop(controls);
    }

    private void applyFilter() {
        table.getSelectionModel().clearSelection();
        String query = search.getText().trim().toLowerCase();
        String selectedProgram = program.getValue();
        String selectedSemester = semester.getValue();
        String selectedSection = section.getValue();
        String selectedActive = active.getValue();
        visibleStudents.setPredicate(student -> {
            boolean matchesQuery = query.isEmpty() || student.studentId().toLowerCase().contains(query)
                    || student.rollNumber().toLowerCase().contains(query) || student.fullName().toLowerCase().contains(query);
            boolean matchesProgram = "All".equals(selectedProgram) || text(student.program()).equals(selectedProgram);
            boolean matchesSemester = "All".equals(selectedSemester)
                    || String.valueOf(student.semester()).equals(selectedSemester);
            boolean matchesSection = "All".equals(selectedSection) || text(student.section()).equals(selectedSection);
            boolean matchesActive = "All".equals(selectedActive) || ("Active".equals(selectedActive) == student.active());
            return matchesQuery && matchesProgram && matchesSemester && matchesSection && matchesActive;
        });
        count.setText(visibleStudents.size() + " shown · " + allStudents.size() + " total");
    }

    private void refresh() throws SQLException {
        allStudents.setAll(repository.findAll());
        String selectedEvent = enrollmentEvent.getValue();
        List<String> eventCodes = repository.findEventCodes();
        enrollmentEvent.setItems(FXCollections.observableArrayList(eventCodes));
        enrollmentEvent.setValue(eventCodes.contains(selectedEvent) ? selectedEvent : null);
        importEnrollments.setDisable(enrollmentEvent.getValue() == null);
        refreshCourseDemand();
        setOptions(program, allStudents.stream().map(Student::program).filter(value -> value != null).distinct().sorted().toList());
        setOptions(semester, allStudents.stream().map(Student::semester).filter(value -> value != null)
                .map(String::valueOf).distinct().sorted().toList());
        setOptions(section, allStudents.stream().map(Student::section).filter(value -> value != null).distinct().sorted().toList());
        if (active.getValue() == null) active.setValue("All");
        long activeCount = allStudents.stream().filter(Student::active).count();
        long registrations = allStudents.stream().mapToLong(Student::enrollmentCount).sum();
        boolean isDemo = allStudents.stream().anyMatch(student -> "DEMO".equalsIgnoreCase(student.dataLabel()));
        summary.setText((isDemo ? "[DEMO DATA] " : "") + allStudents.size() + " students · " + activeCount + " active · " + registrations + " registrations");
        applyFilter();
    }

    private void refreshCourseDemand() {
        String eventCode = enrollmentEvent.getValue();
        if (eventCode == null) {
            eventHasRoster = false;
            demandTable.getItems().clear();
            demandSummary.setText("Choose an imported exam to view its PDF course counts.");
            return;
        }
        try {
            List<StudentRepository.CourseDemand> demand = repository.findCourseDemand(eventCode);
            eventHasRoster = demand.stream().anyMatch(item -> item.rosterRows() > 0);
            demandTable.setItems(FXCollections.observableArrayList(demand));
            int candidates = demand.stream().mapToInt(StudentRepository.CourseDemand::candidateCount).sum();
            int registered = demand.stream().mapToInt(StudentRepository.CourseDemand::registeredCount).sum();
            long sessions = demand.stream().map(item -> item.sessionDate() + " " + item.startTime()).distinct().count();
            long mismatches = eventHasRoster ? demand.stream().filter(item ->
                    item.registeredCount() != item.candidateCount()).count() : 0;
            demandSummary.setText(eventCode + " · " + sessions + " sessions · " + demand.size() + " courses · "
                    + candidates + " PDF candidates · " + (eventHasRoster
                            ? registered + " active registrations · " + mismatches + " course mismatches"
                            : "count-only; no roster imported"));
        } catch (SQLException exception) {
            eventHasRoster = false;
            demandTable.getItems().clear();
            demandSummary.setText("Could not load course demand.");
            error("Could not load exam course demand", exception);
        }
    }

    public void refreshData() {
        try { refresh(); } catch (SQLException exception) { error("Could not refresh students", exception); }
    }

    private static void setOptions(ChoiceBox<String> choice, List<String> options) {
        String previous = choice.getValue();
        List<String> values = new ArrayList<>(List.of("All")); values.addAll(options);
        choice.setItems(FXCollections.observableArrayList(values));
        choice.setValue(values.contains(previous) ? previous : "All");
    }

    private void showRegistrations(Student student) {
        if (student == null) { registrations.getItems().clear(); return; }
        try {
            List<StudentRepository.Registration> rows = repository.findRegistrations(student.studentId());
            registrations.setItems(FXCollections.observableArrayList(rows));
        } catch (SQLException exception) { error("Could not read registrations", exception); }
    }

    private void changeRegistrationStatus() {
        Student student = table.getSelectionModel().getSelectedItem();
        StudentRepository.Registration registration = registrations.getSelectionModel().getSelectedItem();
        if (student == null || registration == null) return;
        String status = registration.status().equals("REGISTERED") ? "WITHDRAWN" : "REGISTERED";
        if (status.equals("REGISTERED") && !student.active()) {
            info("Student is inactive", "Reactivate the student profile before restoring a registration."); return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Set " + registration.courseCode() + " for " + student.rollNumber() + " to " + status + "?",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("Change registration status"); confirm.setHeaderText("Review roster correction");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception {
                repository.setRegistrationStatus(registration.enrollmentId(), status); return null;
            }
        };
        task.setOnSucceeded(event -> {
            try {
                refresh();
                table.getSelectionModel().select(allStudents.stream().filter(item -> item.studentId().equals(student.studentId()))
                        .findFirst().orElse(null));
            } catch (SQLException exception) { error("Registration changed but dashboard refresh failed", exception); }
        });
        task.setOnFailed(event -> error("Could not change registration status", task.getException()));
        Thread worker = new Thread(task, "registration-status-update"); worker.setDaemon(true); worker.start();
    }

    private void editStudent(Student current) {
        Dialog<Student> dialog = new Dialog<>();
        dialog.setTitle(current == null ? "Add student" : "Edit student");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        TextField id = field(current == null ? "" : current.studentId());
        TextField roll = field(current == null ? "" : current.rollNumber());
        TextField name = field(current == null ? "" : current.fullName());
        TextField programName = field(current == null ? "" : text(current.program()));
        TextField semesterNumber = field(current == null || current.semester() == null ? "" : current.semester().toString());
        TextField section = field(current == null ? "" : text(current.section()));
        id.setDisable(current != null);
        VBox form = new VBox(8, new Label("Student ID"), id, new Label("Roll number"), roll,
                new Label("Full name"), name, new Label("Program"), programName,
                new Label("Semester (optional)"), semesterNumber, new Label("Section"), section);
        form.setPadding(new Insets(12)); dialog.getDialogPane().setContent(form);
        Button ok = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        ok.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            try { createStudent(id, roll, name, programName, semesterNumber, section, current); }
            catch (IllegalArgumentException exception) { event.consume(); error("Check the student details", exception); }
        });
        dialog.setResultConverter(button -> button == ButtonType.OK
                ? createStudent(id, roll, name, programName, semesterNumber, section, current) : null);
        Optional<Student> result = dialog.showAndWait();
        if (result.isEmpty()) return;
        try {
            if (current == null) repository.insert(result.get()); else repository.update(result.get());
            refresh();
        } catch (SQLException | IllegalArgumentException exception) { error("Could not save student", exception); }
    }

    private static Student createStudent(TextField id, TextField roll, TextField name, TextField program,
            TextField semester, TextField section, Student current) {
        Integer semesterValue = semester.getText().isBlank() ? null : Integer.valueOf(semester.getText());
        return new Student(id.getText(), roll.getText(), name.getText(), program.getText(), semesterValue,
                section.getText(), current == null || current.active(), current == null ? "OPERATIONAL" : current.dataLabel(),
                current == null ? 0 : current.enrollmentCount());
    }

    private void toggleSelected() {
        Student selected = table.getSelectionModel().getSelectedItem(); if (selected == null) return;
        try { repository.setActive(selected.studentId(), !selected.active()); refresh(); }
        catch (SQLException | IllegalArgumentException exception) { error("Could not change student status", exception); }
    }

    private void setSelectedStatus(boolean makeActive, Button activeButton, Button inactiveButton) {
        List<String> studentIds = table.getSelectionModel().getSelectedItems().stream()
                .filter(student -> student.active() != makeActive).map(Student::studentId).toList();
        if (studentIds.isEmpty()) {
            info("No status changes needed", "Select visible students whose status needs to change.");
            return;
        }
        String status = makeActive ? "active" : "inactive";
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Set " + studentIds.size() + " selected students to " + status + "?",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("Change student status");
        confirm.setHeaderText("Confirm the selected student updates");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;

        activeButton.setDisable(true);
        inactiveButton.setDisable(true);
        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception {
                repository.setActive(studentIds, makeActive);
                return null;
            }
        };
        task.setOnSucceeded(event -> {
            activeButton.setDisable(false);
            inactiveButton.setDisable(false);
            try { refresh(); }
            catch (SQLException exception) { error("Status changed but dashboard refresh failed", exception); }
        });
        task.setOnFailed(event -> {
            activeButton.setDisable(false);
            inactiveButton.setDisable(false);
            error("Could not change selected student statuses", task.getException());
        });
        Thread worker = new Thread(task, "student-status-update");
        worker.setDaemon(true);
        worker.start();
    }

    private void importCsv(Button importButton) {
        FileChooser chooser = new FileChooser(); chooser.setTitle("Choose students CSV");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"));
        var file = chooser.showOpenDialog(getScene().getWindow()); if (file == null) return;
        importButton.setDisable(true);
        Task<StudentImportPreview> task = new Task<>() {
            @Override protected StudentImportPreview call() throws Exception {
                return new StudentCsvImporter().preview(file.toPath(), repository.findAll());
            }
        };
        task.setOnSucceeded(event -> {
            importButton.setDisable(false);
            showPreview(task.getValue(), importButton);
        });
        task.setOnFailed(event -> {
            importButton.setDisable(false);
            error("Could not read students CSV", task.getException());
        });
        Thread worker = new Thread(task, "student-csv-preview"); worker.setDaemon(true); worker.start();
    }

    private void importEnrollments() {
        String eventCode = enrollmentEvent.getValue();
        if (eventCode == null) return;
        FileChooser chooser = new FileChooser(); chooser.setTitle("Choose enrollments CSV");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"));
        var file = chooser.showOpenDialog(getScene().getWindow()); if (file == null) return;
        importEnrollments.setDisable(true); enrollmentStatus.setText("Checking registrations…");
        Task<EnrollmentImportPreview> task = new Task<>() {
            @Override protected EnrollmentImportPreview call() throws Exception {
                var context = repository.findEnrollmentContext(eventCode);
                return new EnrollmentCsvImporter().preview(file.toPath(), context);
            }
        };
        task.setOnSucceeded(event -> {
            importEnrollments.setDisable(enrollmentEvent.getValue() == null);
            enrollmentStatus.setText("Preview ready."); showEnrollmentPreview(task.getValue());
        });
        task.setOnFailed(event -> {
            importEnrollments.setDisable(enrollmentEvent.getValue() == null);
            enrollmentStatus.setText("Preview failed."); error("Could not read enrollments CSV", task.getException());
        });
        Thread worker = new Thread(task, "enrollment-csv-preview"); worker.setDaemon(true); worker.start();
    }

    private void showEnrollmentPreview(EnrollmentImportPreview initialPreview) {
        java.util.Set<Integer> excluded = new java.util.HashSet<>();
        TableView<EnrollmentImportPreview.Row> previewTable = new TableView<>(FXCollections.observableArrayList(initialPreview.rows()));
        previewTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        TableColumn<EnrollmentImportPreview.Row, String> rowCol = new TableColumn<>("Row");
        rowCol.setCellValueFactory(cell -> new ReadOnlyStringWrapper(String.valueOf(cell.getValue().sourceRow())));
        rowCol.setPrefWidth(55);

        TableColumn<EnrollmentImportPreview.Row, String> statusCol = new TableColumn<>("Action");
        statusCol.setCellValueFactory(cell -> new ReadOnlyStringWrapper(
                excluded.contains(cell.getValue().sourceRow()) ? "EXCLUDE" : cell.getValue().status().toString()));
        statusCol.setPrefWidth(85);

        TableColumn<EnrollmentImportPreview.Row, String> studentCol = new TableColumn<>("Student ID");
        studentCol.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().studentId()));
        studentCol.setPrefWidth(120);

        TableColumn<EnrollmentImportPreview.Row, String> courseCol = new TableColumn<>("Course");
        courseCol.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().courseCode()));
        courseCol.setPrefWidth(110);

        TableColumn<EnrollmentImportPreview.Row, String> regStatusCol = new TableColumn<>("Status");
        regStatusCol.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().registrationStatus()));
        regStatusCol.setPrefWidth(100);

        TableColumn<EnrollmentImportPreview.Row, String> msgCol = new TableColumn<>("Notes / Errors");
        msgCol.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().message()));
        msgCol.setPrefWidth(240);

        previewTable.getColumns().addAll(List.of(rowCol, statusCol, studentCol, courseCol, regStatusCol, msgCol));
        previewTable.setPrefHeight(280);

        Label header = new Label();
        Runnable updateHeader = () -> {
            int toAdd = (int) initialPreview.rows().stream()
                    .filter(r -> r.status() == EnrollmentImportPreview.Status.ADD && !excluded.contains(r.sourceRow())).count();
            int skipped = (int) initialPreview.rows().stream()
                    .filter(r -> r.status() == EnrollmentImportPreview.Status.SKIP || (r.status() == EnrollmentImportPreview.Status.ADD && excluded.contains(r.sourceRow()))).count();
            header.setText("Target: " + initialPreview.eventCode() + " · To Add: " + toAdd + " · Skipped/Excluded: " + skipped
                    + " · Errors: " + initialPreview.count(EnrollmentImportPreview.Status.ERROR) + " · Warnings: " + initialPreview.diagnostics().size());
        };
        updateHeader.run();

        Button toggleBtn = new Button("Exclude / Re-include selected valid row(s)");
        toggleBtn.setOnAction(e -> {
            for (EnrollmentImportPreview.Row row : previewTable.getSelectionModel().getSelectedItems()) {
                if (row.status() == EnrollmentImportPreview.Status.ADD) {
                    if (!excluded.add(row.sourceRow())) excluded.remove(row.sourceRow());
                }
            }
            previewTable.refresh();
            updateHeader.run();
        });

        VBox content = new VBox(8, header, previewTable, toggleBtn);
        if (!initialPreview.diagnostics().isEmpty()) {
            StringBuilder diagText = new StringBuilder("Diagnostics:\n");
            for (var d : initialPreview.diagnostics()) diagText.append(d.severity()).append(" · ").append(d.message()).append("\n");
            TextArea diagArea = new TextArea(diagText.toString());
            diagArea.setEditable(false);
            diagArea.setPrefRowCount(4);
            content.getChildren().add(diagArea);
        }
        content.setPadding(new Insets(10));

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Review enrollment import");
        dialog.setHeaderText(initialPreview.hasErrors() ? "Fix registration errors before importing."
                : "Select registrations to include/exclude before importing.");
        dialog.getDialogPane().setContent(content);
        ButtonType importType = new ButtonType("Confirm and Import", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(initialPreview.hasErrors() ? ButtonType.CLOSE : importType, ButtonType.CANCEL);
        if (initialPreview.hasErrors()) {
            dialog.showAndWait();
            return;
        }
        Optional<ButtonType> result = dialog.showAndWait();
        if (result.orElse(ButtonType.CANCEL) != importType) return;

        EnrollmentImportPreview toCommit = initialPreview.withExcludedRows(excluded);
        importEnrollments.setDisable(true); enrollmentStatus.setText("Saving registrations…");
        Task<StudentRepository.EnrollmentImportResult> save = new Task<>() {
            @Override protected StudentRepository.EnrollmentImportResult call() throws Exception {
                return repository.commitEnrollments(toCommit);
            }
        };
        save.setOnSucceeded(event -> {
            importEnrollments.setDisable(enrollmentEvent.getValue() == null);
            enrollmentStatus.setText("Import complete.");
            try { refresh(); } catch (SQLException exception) { error("Import saved but dashboard refresh failed", exception); }
            var resultCounts = save.getValue();
            info("Import complete", resultCounts.added() + " registrations added, " + resultCounts.skipped()
                    + " skipped/excluded, " + resultCounts.warnings() + " count warnings.");
        });
        save.setOnFailed(event -> {
            importEnrollments.setDisable(enrollmentEvent.getValue() == null);
            enrollmentStatus.setText("Import failed."); error("Could not save registrations", save.getException());
        });
        Thread worker = new Thread(save, "enrollment-csv-import"); worker.setDaemon(true); worker.start();
    }

    private void exportEnrollments() {
        String eventCode = enrollmentEvent.getValue();
        if (eventCode == null) return;
        FileChooser chooser = new FileChooser(); chooser.setTitle("Export event enrollments");
        chooser.setInitialFileName("enrollments-" + eventCode + ".csv");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"));
        var file = chooser.showSaveDialog(getScene().getWindow()); if (file == null) return;
        importEnrollments.setDisable(true); enrollmentStatus.setText("Exporting registrations…");
        Task<Integer> task = new Task<>() {
            @Override protected Integer call() throws Exception {
                List<StudentRepository.EnrollmentExportRow> rows = repository.findEnrollments(eventCode);
                try (CSVPrinter printer = new CSVPrinter(Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8), CSVFormat.DEFAULT)) {
                    printer.printRecord("enrollment_id", "student_id", "course_id", "session_id", "status", "data_label");
                    for (StudentRepository.EnrollmentExportRow row : rows) printer.printRecord(row.enrollmentId(), row.studentId(),
                            row.courseCode(), row.sessionId(), row.status(), row.dataLabel());
                }
                return rows.size();
            }
        };
        task.setOnSucceeded(event -> {
            importEnrollments.setDisable(enrollmentEvent.getValue() == null); enrollmentStatus.setText("Export complete.");
            info("Export complete", task.getValue() + " registrations exported for " + eventCode + ".");
        });
        task.setOnFailed(event -> {
            importEnrollments.setDisable(enrollmentEvent.getValue() == null); enrollmentStatus.setText("Export failed.");
            error("Could not export enrollments", task.getException());
        });
        Thread worker = new Thread(task, "enrollment-csv-export"); worker.setDaemon(true); worker.start();
    }

    private void showPreview(StudentImportPreview initialPreview, Button importButton) {
        java.util.Set<Integer> excluded = new java.util.HashSet<>();
        TableView<StudentImportPreview.Row> previewTable = new TableView<>(FXCollections.observableArrayList(initialPreview.rows()));
        previewTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        TableColumn<StudentImportPreview.Row, String> rowCol = new TableColumn<>("Row");
        rowCol.setCellValueFactory(cell -> new ReadOnlyStringWrapper(String.valueOf(cell.getValue().sourceRow())));
        rowCol.setPrefWidth(55);

        TableColumn<StudentImportPreview.Row, String> statusCol = new TableColumn<>("Action");
        statusCol.setCellValueFactory(cell -> new ReadOnlyStringWrapper(
                excluded.contains(cell.getValue().sourceRow()) ? "EXCLUDE" : cell.getValue().status().toString()));
        statusCol.setPrefWidth(85);

        TableColumn<StudentImportPreview.Row, String> idCol = new TableColumn<>("Student ID");
        idCol.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().student() == null ? "" : cell.getValue().student().studentId()));
        idCol.setPrefWidth(120);

        TableColumn<StudentImportPreview.Row, String> rollCol = new TableColumn<>("Roll number");
        rollCol.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().student() == null ? "" : cell.getValue().student().rollNumber()));
        rollCol.setPrefWidth(120);

        TableColumn<StudentImportPreview.Row, String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().student() == null ? "" : cell.getValue().student().fullName()));
        nameCol.setPrefWidth(180);

        TableColumn<StudentImportPreview.Row, String> msgCol = new TableColumn<>("Notes / Diffs");
        msgCol.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().message()));
        msgCol.setPrefWidth(240);

        previewTable.getColumns().addAll(List.of(rowCol, statusCol, idCol, rollCol, nameCol, msgCol));
        previewTable.setPrefHeight(300);

        Label header = new Label();
        Runnable updateHeader = () -> {
            int toAdd = (int) initialPreview.rows().stream()
                    .filter(r -> r.status() == StudentImportPreview.Status.ADD && !excluded.contains(r.sourceRow())).count();
            int skipped = (int) initialPreview.rows().stream()
                    .filter(r -> r.status() == StudentImportPreview.Status.SKIP || (r.status() == StudentImportPreview.Status.ADD && excluded.contains(r.sourceRow()))).count();
            header.setText("File: " + initialPreview.fileName() + " · To Add: " + toAdd + " · Skipped/Excluded: " + skipped
                    + " · Errors: " + initialPreview.count(StudentImportPreview.Status.ERROR));
        };
        updateHeader.run();

        Button toggleBtn = new Button("Exclude / Re-include selected valid row(s)");
        toggleBtn.setOnAction(e -> {
            for (StudentImportPreview.Row row : previewTable.getSelectionModel().getSelectedItems()) {
                if (row.status() == StudentImportPreview.Status.ADD) {
                    if (!excluded.add(row.sourceRow())) excluded.remove(row.sourceRow());
                }
            }
            previewTable.refresh();
            updateHeader.run();
        });

        VBox content = new VBox(8, header, previewTable, toggleBtn);
        content.setPadding(new Insets(10));

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Review student import");
        dialog.setHeaderText(initialPreview.hasErrors() ? "Fix CSV errors before importing." : "Select profiles to include/exclude before importing.");
        dialog.getDialogPane().setContent(content);
        ButtonType importType = new ButtonType("Confirm and Import", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(initialPreview.hasErrors() ? ButtonType.CLOSE : importType, ButtonType.CANCEL);
        if (initialPreview.hasErrors()) {
            dialog.showAndWait();
            return;
        }
        Optional<ButtonType> result = dialog.showAndWait();
        if (result.orElse(ButtonType.CANCEL) != importType) return;
        importButton.setDisable(true);
        StudentImportPreview toCommit = initialPreview.withExcludedRows(excluded);
        Task<Integer> save = new Task<>() {
            @Override protected Integer call() throws Exception { return repository.commitImport(toCommit); }
        };
        save.setOnSucceeded(event -> {
            importButton.setDisable(false);
            try { refresh(); } catch (SQLException exception) { error("Import saved but dashboard refresh failed", exception); }
            info("Import complete", save.getValue() + " student profiles added. " + (excluded.isEmpty() ? "" : excluded.size() + " rows excluded."));
        });
        save.setOnFailed(event -> {
            importButton.setDisable(false);
            error("Could not import students", save.getException());
        });
        Thread worker = new Thread(save, "student-csv-import"); worker.setDaemon(true); worker.start();
    }

    private void exportCsv(Button exportButton) {
        FileChooser chooser = new FileChooser(); chooser.setTitle("Export filtered students");
        chooser.setInitialFileName("students.csv");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"));
        var file = chooser.showSaveDialog(getScene().getWindow()); if (file == null) return;
        List<Student> rows = List.copyOf(visibleStudents);
        exportButton.setDisable(true);
        Task<Integer> task = new Task<>() {
            @Override protected Integer call() throws Exception {
                try (CSVPrinter printer = new CSVPrinter(Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8), CSVFormat.DEFAULT)) {
                    printer.printRecord("student_id", "roll_number", "full_name", "program", "semester", "section", "active", "data_label");
                    for (Student student : rows) printer.printRecord(student.studentId(), student.rollNumber(),
                            student.fullName(), student.program(), student.semester(), student.section(),
                            student.active(), student.dataLabel());
                }
                return rows.size();
            }
        };
        task.setOnSucceeded(event -> {
            exportButton.setDisable(false);
            info("Export complete", task.getValue() + " students exported.");
        });
        task.setOnFailed(event -> {
            exportButton.setDisable(false);
            error("Could not export students", task.getException());
        });
        Thread worker = new Thread(task, "student-csv-export");
        worker.setDaemon(true);
        worker.start();
    }

    private static TextField field(String value) { TextField field = new TextField(value); field.setMaxWidth(Double.MAX_VALUE); return field; }
    private static String text(String value) { return value == null ? "" : value; }
    private static void error(String title, Throwable exception) {
        UiFeedback.error(title, exception);
    }
    private static void error(String title, String message) {
        UiFeedback.error(title, message);
    }
    private static void info(String title, String message) {
        UiFeedback.info(title, message);
    }
}
