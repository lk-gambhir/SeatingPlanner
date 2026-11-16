package ui;

import importexport.ExamImportPreview;
import importexport.ExamSchedulePdfImporter;
import importexport.PlanCsvExporter;
import persistence.ExamRepository;
import persistence.FeasibilityRepository;
import persistence.PlanRepository;
import persistence.StudentRepository;
import planning.FeasibilityAnalyzer;
import planning.GeneratedPlan;
import planning.FeasibilityReport;
import planning.PlanEditor;
import planning.SeatAssignmentPlanner;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Two-PDF schedule/count import with a review before it is saved. */
public final class ExamImportDashboard extends BorderPane {
    private final ExamRepository repository;
    private final FeasibilityRepository feasibilityRepository;
    private final PlanRepository planRepository;
    private final StudentRepository studentRepository;
    private final TableView<ExamImportPreview.Session> sessions = new TableView<>();
    private final TableView<ExamRepository.ExamSummary> savedEvents = new TableView<>();
    private final TableView<PlanRepository.PlanSummary> savedPlans = new TableView<>();
    private final Button feasibilityButton = new Button("Check feasibility");
    private final Button studentConflictButton = new Button("Check student conflicts");
    private final Button generatePlanButton = new Button("Generate seating draft");
    private final Button openPlanButton = new Button("Open saved plan");
    private final Button purgeEventButton = new Button("Purge event and plans");
    private final Label feasibilityStatus = new Label("Select a saved event to check room and staff capacity.");
    private final TextArea feasibilityDetails = new TextArea();
    private final TextField schedulePath = new TextField();
    private final TextField countsPath = new TextField();
    private final TextField eventName = new TextField("Mid-Semester Examination");
    private final TextField academicPeriod = new TextField();
    private final TextField duration = new TextField();
    private final Label status = new Label("Choose a schedule PDF and its course-count PDF.");
    private final Label summary = new Label();
    private final TextArea details = new TextArea();
    private final Button previewButton = new Button("Preview PDFs");
    private final Button editCountsButton = new Button("Correct selected session counts");
    private final Button importButton = new Button("Confirm and import");
    private ExamImportPreview preview;
    private boolean duplicateEventCode;

    public ExamImportDashboard(ExamRepository repository, FeasibilityRepository feasibilityRepository,
            PlanRepository planRepository, StudentRepository studentRepository) throws SQLException {
        this.repository = repository;
        this.feasibilityRepository = feasibilityRepository;
        this.planRepository = planRepository;
        this.studentRepository = studentRepository;
        setCenter(buildTabs());
        refreshSavedEvents();
    }

    private TabPane buildTabs() {
        TabPane tabs = new TabPane(new Tab("Import exam", buildImportView()), new Tab("Saved exams", buildSavedView()));
        for (Tab tab : tabs.getTabs()) tab.setClosable(false);
        return tabs;
    }

    private VBox buildSavedView() {
        feasibilityButton.setDisable(true);
        feasibilityButton.setOnAction(event -> checkFeasibility());
        studentConflictButton.setDisable(true);
        studentConflictButton.setOnAction(event -> checkStudentConflicts());
        generatePlanButton.setDisable(true);
        generatePlanButton.setOnAction(event -> generatePlan());
        openPlanButton.setDisable(true);
        openPlanButton.setOnAction(event -> openSavedPlan());
        purgeEventButton.setDisable(true);
        purgeEventButton.setOnAction(event -> purgeSelectedEvent());
        savedPlans.getColumns().addAll(List.of(
                planColumn("Version", plan -> "v" + plan.version(), 90),
                planColumn("Status", PlanRepository.PlanSummary::status, 110),
                planColumn("Event data", plan -> plan.stale() ? "STALE" : "CURRENT", 90),
                planColumn("Rooms", plan -> String.valueOf(plan.roomCount()), 80),
                planColumn("Seats", plan -> String.valueOf(plan.seatCount()), 90),
                planColumn("Blockers", plan -> String.valueOf(plan.blockerCount()), 90),
                planColumn("Created", PlanRepository.PlanSummary::createdAt, 200)));
        savedEvents.getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) -> {
            feasibilityButton.setDisable(selected == null);
            studentConflictButton.setDisable(selected == null);
            generatePlanButton.setDisable(selected == null);
            purgeEventButton.setDisable(selected == null);
            feasibilityDetails.clear();
            feasibilityStatus.setText(selected == null ? "Select a saved event to check room and staff capacity."
                    : "Ready to check " + selected.eventCode() + ".");
            savedPlans.getItems().clear();
            if (selected != null) refreshSavedPlans(selected.eventCode());
        });
        savedPlans.getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) -> {
            openPlanButton.setDisable(selected == null);
        });
        feasibilityDetails.setEditable(false); feasibilityDetails.setWrapText(true);
        VBox content = new VBox(10, new HBox(10, feasibilityButton, studentConflictButton, generatePlanButton,
                purgeEventButton, feasibilityStatus), savedEvents,
                new Label("Saved plan versions"), savedPlans, new HBox(8, openPlanButton),
                new Label("Feasibility report · course-to-room estimate"), feasibilityDetails);
        content.setPadding(new Insets(16, 24, 20, 24));
        VBox.setVgrow(savedEvents, javafx.scene.layout.Priority.ALWAYS);
        VBox.setVgrow(savedPlans, javafx.scene.layout.Priority.ALWAYS);
        VBox.setVgrow(feasibilityDetails, javafx.scene.layout.Priority.ALWAYS);
        return content;
    }

    private VBox buildImportView() {
        sessions.getColumns().addAll(List.of(
                column("Date", session -> session.date().toString(), 120),
                column("Start", session -> session.startTime().format(DateTimeFormatter.ofPattern("h:mm a")), 100),
                column("End", session -> session.endTime().format(DateTimeFormatter.ofPattern("h:mm a")), 100),
                column("Candidates", session -> String.valueOf(session.reportedTotal()), 120),
                column("Papers", session -> String.valueOf(session.papers().size()), 90),
                column("Source row", session -> String.valueOf(session.sourceRow()), 90)));
        sessions.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, selected) -> {
            editCountsButton.setDisable(selected == null);
            showSession(selected);
        });
        sessions.setPlaceholder(new Label("Parsed sessions will appear here."));

        schedulePath.setEditable(false); schedulePath.setPromptText("Date sheet PDF without totals");
        countsPath.setEditable(false); countsPath.setPromptText("Course-count PDF with totals");
        Button chooseSchedule = new Button("Choose date sheet PDF"); chooseSchedule.setOnAction(event -> choosePdf(schedulePath));
        Button chooseCounts = new Button("Choose count PDF"); chooseCounts.setOnAction(event -> choosePdf(countsPath));
        eventName.setPromptText("Exam name"); academicPeriod.setPromptText("Academic period (optional)");
        duration.setPromptText("Duration in minutes"); duration.setPrefWidth(150);
        previewButton.setOnAction(event -> previewPdfs());
        editCountsButton.setText("Correct selected session");
        editCountsButton.setDisable(true); editCountsButton.setOnAction(event -> editSelectedSessionCounts());
        importButton.setDisable(true); importButton.setOnAction(event -> commitImport());
        HBox eventFields = new HBox(10, eventName, academicPeriod, duration);
        HBox fileRows = new HBox(10, chooseSchedule, schedulePath);
        HBox countRows = new HBox(10, chooseCounts, countsPath);
        HBox actions = new HBox(10, previewButton, editCountsButton, importButton, status);
        details.setEditable(false); details.setWrapText(true); details.setPrefRowCount(8);
        VBox content = new VBox(10, new Label("Event details"), eventFields, fileRows, countRows, actions,
                summary, sessions, new Label("Selected session papers and import diagnostics"), details);
        content.setPadding(new Insets(16, 24, 20, 24));
        VBox.setVgrow(sessions, javafx.scene.layout.Priority.ALWAYS);
        return content;
    }

    private TableColumn<ExamImportPreview.Session, String> column(String title,
            java.util.function.Function<ExamImportPreview.Session, String> value, double width) {
        TableColumn<ExamImportPreview.Session, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        column.setPrefWidth(width);
        return column;
    }

    private void choosePdf(TextField target) {
        FileChooser chooser = new FileChooser(); chooser.setTitle("Choose PDF");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF files", "*.pdf"));
        var file = chooser.showOpenDialog(getScene().getWindow());
        if (file != null) { target.setText(file.getAbsolutePath()); clearPreview(); }
    }

    private void previewPdfs() {
        if (schedulePath.getText().isBlank() || countsPath.getText().isBlank()) {
            error("Choose both PDFs", "Select the schedule without totals and the course-count PDF with totals."); return;
        }
        int minutes;
        try { minutes = Integer.parseInt(duration.getText().trim()); }
        catch (NumberFormatException exception) { error("Check duration", "Enter the shared exam duration in whole minutes."); return; }
        Path schedule = Path.of(schedulePath.getText());
        Path counts = Path.of(countsPath.getText());
        previewButton.setDisable(true); importButton.setDisable(true); status.setText("Reading and checking the PDFs…");
        Task<ExamImportPreview> task = new Task<>() {
            @Override protected ExamImportPreview call() throws Exception {
                return new ExamSchedulePdfImporter().preview(schedule, counts, minutes);
            }
        };
        task.setOnSucceeded(event -> {
            previewButton.setDisable(false);
            preview = task.getValue();
            checkDuplicateEvent();
            sessions.setItems(FXCollections.observableArrayList(preview.sessions()));
            summary.setText(preview.sessions().size() + " sessions · " + preview.paperCount() + " papers · "
                    + preview.candidateTotal() + " candidates · " + preview.diagnostics().size() + " diagnostics");
            details.setText(currentPreviewDetails());
            importButton.setDisable(preview.hasErrors() || duplicateEventCode);
            status.setText(preview.hasErrors() ? "Fix PDF errors before importing." : duplicateEventCode
                    ? "This event code is already saved." : "Review the rows, then confirm the import.");
        });
        task.setOnFailed(event -> {
            previewButton.setDisable(false); clearPreview();
            Throwable failure = task.getException();
            error("Could not read the PDFs", failure);
            status.setText("PDF preview failed.");
        });
        Thread worker = new Thread(task, "exam-pdf-preview"); worker.setDaemon(true); worker.start();
    }

    private void checkDuplicateEvent() {
        duplicateEventCode = false;
        if (preview.eventCode() == null) return;
        try {
            duplicateEventCode = repository.hasEventCode(preview.eventCode());
        } catch (SQLException exception) {
            error("Could not check saved exams", exception); duplicateEventCode = true;
        }
    }

    private String currentPreviewDetails() {
        return (duplicateEventCode ? "This event code is already saved. Re-import is disabled; saved data is unchanged.\n\n" : "")
                + formatDiagnostics(preview);
    }

    private void showSession(ExamImportPreview.Session session) {
        if (session == null) return;
        StringBuilder text = new StringBuilder("Source schedule row ").append(session.sourceRow())
                .append(" · page ").append(session.schedulePage()).append("\n");
        text.append("Original schedule PDF text:\n").append(session.scheduleSourceText()).append("\n\n");
        for (ExamImportPreview.Paper paper : session.papers()) text.append(paper.courseCode()).append(" · ")
                .append(paper.candidateCount()).append(" candidates · ").append(paper.courseName()).append("\n");
        text.append("\nOriginal count PDF text (kept for provenance):\n").append(session.countSourceText()).append("\n\n");
        details.setText(text + currentPreviewDetails());
    }

    private void editSelectedSessionCounts() {
        ExamImportPreview.Session selected = sessions.getSelectionModel().getSelectedItem();
        if (selected == null || preview == null) return;

        record PaperRow(TextField code, TextField name, TextField count, int sourceRow, int sourcePage, HBox box) {}
        List<PaperRow> paperRows = new java.util.ArrayList<>();
        VBox rows = new VBox(8);

        TextField date = new TextField(selected.date().toString());
        TextField startTime = new TextField(selected.startTime().toString());
        rows.getChildren().add(new HBox(10, new Label("Exam date (YYYY-MM-DD)"), date));
        rows.getChildren().add(new HBox(10, new Label("Start time (HH:MM)"), startTime));
        TextField reportedTotal = new TextField(String.valueOf(selected.reportedTotal()));
        rows.getChildren().add(new HBox(10, new Label("Reported session total"), reportedTotal));
        rows.getChildren().add(new Label("Course papers in this session (Code · Name · Candidates):"));

        VBox papersContainer = new VBox(6);
        for (ExamImportPreview.Paper paper : selected.papers()) {
            TextField codeField = new TextField(paper.courseCode());
            codeField.setPrefWidth(90);
            TextField nameField = new TextField(paper.courseName());
            nameField.setPrefWidth(200);
            TextField countField = new TextField(String.valueOf(paper.candidateCount()));
            countField.setPrefWidth(80);
            Button removeBtn = new Button("Remove");
            HBox box = new HBox(8, codeField, nameField, countField, removeBtn);
            PaperRow row = new PaperRow(codeField, nameField, countField, paper.sourceRow(), paper.sourcePage(), box);
            removeBtn.setOnAction(e -> {
                papersContainer.getChildren().remove(box);
                paperRows.remove(row);
            });
            paperRows.add(row);
            papersContainer.getChildren().add(box);
        }
        rows.getChildren().add(papersContainer);

        TextField newCode = new TextField(); newCode.setPromptText("Code (e.g. UCS503)"); newCode.setPrefWidth(90);
        TextField newName = new TextField(); newName.setPromptText("Course name"); newName.setPrefWidth(200);
        TextField newCount = new TextField(); newCount.setPromptText("Candidates"); newCount.setPrefWidth(80);
        Button addPaperBtn = new Button("Add course");
        addPaperBtn.setOnAction(e -> {
            String code = newCode.getText().trim().toUpperCase(java.util.Locale.ROOT);
            if (code.isBlank()) { error("Check course details", "Enter a course code."); return; }
            if (paperRows.stream().anyMatch(row -> row.code().getText().trim().equalsIgnoreCase(code))) {
                error("Duplicate course", "This course is already in the session."); return;
            }
            String name = newName.getText().trim();
            int countVal;
            try {
                countVal = Integer.parseInt(newCount.getText().trim());
                if (countVal < 0) throw new NumberFormatException();
            } catch (NumberFormatException exception) {
                error("Check candidate count", "Enter a whole-number candidate count of zero or more."); return;
            }
            TextField cField = new TextField(code); cField.setPrefWidth(90);
            TextField nField = new TextField(name.isBlank() ? code : name); nField.setPrefWidth(200);
            TextField cntField = new TextField(String.valueOf(countVal)); cntField.setPrefWidth(80);
            Button rmBtn = new Button("Remove");
            HBox b = new HBox(8, cField, nField, cntField, rmBtn);
            int countSourceRow = selected.papers().getFirst().sourceRow();
            PaperRow r = new PaperRow(cField, nField, cntField, countSourceRow, selected.countPage(), b);
            rmBtn.setOnAction(ev -> {
                papersContainer.getChildren().remove(b);
                paperRows.remove(r);
            });
            paperRows.add(r);
            papersContainer.getChildren().add(b);
            newCode.clear(); newName.clear(); newCount.clear();
        });
        HBox addRow = new HBox(8, new Label("Add:"), newCode, newName, newCount, addPaperBtn);
        rows.getChildren().addAll(new Label("Add missing or unmatched course to this session:"), addRow);

        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(400);

        ButtonType apply = new ButtonType("Apply corrections", ButtonType.OK.getButtonData());
        Dialog<Boolean> dialog = new Dialog<>();
        dialog.setTitle("Correct session details and courses");
        dialog.setHeaderText(selected.date() + " · " + selected.startTime());
        dialog.getDialogPane().getButtonTypes().addAll(apply, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(scroll);
        dialog.setResultConverter(button -> button == apply);

        Button applyButton = (Button) dialog.getDialogPane().lookupButton(apply);
        applyButton.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                LocalDate.parse(date.getText().trim());
                LocalTime.parse(startTime.getText().trim());
                int correctedTotal = Integer.parseInt(reportedTotal.getText().trim());
                if (correctedTotal < 0) throw new IllegalArgumentException("Reported total cannot be negative.");
                long total = 0;
                java.util.Set<String> codes = new java.util.HashSet<>();
                for (PaperRow row : paperRows) {
                    String code = row.code().getText().trim().toUpperCase(java.util.Locale.ROOT);
                    if (code.isBlank()) throw new IllegalArgumentException("Course code cannot be blank.");
                    if (!codes.add(code)) throw new IllegalArgumentException("A course code appears more than once in this session: " + code);
                    int value = Integer.parseInt(row.count().getText().trim());
                    if (value < 0) throw new IllegalArgumentException("Candidate counts cannot be negative.");
                    total += value;
                }
                if (paperRows.isEmpty()) throw new IllegalArgumentException("Keep at least one course in the session.");
                if (total > Integer.MAX_VALUE) throw new IllegalArgumentException("The session total is too large.");
                if (total != correctedTotal) throw new IllegalArgumentException("Course counts must sum to the reported session total.");
                LocalDate correctedDate = LocalDate.parse(date.getText().trim());
                LocalTime correctedStart = LocalTime.parse(startTime.getText().trim());
                if (preview.sessions().stream().anyMatch(session -> session != selected
                        && session.date().equals(correctedDate) && session.startTime().equals(correctedStart))) {
                    throw new IllegalArgumentException("Another session already uses this date and start time.");
                }
            } catch (IllegalArgumentException exception) {
                event.consume();
                error("Check session corrections", exception.getMessage() == null ? "Invalid date, time, or counts." : exception.getMessage());
            }
        });

        Optional<Boolean> result = dialog.showAndWait();
        if (result.isEmpty() || !result.get()) return;

        List<ExamImportPreview.Paper> updatedPapers = new java.util.ArrayList<>();
        int total = 0;
        for (PaperRow row : paperRows) {
            String code = row.code().getText().trim().toUpperCase(java.util.Locale.ROOT);
            String name = row.name().getText().trim();
            int count = Integer.parseInt(row.count().getText().trim());
            total += count;
            updatedPapers.add(new ExamImportPreview.Paper(code, name, count,
                    row.sourceRow(), row.sourcePage(), true));
        }

        LocalDate correctedDate = LocalDate.parse(date.getText().trim());
        LocalTime correctedStart = LocalTime.parse(startTime.getText().trim());
        ExamImportPreview.Session corrected = new ExamImportPreview.Session(correctedDate, correctedStart,
                correctedStart.plusMinutes(preview.durationMinutes()), selected.sourceRow(), selected.schedulePage(),
                selected.countPage(), total, selected.scheduleSourceText(), selected.countSourceText(), updatedPapers);

        List<ExamImportPreview.Session> updated = new java.util.ArrayList<>(preview.sessions());
        int selectedIndex = updated.indexOf(selected);
        updated.set(selectedIndex, corrected);

        int countSourceRow = selected.papers().getFirst().sourceRow();
        List<ExamImportPreview.Diagnostic> remaining = preview.diagnostics().stream().map(item -> {
            boolean isSessionMismatch = "SESSION_TOTAL_MISMATCH".equals(item.code())
                    && Integer.valueOf(countSourceRow).equals(item.sourceRow());
            boolean isMissingCourse = "MISSING_COURSE_COUNT".equals(item.code())
                    && Integer.valueOf(selected.sourceRow()).equals(item.sourceRow())
                    && updatedPapers.stream().anyMatch(paper -> item.message().startsWith(
                            "No candidate count for " + paper.courseCode() + " on "));
            boolean isExtraCountCourse = "COUNT_WITHOUT_SCHEDULE_COURSE".equals(item.code())
                    && Integer.valueOf(countSourceRow).equals(item.sourceRow())
                    && updatedPapers.stream().anyMatch(paper -> item.message().startsWith(
                            "Count PDF lists " + paper.courseCode() + " but"));
            if (isSessionMismatch || isMissingCourse || isExtraCountCourse) {
                return new ExamImportPreview.Diagnostic(ExamImportPreview.Severity.WARNING, item.code(),
                        "Resolved: " + item.message() + " · Session manually corrected", item.sourceRow(), item.sourcePage());
            }
            return item;
        }).toList();

        preview = new ExamImportPreview(preview.eventCode(), preview.scheduleFile(), preview.scheduleSha256(),
                preview.countFile(), preview.countSha256(), preview.durationMinutes(), updated, remaining);
        sessions.setItems(FXCollections.observableArrayList(updated));
        sessions.getSelectionModel().select(selectedIndex);
        summary.setText(preview.sessions().size() + " sessions · " + preview.paperCount() + " papers · "
                + preview.candidateTotal() + " candidates · " + preview.diagnostics().size() + " diagnostics");
        details.setText(currentPreviewDetails());
        importButton.setDisable(preview.hasErrors() || duplicateEventCode);
        status.setText(preview.hasErrors() ? "Review remaining PDF diagnostics before importing."
                : duplicateEventCode ? "This event code is already saved."
                : "Corrected session details; review and confirm the import.");
    }

    private static String formatDiagnostics(ExamImportPreview preview) {
        if (preview.diagnostics().isEmpty()) return "No validation issues found. Session totals match the course counts.";
        StringBuilder text = new StringBuilder();
        for (ExamImportPreview.Diagnostic item : preview.diagnostics()) {
            text.append(item.severity()).append(" · ").append(item.code()).append(" · ").append(item.message());
            if (item.sourceRow() != null) text.append(" (row ").append(item.sourceRow()).append(")");
            if (item.sourcePage() != null) text.append(" (page ").append(item.sourcePage()).append(")");
            text.append('\n');
        }
        return text.toString();
    }

    private void commitImport() {
        if (preview == null || preview.hasErrors() || duplicateEventCode) return;
        String message = "Import " + preview.sessions().size() + " sessions, " + preview.paperCount()
                + " course papers, and " + preview.candidateTotal() + " candidates?";
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("Confirm exam import"); confirm.setHeaderText(preview.eventCode());
        Optional<ButtonType> result = confirm.showAndWait();
        if (result.orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        try {
            ExamRepository.ExamSummary saved = repository.commitImport(preview, eventName.getText(), academicPeriod.getText());
            refreshSavedEvents(); clearPreview();
            info("Exam imported", saved.eventCode() + " saved with " + saved.sessionCount() + " sessions and "
                    + saved.paperCount() + " course papers.");
            status.setText("Import complete.");
        } catch (SQLException | IllegalArgumentException exception) { error("Could not save exam", exception); }
    }

    private void refreshSavedEvents() throws SQLException {
        if (savedEvents.getColumns().isEmpty()) savedEvents.getColumns().addAll(List.of(
                eventColumn("Code", ExamRepository.ExamSummary::eventCode, 150),
                eventColumn("Name", ExamRepository.ExamSummary::name, 250),
                eventColumn("Status", ExamRepository.ExamSummary::status, 100),
                eventColumn("Sessions", event -> String.valueOf(event.sessionCount()), 100),
                eventColumn("Papers", event -> String.valueOf(event.paperCount()), 100),
                eventColumn("Candidates", event -> String.valueOf(event.candidateCount()), 120)));
        savedEvents.setItems(FXCollections.observableArrayList(repository.findAll()));
        savedEvents.setPlaceholder(new Label("No exams imported yet."));
    }

    private void purgeSelectedEvent() {
        ExamRepository.ExamSummary selected = savedEvents.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        ButtonType purge = new ButtonType("Purge event", ButtonBar.ButtonData.OK_DONE);
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                "This permanently deletes " + selected.eventCode() + ", all saved plans including finalized plans, "
                        + "its registrations, and import records used only by this event. Student profiles used by "
                        + "other events remain. If unused student profiles are removed, their CSV row snapshots are "
                        + "cleared and student-profile import diagnostics are removed. Room, course, and staff master "
                        + "records remain.", purge, ButtonType.CANCEL);
        confirmation.setTitle("Purge exam event");
        confirmation.setHeaderText("This action cannot be undone.");
        if (confirmation.showAndWait().orElse(ButtonType.CANCEL) != purge) return;

        purgeEventButton.setDisable(true);
        Task<ExamRepository.PurgeResult> task = new Task<>() {
            @Override protected ExamRepository.PurgeResult call() throws Exception {
                return repository.purgeEvent(selected.eventCode());
            }
        };
        task.setOnSucceeded(event -> {
            try {
                refreshSavedEvents();
                savedPlans.getItems().clear();
                feasibilityDetails.clear();
                feasibilityStatus.setText("Event purged.");
                ExamRepository.PurgeResult result = task.getValue();
                info("Event purged", result.eventCode() + " and " + result.plansDeleted() + " plan versions deleted. "
                        + result.studentProfilesDeleted() + " unused student profiles and "
                        + result.importBatchesDeleted() + " event-only import batches deleted.");
            } catch (SQLException exception) {
                error("Event purged but exam list could not refresh", exception);
            }
        });
        task.setOnFailed(event -> {
            purgeEventButton.setDisable(savedEvents.getSelectionModel().getSelectedItem() == null);
            error("Could not purge event", task.getException());
        });
        Thread worker = new Thread(task, "purge-exam-event");
        worker.setDaemon(true);
        worker.start();
    }

    private void checkFeasibility() {
        ExamRepository.ExamSummary selected = savedEvents.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        feasibilityButton.setDisable(true); feasibilityStatus.setText("Checking rooms, seats, and invigilator availability…");
        Task<FeasibilityReport> task = new Task<>() {
            @Override protected FeasibilityReport call() throws Exception {
                return new FeasibilityAnalyzer().analyze(feasibilityRepository.load(selected.eventCode()));
            }
        };
        task.setOnSucceeded(event -> {
            feasibilityButton.setDisable(false);
            FeasibilityReport report = task.getValue();
            feasibilityStatus.setText(report.sessions().isEmpty() ? "No active sessions found."
                    : report.hasBlockers() ? "Capacity or staffing blockers found."
                    : "No hard capacity blocker found; review room placement and warnings.");
            feasibilityDetails.setText(formatFeasibility(report));
        });
        task.setOnFailed(event -> {
            feasibilityButton.setDisable(false); feasibilityStatus.setText("Feasibility check failed.");
            error("Could not check feasibility", task.getException());
        });
        Thread worker = new Thread(task, "event-feasibility-check"); worker.setDaemon(true); worker.start();
    }

    private void checkStudentConflicts() {
        ExamRepository.ExamSummary selected = savedEvents.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        studentConflictButton.setDisable(true);
        Task<StudentRepository.StudentConflictReport> task = new Task<>() {
            @Override protected StudentRepository.StudentConflictReport call() throws Exception {
                return studentRepository.findConflicts(selected.eventCode());
            }
        };
        task.setOnSucceeded(event -> {
            studentConflictButton.setDisable(false);
            showStudentConflicts(selected.eventCode(), task.getValue());
        });
        task.setOnFailed(event -> {
            studentConflictButton.setDisable(false);
            error("Could not check student conflicts", task.getException());
        });
        Thread worker = new Thread(task, "student-conflict-check"); worker.setDaemon(true); worker.start();
    }

    private void showStudentConflicts(String eventCode, StudentRepository.StudentConflictReport report) {
        StringBuilder text = new StringBuilder("Exam: ").append(eventCode).append("\n\n");
        if (!report.rosterAvailable()) text.append("No named enrollment rows are loaded for this exam. "
                + "Count-only exams cannot be checked for individual student conflicts.");
        else if (report.conflicts().isEmpty()) text.append("No active student has overlapping registered exams.");
        else {
            text.append(report.conflicts().size()).append(" overlapping registration pairs found:\n\n");
            for (StudentRepository.StudentConflict conflict : report.conflicts()) {
                text.append(conflict.rollNumber()).append(" · ").append(conflict.fullName())
                        .append(" (ID ").append(conflict.studentId()).append(")\n  ")
                        .append(conflict.firstCourse()).append(" · ").append(conflict.firstDate()).append(" ")
                        .append(conflict.firstStart()).append("\n  ")
                        .append(conflict.secondCourse()).append(" · ").append(conflict.secondDate()).append(" ")
                        .append(conflict.secondStart()).append("\n\n");
            }
        }
        TextArea details = new TextArea(text.toString()); details.setEditable(false); details.setWrapText(true);
        details.setPrefColumnCount(75); details.setPrefRowCount(Math.min(22, Math.max(8, report.conflicts().size() * 3)));
        Alert dialog = new Alert(report.conflicts().isEmpty() ? Alert.AlertType.INFORMATION : Alert.AlertType.WARNING);
        dialog.setTitle("Student conflict check");
        dialog.setHeaderText(!report.rosterAvailable() ? "Roster check not available"
                : report.conflicts().isEmpty() ? "No student conflicts found" : "Resolve roster conflicts before seating");
        dialog.getDialogPane().setContent(details);
        dialog.showAndWait();
    }

    private void generatePlan() {
        ExamRepository.ExamSummary selected = savedEvents.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        feasibilityButton.setDisable(true);
        generatePlanButton.setDisable(true);
        feasibilityStatus.setText("Assigning candidates to seats…");
        Task<GeneratedPlan> task = new Task<>() {
            @Override protected GeneratedPlan call() throws Exception {
                return planRepository.prepareDraft(selected.eventCode());
            }
        };
        task.setOnSucceeded(event -> {
            feasibilityButton.setDisable(false);
            generatePlanButton.setDisable(false);
            reviewPlan(task.getValue(), null, "DRAFT", false, null, null);
        });
        task.setOnFailed(event -> {
            feasibilityButton.setDisable(false);
            generatePlanButton.setDisable(false);
            feasibilityStatus.setText("Draft generation failed.");
            error("Could not generate seating draft", task.getException());
        });
        Thread worker = new Thread(task, "seat-plan-generation");
        worker.setDaemon(true);
        worker.start();
    }

    private void reviewPlan(GeneratedPlan plan, PlanRepository.SavedPlan saved, String savedStatus, boolean stale,
            String finalizedBy, String finalizedAt) {
        boolean confirmSave = saved == null;
        ButtonType save = new ButtonType(plan.blockerCount() > 0 ? "Save incomplete draft" : "Save draft",
                ButtonBar.ButtonData.OK_DONE);
        ButtonType finalize = new ButtonType("Finalize plan", ButtonBar.ButtonData.FINISH);
        boolean canFinalize = saved != null && savedStatus.equals("DRAFT") && !stale && plan.blockerCount() == 0;
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION);
        dialog.setTitle(saved == null ? "Review seating draft" : "Saved seating plan");
        dialog.setHeaderText(saved == null ? plan.blockerCount() > 0
                ? "Hard blockers are present. The draft will remain incomplete."
                : "Review the room charts before saving this draft."
                : "Version " + saved.version() + " · " + savedStatus
                        + (stale ? " · stale: event data changed after generation" : "")
                        + (finalizedBy == null ? "" : " · finalized by " + finalizedBy + " at " + finalizedAt));
        dialog.getDialogPane().setContent(planReviewContent(plan, saved, savedStatus, stale, finalizedBy, finalizedAt));
        if (confirmSave) dialog.getDialogPane().getButtonTypes().setAll(save, ButtonType.CANCEL);
        else if (canFinalize) dialog.getDialogPane().getButtonTypes().setAll(finalize, ButtonType.CLOSE);
        else dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);
        Optional<ButtonType> result = dialog.showAndWait();
        if (confirmSave && result.orElse(ButtonType.CANCEL) == save) saveDraft(plan);
        else if (canFinalize && result.orElse(ButtonType.CLOSE) == finalize) confirmFinalization(saved, plan);
    }

    private VBox planReviewContent(GeneratedPlan plan, PlanRepository.SavedPlan saved, String savedStatus, boolean stale,
            String finalizedBy, String finalizedAt) {
        String saveText = saved == null ? "Not saved yet."
                : "Plan " + saved.planId() + " · version " + saved.version() + " · " + savedStatus
                        + (stale ? " · source data changed after generation; this version cannot be finalized." : ".")
                        + (finalizedBy == null ? "" : "\nFinalized by " + finalizedBy + " at " + finalizedAt + ".");
        TextArea summary = new TextArea(saveText + "\n" + formatPlanSummary(plan));
        summary.setEditable(false);
        summary.setWrapText(true);
        summary.setPrefRowCount(12);
        summary.setPrefColumnCount(85);

        ChoiceBox<String> sessionChoice = new ChoiceBox<>();
        ChoiceBox<String> roomChoice = new ChoiceBox<>();
        TextArea chart = new TextArea();
        chart.setEditable(false);
        chart.setStyle("-fx-font-family: monospace;");
        chart.setPrefRowCount(18);
        Map<String, GeneratedPlan.Session> sessionByLabel = new HashMap<>();
        for (GeneratedPlan.Session session : plan.sessions()) {
            String label = session.date() + " " + session.startTime() + " · " + session.id();
            sessionByLabel.put(label, session);
            sessionChoice.getItems().add(label);
        }
        Map<String, GeneratedPlan.Room> roomByLabel = new HashMap<>();
        sessionChoice.setOnAction(event -> {
            roomByLabel.clear();
            roomChoice.getItems().clear();
            GeneratedPlan.Session session = sessionByLabel.get(sessionChoice.getValue());
            if (session == null) { chart.clear(); return; }
            for (GeneratedPlan.Room room : session.rooms()) {
                String label = room.buildingCode() + " " + room.roomCode();
                roomByLabel.put(label, room);
                roomChoice.getItems().add(label);
            }
            if (roomChoice.getItems().isEmpty()) chart.setText("No room was allocated to this session.");
            else {
                roomChoice.setValue(roomChoice.getItems().getFirst());
                chart.setText(formatRoomChart(roomByLabel.get(roomChoice.getValue())));
            }
        });
        roomChoice.setOnAction(event -> chart.setText(formatRoomChart(roomByLabel.get(roomChoice.getValue()))));
        if (!sessionChoice.getItems().isEmpty()) sessionChoice.setValue(sessionChoice.getItems().getFirst());
        sessionChoice.getOnAction().handle(null);
        VBox content = new VBox(8, summary, new HBox(10, new Label("Session"), sessionChoice,
                new Label("Room"), roomChoice), chart);
        if (saved != null) {
            Button exportSeats = new Button("Export selected session / room seats CSV");
            Button exportStaff = new Button("Export selected session / room invigilators CSV");
            exportSeats.setOnAction(event -> {
                GeneratedPlan.Session selected = sessionByLabel.get(sessionChoice.getValue());
                GeneratedPlan.Room selectedRoom = roomByLabel.get(roomChoice.getValue());
                if (selected != null) exportPlanCsv(plan, saved.version(), selected.id(),
                        selectedRoom == null ? null : selectedRoom.id(), false, exportSeats);
            });
            exportStaff.setOnAction(event -> {
                GeneratedPlan.Session selected = sessionByLabel.get(sessionChoice.getValue());
                GeneratedPlan.Room selectedRoom = roomByLabel.get(roomChoice.getValue());
                if (selected != null) exportPlanCsv(plan, saved.version(), selected.id(),
                        selectedRoom == null ? null : selectedRoom.id(), true, exportStaff);
            });
            HBox exports = new HBox(8, exportSeats, exportStaff);
            if (!stale) {
                Button courseBlocks = new Button("Check course by block");
                courseBlocks.setOnAction(event -> {
                    GeneratedPlan.Session selected = sessionByLabel.get(sessionChoice.getValue());
                    if (selected != null) loadCourseBlockReport(plan.eventCode(), saved.planId(), saved.version(),
                            selected.id(), courseBlocks);
                });
                Button centreAllocation = new Button("Export centre allocation CSV");
                centreAllocation.setOnAction(event -> {
                    GeneratedPlan.Session selected = sessionByLabel.get(sessionChoice.getValue());
                    if (selected != null) exportAllocationCsv(plan.eventCode(), saved.planId(), saved.version(),
                            selected.id(), true, centreAllocation);
                });
                Button courseAllocation = new Button("Export course-room CSV");
                courseAllocation.setOnAction(event -> {
                    GeneratedPlan.Session selected = sessionByLabel.get(sessionChoice.getValue());
                    if (selected != null) exportAllocationCsv(plan.eventCode(), saved.planId(), saved.version(),
                            selected.id(), false, courseAllocation);
                });
                exports.getChildren().addAll(courseBlocks, centreAllocation, courseAllocation);
            }
            content.getChildren().add(exports);
            if (savedStatus.equals("DRAFT") && !stale) {
                Button move = new Button("Move candidate / seat");
                Button replaceStaff = new Button("Replace invigilator");
                move.setOnAction(event -> moveCandidate(plan));
                replaceStaff.setOnAction(event -> replaceInvigilator(plan));
                content.getChildren().add(new HBox(8, move, replaceStaff));
            }
        }
        content.setPrefSize(900, 680);
        return content;
    }

    private void exportPlanCsv(GeneratedPlan plan, int version, String sessionId, String roomId,
            boolean staff, Button button) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(staff ? "Export selected room invigilators" : "Export selected room seats");
        chooser.setInitialFileName((staff ? "invigilators-v" : "seating-v") + version + "-" + sessionId
                + (roomId == null ? "" : "-" + roomId) + ".csv");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"));
        var file = chooser.showSaveDialog(getScene().getWindow());
        if (file == null) return;
        button.setDisable(true);
        Task<Integer> task = new Task<>() {
            @Override protected Integer call() throws Exception {
                PlanCsvExporter exporter = new PlanCsvExporter();
                return staff ? exporter.exportInvigilators(plan, version, sessionId, roomId, file.toPath())
                        : exporter.exportSeats(plan, version, sessionId, roomId, file.toPath());
            }
        };
        task.setOnSucceeded(event -> {
            button.setDisable(false);
            info("Export complete", task.getValue() + (staff ? " invigilator rows" : " seat rows")
                    + " exported.");
        });
        task.setOnFailed(event -> {
            button.setDisable(false);
            error("Could not export plan", task.getException());
        });
        Thread worker = new Thread(task, "plan-csv-export");
        worker.setDaemon(true);
        worker.start();
    }

    private void exportAllocationCsv(String eventCode, String planId, int version, String sessionId,
            boolean centreWise, Button button) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(centreWise ? "Export centre-wise allocation" : "Export subject-wise room allocation");
        chooser.setInitialFileName((centreWise ? "centre-allocation-v" : "course-room-allocation-v")
                + version + "-" + sessionId + ".csv");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"));
        var file = chooser.showSaveDialog(getScene().getWindow());
        if (file == null) return;
        button.setDisable(true);
        Task<Integer> task = new Task<>() {
            @Override protected Integer call() throws Exception {
                PlanCsvExporter exporter = new PlanCsvExporter();
                if (centreWise) return exporter.exportCentreAllocations(
                        planRepository.findCentreAllocations(planId, sessionId), file.toPath());
                return exporter.exportCourseRoomAllocations(
                        eventCode, version, planRepository.findCourseRoomAllocations(planId, sessionId), file.toPath());
            }
        };
        task.setOnSucceeded(event -> {
            button.setDisable(false);
            info("Export complete", task.getValue() + (centreWise ? " centre/room rows" : " course/room rows") + " exported.");
        });
        task.setOnFailed(event -> {
            button.setDisable(false);
            error("Could not export allocation", task.getException());
        });
        Thread worker = new Thread(task, centreWise ? "centre-allocation-export" : "course-room-export");
        worker.setDaemon(true); worker.start();
    }

    private void loadCourseBlockReport(String eventCode, String planId, int version, String sessionId, Button button) {
        button.setDisable(true);
        Task<List<PlanRepository.CourseBlockSummary>> task = new Task<>() {
            @Override protected List<PlanRepository.CourseBlockSummary> call() throws Exception {
                return planRepository.findCourseBlockSummary(planId, sessionId);
            }
        };
        task.setOnSucceeded(event -> {
            button.setDisable(false);
            showCourseBlockReport(eventCode, version, sessionId, task.getValue());
        });
        task.setOnFailed(event -> {
            button.setDisable(false);
            error("Could not build course-by-block report", task.getException());
        });
        Thread worker = new Thread(task, "course-block-report");
        worker.setDaemon(true);
        worker.start();
    }

    private void showCourseBlockReport(String eventCode, int version, String sessionId,
            List<PlanRepository.CourseBlockSummary> rows) {
        TableView<PlanRepository.CourseBlockSummary> table = new TableView<>(FXCollections.observableArrayList(rows));
        table.getColumns().addAll(List.of(
                courseBlockColumn("Date", row -> row.date(), 105),
                courseBlockColumn("Start", row -> row.startTime(), 75),
                courseBlockColumn("Course", row -> row.courseCode(), 110),
                courseBlockColumn("Expected", row -> String.valueOf(row.expectedCandidates()), 80),
                courseBlockColumn("Assigned", row -> String.valueOf(row.assignedCandidates()), 80),
                courseBlockColumn("Blocks", row -> String.valueOf(row.blocksUsed()), 65),
                courseBlockColumn("Seats by block", PlanRepository.CourseBlockSummary::distribution, 250),
                courseBlockColumn("Check", PlanRepository.CourseBlockSummary::status, 150)));
        table.setPlaceholder(new Label("This saved plan has no course rows."));
        Button export = new Button("Export CSV");
        export.setOnAction(event -> exportCourseBlockReport(eventCode, version, sessionId, rows, export));
        VBox content = new VBox(8, new Label("Counts compare assigned seats with each course's imported demand."), table, export);
        VBox.setVgrow(table, javafx.scene.layout.Priority.ALWAYS);
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Course-by-block check · v" + version);
        dialog.setHeaderText(rows.stream().filter(row -> !row.status().equals("OK")).count()
                + " course/session allocations need review.");
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().setPrefSize(1000, 560);
        dialog.show();
    }

    private void exportCourseBlockReport(String eventCode, int version, String sessionId,
            List<PlanRepository.CourseBlockSummary> rows, Button button) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export course-by-block check");
        chooser.setInitialFileName("course-block-check-v" + version + "-" + sessionId + ".csv");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"));
        var file = chooser.showSaveDialog(getScene().getWindow());
        if (file == null) return;
        button.setDisable(true);
        Task<Integer> task = new Task<>() {
            @Override protected Integer call() throws Exception {
                return new PlanCsvExporter().exportCourseBlocks(eventCode, version, rows, file.toPath());
            }
        };
        task.setOnSucceeded(event -> { button.setDisable(false); info("Export complete", task.getValue() + " course rows exported."); });
        task.setOnFailed(event -> { button.setDisable(false); error("Could not export report", task.getException()); });
        Thread worker = new Thread(task, "course-block-export");
        worker.setDaemon(true);
        worker.start();
    }

    private static String formatPlanSummary(GeneratedPlan plan) {
        int demand = plan.sessions().stream().mapToInt(GeneratedPlan.Session::candidateDemand).sum();
        StringBuilder text = new StringBuilder("Event ").append(plan.eventCode()).append(" · source revision ")
                .append(plan.sourceRevision()).append(" · ").append(plan.assignedCandidates()).append('/')
                .append(demand).append(" candidates seated · ").append(plan.roomCount()).append(" room uses · ")
                .append(plan.blockerCount()).append(" hard blockers\n");
        for (GeneratedPlan.Session session : plan.sessions()) {
            int assigned = session.rooms().stream().mapToInt(room -> room.assignments().size()).sum();
            text.append("\n").append(session.date()).append(" ").append(session.startTime()).append(" · ")
                    .append(assigned).append('/').append(session.candidateDemand()).append(" seated · ")
                    .append(session.rooms().size()).append(" rooms\n");
            for (GeneratedPlan.Room room : session.rooms()) {
                text.append("  ").append(room.buildingCode()).append(" ").append(room.roomCode()).append(" · ")
                        .append(room.assignments().size()).append('/').append(room.candidateDemand())
                        .append(" candidates · staff ").append(room.staffIds().size()).append('/')
                        .append(room.staffRequired()).append("\n");
            }
            Map<String, Integer> counts = new java.util.TreeMap<>();
            session.diagnostics().forEach(diagnostic -> counts.merge(diagnostic.code(), 1, Integer::sum));
            session.rooms().forEach(room -> room.diagnostics().forEach(
                    diagnostic -> counts.merge(diagnostic.code(), 1, Integer::sum)));
            if (!counts.isEmpty()) text.append("  Diagnostics: ").append(counts).append("\n");
            for (FeasibilityReport.Diagnostic diagnostic : session.diagnostics()) {
                if (diagnostic.severity().equals("BLOCKER") || diagnostic.severity().equals("ERROR")
                        || diagnostic.code().equals("COURSE_SPLIT_ACROSS_BLOCKS")) appendDiagnostic(text, diagnostic);
            }
            for (GeneratedPlan.Room room : session.rooms()) for (FeasibilityReport.Diagnostic diagnostic : room.diagnostics()) {
                if (diagnostic.severity().equals("BLOCKER") || diagnostic.severity().equals("ERROR")) appendDiagnostic(text, diagnostic);
            }
        }
        return text.toString();
    }

    private static String formatRoomChart(GeneratedPlan.Room room) {
        if (room == null) return "Select a room to see its seating chart.";
        Map<Integer, SeatAssignmentPlanner.Assignment> occupied = new HashMap<>();
        for (SeatAssignmentPlanner.Assignment assignment : room.assignments()) occupied.put(assignment.seat().id(), assignment);
        int rows = room.seatGrid().stream().mapToInt(SeatAssignmentPlanner.Seat::row).max().orElse(0);
        int columns = room.seatGrid().stream().mapToInt(SeatAssignmentPlanner.Seat::column).max().orElse(0);
        if (rows == 0 || columns == 0) return room.buildingCode() + " " + room.roomCode() + " has no seat layout.";
        Map<String, SeatAssignmentPlanner.Seat> byPosition = new HashMap<>();
        for (SeatAssignmentPlanner.Seat seat : room.seatGrid()) byPosition.put(seat.row() + ":" + seat.column(), seat);
        StringBuilder chart = new StringBuilder(room.buildingCode()).append(" ").append(room.roomCode())
                .append(" · ").append(room.assignments().size()).append("/").append(room.capacity())
                .append(" seats · ").append(room.staffIds().size()).append('/').append(room.staffRequired())
                .append(" invigilators\n");
        for (int row = 1; row <= rows; row++) {
            chart.append(String.format("%2d |", row));
            for (int column = 1; column <= columns; column++) {
                SeatAssignmentPlanner.Seat seat = byPosition.get(row + ":" + column);
                String occupant = seat == null ? " " : !seat.usable() ? "×"
                        : occupied.containsKey(seat.id()) ? label(occupied.get(seat.id()).candidate()) : "·";
                chart.append(String.format(" %12s", occupant));
            }
            chart.append("\n");
        }
        chart.append("\n· empty seat   × blocked/reserved seat\n");
        if (!room.staffIds().isEmpty()) chart.append("Invigilators: ").append(String.join(", ", room.staffIds()));
        for (FeasibilityReport.Diagnostic diagnostic : room.diagnostics()) {
            chart.append("\n").append(diagnostic.severity()).append(" · ").append(diagnostic.code())
                    .append(" · ").append(diagnostic.message());
        }
        return chart.toString();
    }

    private static void appendDiagnostic(StringBuilder text, FeasibilityReport.Diagnostic diagnostic) {
        text.append("  ").append(diagnostic.severity()).append(" · ").append(diagnostic.code())
                .append(" · ").append(diagnostic.message()).append("\n");
    }

    private static String label(SeatAssignmentPlanner.Candidate candidate) {
        return candidate.studentId() == null ? "A" + candidate.anonymousNumber() : candidate.candidateKey();
    }

    private void saveDraft(GeneratedPlan plan) { saveDraft(plan, "Generated seating draft"); }

    private void saveDraft(GeneratedPlan plan, String changeSummary) {
        generatePlanButton.setDisable(true);
        feasibilityStatus.setText("Saving the reviewed draft…");
        Task<PlanRepository.LoadedPlan> task = new Task<>() {
            @Override protected PlanRepository.LoadedPlan call() throws Exception {
                PlanRepository.SavedPlan saved = planRepository.saveDraft(plan, changeSummary);
                return planRepository.loadPlan(saved.planId());
            }
        };
        task.setOnSucceeded(event -> {
            generatePlanButton.setDisable(false);
            PlanRepository.LoadedPlan loaded = task.getValue();
            PlanRepository.PlanSummary saved = loaded.summary();
            feasibilityStatus.setText("Draft v" + saved.version() + " saved · " + saved.seatCount() + " seats.");
            feasibilityDetails.setText(formatPlanSummary(plan));
            refreshSelectedPlanList();
            reviewPlan(loaded.plan(), new PlanRepository.SavedPlan(saved.planId(), saved.version(),
                    saved.seatCount(), saved.roomCount(), saved.blockerCount()), saved.status(), saved.stale(),
                    saved.finalizedBy(), saved.finalizedAt());
        });
        task.setOnFailed(event -> {
            generatePlanButton.setDisable(false);
            feasibilityStatus.setText("Draft was not saved.");
            error("Could not save seating draft", task.getException());
        });
        Thread worker = new Thread(task, "seat-plan-save");
        worker.setDaemon(true);
        worker.start();
    }

    private void moveCandidate(GeneratedPlan plan) {
        Map<String, GeneratedPlan.Session> sessions = new HashMap<>();
        for (GeneratedPlan.Session session : plan.sessions()) sessions.put(session.date() + " " + session.startTime(), session);
        GeneratedPlan.Session session = sessions.get(choose("Move candidate", "Choose exam session", sessions.keySet().stream().toList()));
        if (session == null) return;
        Map<String, SeatAssignmentPlanner.Assignment> candidates = new HashMap<>();
        for (GeneratedPlan.Room room : session.rooms()) for (var assignment : room.assignments()) {
            String label = assignment.candidate().candidateKey() + " · " + assignment.candidate().courseCode()
                    + " · " + room.buildingCode() + " " + room.roomCode();
            candidates.put(label, assignment);
        }
        String candidateLabel = choose("Move candidate", "Choose candidate", candidates.keySet().stream().sorted().toList());
        if (candidateLabel == null) return;
        Map<String, GeneratedPlan.Room> rooms = new HashMap<>();
        for (GeneratedPlan.Room room : session.rooms()) rooms.put(room.buildingCode() + " " + room.roomCode(), room);
        String roomLabel = choose("Move candidate", "Choose destination room", rooms.keySet().stream().sorted().toList());
        if (roomLabel == null) return;
        GeneratedPlan.Room room = rooms.get(roomLabel);
        Map<String, Integer> seats = new HashMap<>();
        for (var seat : room.seatGrid()) if (seat.usable() && room.assignments().stream().noneMatch(a -> a.seat().id() == seat.id()))
            seats.put("Row " + seat.row() + ", seat " + seat.column(), seat.id());
        String seatLabel = choose("Move candidate", "Choose an empty usable seat", seats.keySet().stream().sorted().toList());
        if (seatLabel == null) return;
        try {
            GeneratedPlan updated = new PlanEditor().moveCandidate(plan, session.id(),
                    candidates.get(candidateLabel).candidate().candidateKey(), room.id(), seats.get(seatLabel));
            saveDraft(updated, "Manual seat move");
        } catch (IllegalArgumentException exception) { error("Could not move candidate", exception.getMessage()); }
    }

    private void replaceInvigilator(GeneratedPlan plan) {
        Map<String, GeneratedPlan.Session> sessions = new HashMap<>();
        for (GeneratedPlan.Session session : plan.sessions()) sessions.put(session.date() + " " + session.startTime(), session);
        GeneratedPlan.Session session = sessions.get(choose("Replace invigilator", "Choose exam session", sessions.keySet().stream().toList()));
        if (session == null) return;
        Map<String, GeneratedPlan.Room> rooms = new HashMap<>();
        for (GeneratedPlan.Room room : session.rooms()) rooms.put(room.buildingCode() + " " + room.roomCode(), room);
        String roomLabel = choose("Replace invigilator", "Choose room", rooms.keySet().stream().sorted().toList());
        if (roomLabel == null) return;
        GeneratedPlan.Room room = rooms.get(roomLabel);
        Map<String, String> assigned = new HashMap<>();
        for (String staffId : room.staffIds()) assigned.put(staffId, staffId);
        String oldStaff = choose("Replace invigilator", "Choose assigned invigilator", assigned.keySet().stream().sorted().toList());
        if (oldStaff == null) return;
        try {
            Map<String, String> available = new HashMap<>();
            for (PlanRepository.StaffOption staff : planRepository.findActiveStaff())
                available.put(staff.toString(), staff.staffId());
            String newLabel = choose("Replace invigilator", "Choose replacement", available.keySet().stream().sorted().toList());
            if (newLabel == null) return;
            GeneratedPlan updated = new PlanEditor().replaceInvigilator(plan, session.id(), room.id(), oldStaff, available.get(newLabel));
            saveDraft(updated, "Manual invigilator replacement");
        } catch (SQLException | IllegalArgumentException exception) {
            error("Could not replace invigilator", exception.getMessage());
        }
    }

    private String choose(String title, String prompt, List<String> options) {
        if (options.isEmpty()) { info(title, "There are no available choices."); return null; }
        ChoiceDialog<String> dialog = new ChoiceDialog<>(options.getFirst(), options);
        dialog.setTitle(title); dialog.setHeaderText(prompt);
        return dialog.showAndWait().orElse(null);
    }

    private void refreshSavedPlans(String eventCode) {
        try {
            savedPlans.setItems(FXCollections.observableArrayList(planRepository.findPlans(eventCode)));
            savedPlans.setPlaceholder(new Label("No plans saved for this exam yet."));
        } catch (SQLException exception) {
            error("Could not load saved plans", exception);
        }
    }

    private void refreshSelectedPlanList() {
        ExamRepository.ExamSummary selected = savedEvents.getSelectionModel().getSelectedItem();
        if (selected != null) refreshSavedPlans(selected.eventCode());
    }

    private void openSavedPlan() {
        PlanRepository.PlanSummary selected = savedPlans.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        openPlanButton.setDisable(true);
        Task<PlanRepository.LoadedPlan> task = new Task<>() {
            @Override protected PlanRepository.LoadedPlan call() throws Exception {
                return planRepository.loadPlan(selected.planId());
            }
        };
        task.setOnSucceeded(event -> {
            openPlanButton.setDisable(false);
            PlanRepository.LoadedPlan loaded = task.getValue();
            PlanRepository.PlanSummary info = loaded.summary();
            PlanRepository.SavedPlan saved = new PlanRepository.SavedPlan(info.planId(), info.version(),
                    info.seatCount(), info.roomCount(), info.blockerCount());
            reviewPlan(loaded.plan(), saved, info.status(), info.stale(), info.finalizedBy(), info.finalizedAt());
        });
        task.setOnFailed(event -> {
            openPlanButton.setDisable(false);
            error("Could not open saved plan", task.getException());
        });
        Thread worker = new Thread(task, "saved-plan-load");
        worker.setDaemon(true);
        worker.start();
    }

    private void confirmFinalization(PlanRepository.SavedPlan saved, GeneratedPlan plan) {
        List<FeasibilityReport.Diagnostic> warnings = plan.sessions().stream()
                .flatMap(session -> java.util.stream.Stream.concat(session.diagnostics().stream(),
                        session.rooms().stream().flatMap(room -> room.diagnostics().stream())))
                .filter(item -> item.severity().equals("WARNING")
                        && !item.code().equals("SAME_COURSE_ORTHOGONAL_ADJACENCY")
                        && !item.code().equals("COURSE_SPLIT_ACROSS_BLOCKS"))
                .toList();
        TextField administrator = new TextField("Administrator");
        CheckBox acknowledge = new CheckBox("I reviewed and accept the capacity and eligibility warnings.");
        TextArea warningDetails = new TextArea(warnings.stream().map(item -> item.code() + " · " + item.message())
                .distinct().reduce((first, second) -> first + "\n" + second).orElse("No required warnings."));
        warningDetails.setEditable(false);
        warningDetails.setWrapText(true);
        warningDetails.setPrefRowCount(Math.min(8, Math.max(2, warnings.size())));
        VBox content = new VBox(8, new Label("Finalizing locks this version. Later changes need a new draft."),
                new Label("Administrator"), administrator, warningDetails, acknowledge);
        Dialog<ButtonType> dialog = new Dialog<>();
        ButtonType finalize = new ButtonType("Finalize", ButtonBar.ButtonData.FINISH);
        dialog.setTitle("Finalize seating plan");
        dialog.setHeaderText("Confirm version " + saved.version());
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().setAll(finalize, ButtonType.CANCEL);
        Button finalizeButton = (Button) dialog.getDialogPane().lookupButton(finalize);
        Runnable updateButton = () -> finalizeButton.setDisable(administrator.getText().isBlank()
                || (!warnings.isEmpty() && !acknowledge.isSelected()));
        administrator.textProperty().addListener((observable, oldValue, newValue) -> updateButton.run());
        acknowledge.selectedProperty().addListener((observable, oldValue, newValue) -> updateButton.run());
        updateButton.run();
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) == finalize)
            finalizePlan(saved.planId(), administrator.getText().trim(), acknowledge.isSelected());
    }

    private void finalizePlan(String planId, String administrator, boolean acknowledgeWarnings) {
        generatePlanButton.setDisable(true);
        Task<PlanRepository.LoadedPlan> task = new Task<>() {
            @Override protected PlanRepository.LoadedPlan call() throws Exception {
                planRepository.finalizePlan(planId, administrator, acknowledgeWarnings);
                return planRepository.loadPlan(planId);
            }
        };
        task.setOnSucceeded(event -> {
            generatePlanButton.setDisable(false);
            refreshSelectedPlanList();
            PlanRepository.LoadedPlan loaded = task.getValue();
            var info = loaded.summary();
            var saved = new PlanRepository.SavedPlan(info.planId(), info.version(), info.seatCount(),
                    info.roomCount(), info.blockerCount());
            reviewPlan(loaded.plan(), saved, info.status(), info.stale(), info.finalizedBy(), info.finalizedAt());
        });
        task.setOnFailed(event -> {
            generatePlanButton.setDisable(false);
            refreshSelectedPlanList();
            error("Could not finalize seating plan", task.getException());
        });
        Thread worker = new Thread(task, "plan-finalization");
        worker.setDaemon(true);
        worker.start();
    }

    private static String formatFeasibility(FeasibilityReport report) {
        StringBuilder text = new StringBuilder("Event ").append(report.eventCode()).append(" · source revision ")
                .append(report.sourceRevision()).append("\n");
        if (report.sessions().isEmpty()) return text.append("No active sessions were found.\n").toString();
        for (FeasibilityReport.SessionResult session : report.sessions()) {
            text.append("\n").append(session.date()).append(" ").append(session.startTime()).append("–")
                    .append(session.endTime()).append(" · ").append(session.candidates()).append(" candidates\n")
                    .append("Free room capacity: ").append(session.availableSeats()).append(" in ")
                    .append(session.availableRooms()).append(" rooms · estimated rooms needed: ")
                    .append(session.roomsNeeded()).append(" · invigilators required: ").append(session.staffNeeded())
                    .append(" · available: ").append(session.availableStaff()).append(" · eligible for at least one room: ")
                    .append(session.potentiallyEligibleStaff()).append("\n");
            for (FeasibilityReport.RoomUse room : session.selectedRooms()) {
                text.append("  ").append(room.buildingCode()).append(" ").append(room.roomCode()).append(" · ")
                        .append(room.seats()).append(" seats · ").append(room.staffIds().size()).append('/')
                        .append(room.staffRequired()).append(" staff");
                if (!room.staffIds().isEmpty()) text.append(" [").append(String.join(", ", room.staffIds())).append("]");
                text.append(" · ").append(room.capacityStatus());
                if (!room.courses().isEmpty()) {
                    text.append(" · courses: ").append(room.courses().stream()
                            .map(course -> course.courseCode() + " " + course.candidates())
                            .reduce((first, second) -> first + ", " + second).orElse(""));
                }
                text.append('\n');
            }
            for (FeasibilityReport.Diagnostic diagnostic : session.diagnostics()) text.append("  ")
                    .append(diagnostic.severity()).append(" · ").append(diagnostic.code()).append(" · ")
                    .append(diagnostic.message()).append('\n');
        }
        text.append("\nCourse counts are allocated to available rooms with a same-building preference. Staff listed per room are a feasibility preview; individual seats and saved duty assignments are not created here.");
        return text.toString();
    }

    private static TableColumn<ExamRepository.ExamSummary, String> eventColumn(String title,
            java.util.function.Function<ExamRepository.ExamSummary, String> value, double width) {
        TableColumn<ExamRepository.ExamSummary, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        column.setPrefWidth(width); return column;
    }

    private static TableColumn<PlanRepository.PlanSummary, String> planColumn(String title,
            java.util.function.Function<PlanRepository.PlanSummary, String> value, double width) {
        TableColumn<PlanRepository.PlanSummary, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        column.setPrefWidth(width);
        return column;
    }

    private static TableColumn<PlanRepository.CourseBlockSummary, String> courseBlockColumn(String title,
            java.util.function.Function<PlanRepository.CourseBlockSummary, String> value, double width) {
        TableColumn<PlanRepository.CourseBlockSummary, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        column.setPrefWidth(width);
        return column;
    }

    private void clearPreview() {
        preview = null; duplicateEventCode = false; sessions.getItems().clear(); summary.setText("");
        editCountsButton.setDisable(true);
        details.clear(); importButton.setDisable(true);
    }

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
