package ui;

import domain.StaffImportPreview;
import domain.StaffMember;
import importexport.AffiliationImportPreview;
import importexport.StaffAffiliationCsvImporter;
import importexport.StaffCsvImporter;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import persistence.StaffRepository;

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
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** Staff records and CSV tools. */
public final class StaffDashboard extends BorderPane {
    private final StaffRepository repository;
    private final TableView<StaffMember> table = new TableView<>();
    private final ObservableList<StaffMember> allStaff = FXCollections.observableArrayList();
    private final FilteredList<StaffMember> visibleStaff = new FilteredList<>(allStaff);
    private final TextField search = new TextField();
    private final ChoiceBox<String> activeFilter = new ChoiceBox<>(FXCollections.observableArrayList("All", "Active", "Inactive"));
    private final ChoiceBox<StaffRepository.SessionOption> sessionSelector = new ChoiceBox<>();
    private final ChoiceBox<String> availabilityAction = new ChoiceBox<>(FXCollections.observableArrayList("Not set", "Available", "Unavailable"));
    private final Label count = new Label();
    private final Label summary = new Label();
    private final Label sessionStatus = new Label("Session controls are available after an exam is imported.");
    private final Label assignmentSummary = new Label("No saved room assignments for this session.");
    private final Button viewAssignments = new Button("View room assignments");
    private final Label selectedStaff = new Label("Select a staff member to review course links.");
    private final javafx.scene.control.ListView<StaffRepository.AffiliationView> affiliationList = new javafx.scene.control.ListView<>();
    private final Button importLinksButton = new Button("Import course links");
    private java.util.Map<String, Integer> affiliationCounts = java.util.Map.of();
    private java.util.Map<String, String> sessionAvailability = java.util.Map.of();
    private List<StaffRepository.RoomAssignmentSummary> sessionAssignments = List.of();

    public StaffDashboard(StaffRepository repository) throws SQLException {
        this.repository = repository;
        buildTable();
        buildControls();
        setCenter(table);
        BorderPane.setMargin(table, new Insets(12, 24, 12, 24));
        refresh();
    }

    private void buildTable() {
        column("Staff ID", StaffMember::staffId, 150);
        column("Name", StaffMember::fullName, 240);
        column("Department", staff -> text(staff.department()), 170);
        column("Designation", staff -> text(staff.designation()), 220);
        column("Course links", staff -> String.valueOf(affiliationCounts.getOrDefault(staff.staffId(), 0)), 100);
        column("Status", staff -> staff.active() ? "Active" : "Inactive", 100);
        column("Availability", staff -> displayStatus(sessionAvailability.get(staff.staffId())), 115);
        table.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, staff) -> showAffiliations(staff));
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setItems(visibleStaff);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("No staff records match these filters."));
    }

    private void column(String title, java.util.function.Function<StaffMember, String> value, double width) {
        TableColumn<StaffMember, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        column.setPrefWidth(width);
        table.getColumns().add(column);
    }

    private void buildControls() {
        search.setPromptText("Search ID, name, or department");
        search.textProperty().addListener((observable, oldValue, newValue) -> applyFilter());
        activeFilter.setValue("All");
        activeFilter.setOnAction(event -> applyFilter());
        Button add = new Button("Add staff");
        add.setOnAction(event -> editStaff(null));
        Button edit = new Button("Edit selected");
        edit.setOnAction(event -> {
            if (table.getSelectionModel().getSelectedItems().size() != 1) {
                info("Select one staff member", "Choose exactly one staff row to edit."); return;
            }
            editStaff(table.getSelectionModel().getSelectedItem());
        });
        Button toggle = new Button("Change status");
        toggle.setOnAction(event -> toggleSelected());
        Button importCsv = new Button("Import CSV");
        importCsv.setOnAction(event -> importCsv());
        Button exportCsv = new Button("Export CSV");
        exportCsv.setOnAction(event -> exportCsv(exportCsv));
        importLinksButton.setOnAction(event -> importAffiliations());
        Button exportLinks = new Button("Export course links");
        exportLinks.setOnAction(event -> exportAffiliations());
        Button selectVisible = new Button("Select visible");
        selectVisible.setOnAction(event -> {
            table.getSelectionModel().clearSelection();
            table.getSelectionModel().selectAll();
        });
        Button clearSelection = new Button("Clear selection");
        clearSelection.setOnAction(event -> table.getSelectionModel().clearSelection());

        HBox filters = new HBox(10, search, activeFilter, count);
        HBox actions = new HBox(8, add, edit, toggle, importCsv, exportCsv, importLinksButton, exportLinks,
                selectVisible, clearSelection);
        sessionSelector.setOnAction(event -> refreshSessionStatuses());
        viewAssignments.setOnAction(event -> showSessionAssignments());
        availabilityAction.setValue("Available");
        Button setAvailability = new Button("Apply availability to selected");
        setAvailability.setOnAction(event -> applySessionAvailability());
        HBox sessionControls = new HBox(8, new Label("Exam session"), sessionSelector, sessionStatus);
        HBox assignmentControls = new HBox(8, assignmentSummary, viewAssignments);
        HBox statusControls = new HBox(8, availabilityAction, setAvailability);
        Button addLink = new Button("Add link"); addLink.setOnAction(event -> addAffiliation());
        Button removeLink = new Button("Remove selected link"); removeLink.setOnAction(event -> removeAffiliation());
        affiliationList.setPlaceholder(new Label("No course relationships saved for this staff member."));
        affiliationList.setCellFactory(list -> new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(StaffRepository.AffiliationView item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.courseCode() + " · " + item.dataLabel());
            }
        });
        HBox affiliationActions = new HBox(8, addLink, removeLink);
        VBox affiliationDetails = new VBox(6, selectedStaff, affiliationList, affiliationActions);
        affiliationDetails.setPadding(new Insets(8, 24, 14, 24));
        setBottom(affiliationDetails);
        BorderPane.setMargin(affiliationList, new Insets(0, 0, 0, 0));
        affiliationList.setPrefHeight(105);
        VBox controls = new VBox(12, summary, filters, actions, sessionControls, statusControls, assignmentControls);
        controls.setPadding(new Insets(20, 24, 8, 24));
        setTop(controls);
    }

    private void applyFilter() {
        table.getSelectionModel().clearSelection();
        String query = search.getText().trim().toLowerCase();
        String status = activeFilter.getValue();
        visibleStaff.setPredicate(staff -> {
            boolean matchesSearch = query.isEmpty()
                    || staff.staffId().toLowerCase().contains(query)
                    || staff.fullName().toLowerCase().contains(query)
                    || text(staff.department()).toLowerCase().contains(query);
            boolean matchesStatus = "All".equals(status)
                    || ("Active".equals(status) == staff.active());
            return matchesSearch && matchesStatus;
        });
        count.setText(visibleStaff.size() + " shown · " + visibleStaff.getSource().size() + " total");
    }

    private void refresh() throws SQLException {
        allStaff.setAll(repository.findAll());
        affiliationCounts = repository.findAffiliationCounts();
        refreshSessions();
        table.refresh();
        long active = allStaff.stream().filter(StaffMember::active).count();
        boolean isDemo = allStaff.stream().anyMatch(s -> "DEMO".equalsIgnoreCase(s.dataLabel()));
        summary.setText((isDemo ? "[DEMO DATA] " : "") + allStaff.size() + " staff · " + active + " active · "
                + affiliationCounts.size() + " with course links");
        applyFilter();
    }

    public void refreshData() {
        try { refresh(); } catch (SQLException exception) { error("Could not refresh staff", exception); }
    }

    private void refreshSessions() throws SQLException {
        String selectedId = sessionSelector.getValue() == null ? null : sessionSelector.getValue().sessionId();
        List<StaffRepository.SessionOption> sessions = repository.findSessions();
        sessionSelector.setItems(FXCollections.observableArrayList(sessions));
        StaffRepository.SessionOption selected = sessions.stream().filter(item -> item.sessionId().equals(selectedId))
                .findFirst().orElse(sessions.isEmpty() ? null : sessions.getFirst());
        sessionSelector.setValue(selected);
        sessionSelector.setDisable(sessions.isEmpty());
        if (sessions.isEmpty()) refreshSessionStatuses();
    }

    private void refreshSessionStatuses() {
        StaffRepository.SessionOption session = sessionSelector.getValue();
        if (session == null) {
            sessionAvailability = java.util.Map.of();
            sessionAssignments = List.of();
            sessionStatus.setText("Session controls are available after an exam is imported.");
            assignmentSummary.setText("No saved room assignments for this session.");
        } else {
            try {
                sessionAvailability = repository.findSessionAvailability(session.sessionId());
                sessionAssignments = repository.findSessionAssignments(session.sessionId());
                long shortages = sessionAssignments.stream().filter(StaffRepository.RoomAssignmentSummary::hasShortage).count();
                assignmentSummary.setText(sessionAssignments.size() + " rooms assigned · " + shortages + " staffing shortages");
                viewAssignments.setDisable(sessionAssignments.isEmpty());
                sessionStatus.setText("Select staff rows; changes mark the plan data for review.");
            } catch (SQLException exception) {
                sessionAvailability = java.util.Map.of();
                sessionAssignments = List.of();
                assignmentSummary.setText("Could not load room assignments.");
                error("Could not load session staff status", exception);
            }
        }
        viewAssignments.setDisable(sessionAssignments.isEmpty());
        table.refresh();
    }

    private void showSessionAssignments() {
        if (sessionAssignments.isEmpty()) return;
        StringBuilder text = new StringBuilder();
        for (StaffRepository.RoomAssignmentSummary assignment : sessionAssignments) {
            text.append(assignment.eventCode()).append(" v").append(assignment.planVersion())
                    .append(" · ").append(assignment.planStatus()).append(assignment.stale() ? " · STALE" : "")
                    .append("\n").append(assignment.buildingCode()).append(" ").append(assignment.roomCode())
                    .append(" · ").append(assignment.assignedStaff()).append('/').append(assignment.requiredStaff())
                    .append(" assigned");
            if (assignment.hasShortage()) text.append(" · SHORT BY ")
                    .append(assignment.requiredStaff() - assignment.assignedStaff());
            if (assignment.staffIds() != null) text.append(" · ").append(assignment.staffIds());
            text.append("\n\n");
        }
        TextArea details = new TextArea(text.toString()); details.setEditable(false); details.setWrapText(true);
        details.setPrefColumnCount(75); details.setPrefRowCount(Math.min(22, Math.max(8, sessionAssignments.size() * 3)));
        Alert dialog = new Alert(Alert.AlertType.INFORMATION); dialog.setTitle("Session room assignments");
        dialog.setHeaderText("Assigned staff and room shortages"); dialog.getDialogPane().setContent(details);
        dialog.showAndWait();
    }

    private void applySessionAvailability() {
        StaffRepository.SessionOption session = sessionSelector.getValue();
        List<String> staffIds = table.getSelectionModel().getSelectedItems().stream()
                .filter(visibleStaff::contains).map(StaffMember::staffId).toList();
        String availability = switch (availabilityAction.getValue()) {
            case "Available" -> "AVAILABLE"; case "Unavailable" -> "UNAVAILABLE"; default -> "NOT_SET";
        };
        if (session == null) { info("Choose an exam session", "Import an exam before changing session status."); return; }
        if (staffIds.isEmpty()) { info("Select visible staff", "Select at least one visible staff row first."); return; }
        boolean mayAffectPlans = "UNAVAILABLE".equals(availability);
        Task<List<StaffRepository.AssignedRoomView>> task = new Task<>() {
            @Override protected List<StaffRepository.AssignedRoomView> call() throws Exception {
                repository.setSessionAvailability(session.sessionId(), staffIds, availability);
                return mayAffectPlans ? repository.findAssignedRooms(session.sessionId(), staffIds) : List.of();
            }
        };
        task.setOnSucceeded(event -> {
            refreshSessionStatuses();
            if (mayAffectPlans) showAffectedRooms(staffIds, task.getValue());
            else info("Session availability updated", staffIds.size() + " staff records updated.");
        });
        task.setOnFailed(event -> error("Could not update session availability", task.getException()));
        start(task, "staff-session-availability-update");
    }

    private static String displayStatus(String availability) {
        if (availability == null) return "—";
        return switch (availability) {
            case "NOT_SET" -> "Not set"; case "AVAILABLE" -> "Available"; case "UNAVAILABLE" -> "Unavailable";
            default -> availability;
        };
    }

    private void showAffectedRooms(List<String> staffIds, List<StaffRepository.AssignedRoomView> rooms) {
        StringBuilder text = new StringBuilder("Changed staff: ").append(String.join(", ", staffIds)).append("\n\n");
        if (rooms.isEmpty()) text.append("No saved plan assigns these staff to a room in this session.");
        else {
            for (var room : rooms) text.append(room.staffId()).append(" · ").append(room.eventCode())
                    .append(" v").append(room.planVersion()).append(" · ").append(room.planStatus())
                    .append(room.stale() ? " · STALE" : " · CURRENT").append(" · ")
                    .append(room.buildingCode()).append(" ").append(room.roomCode()).append('\n');
            text.append("\nReview or regenerate these plans to replace the affected duties.");
        }
        Alert dialog = new Alert(Alert.AlertType.INFORMATION);
        dialog.setTitle("Rooms to review");
        dialog.setHeaderText(rooms.isEmpty() ? "Staff status saved." : "Saved room duties need review.");
        TextArea details = new TextArea(text.toString()); details.setEditable(false); details.setWrapText(true);
        details.setPrefRowCount(Math.min(16, Math.max(5, rooms.size() + 2)));
        dialog.getDialogPane().setContent(details);
        dialog.showAndWait();
    }

    private void showAffiliations(StaffMember staff) {
        affiliationList.getItems().clear();
        if (staff == null) { selectedStaff.setText("Select a staff member to review course links."); return; }
        selectedStaff.setText("Course links for " + staff.staffId() + " · " + staff.fullName());
        try { affiliationList.getItems().setAll(repository.findAffiliationViews(staff.staffId())); }
        catch (SQLException exception) { error("Could not load course links", exception); }
    }

    private void addAffiliation() {
        StaffMember staff = table.getSelectionModel().getSelectedItem();
        if (staff == null) return;
        TextField course = field(""); course.setPromptText("Course code");
        Dialog<ButtonType> dialog = new Dialog<>(); dialog.setTitle("Add course link");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(new VBox(8,
                new Label("Course code (this staff member will not invigilate rooms holding it)"), course));
        Optional<ButtonType> result = dialog.showAndWait();
        if (result.orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        try {
            repository.addAffiliationByCourseCode(staff.staffId(), course.getText());
            refresh(); showAffiliations(staff);
        } catch (SQLException | IllegalArgumentException exception) { error("Could not add course link", exception); }
    }

    private void removeAffiliation() {
        StaffMember staff = table.getSelectionModel().getSelectedItem();
        StaffRepository.AffiliationView selected = affiliationList.getSelectionModel().getSelectedItem();
        if (staff == null || selected == null) return;
        try {
            repository.removeAffiliationByCourseCode(staff.staffId(), selected.courseCode());
            refresh(); showAffiliations(staff);
        } catch (SQLException | IllegalArgumentException exception) { error("Could not remove course link", exception); }
    }

    private void importAffiliations() {
        FileChooser chooser = new FileChooser(); chooser.setTitle("Choose staff course links CSV");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"));
        var file = chooser.showOpenDialog(getScene().getWindow()); if (file == null) return;
        importLinksButton.setDisable(true);
        Task<AffiliationImportPreview> task = new Task<>() {
            @Override protected AffiliationImportPreview call() throws Exception {
                return new StaffAffiliationCsvImporter().preview(file.toPath(), repository.findAffiliationImportContext());
            }
        };
        task.setOnSucceeded(event -> reviewAffiliationImport(task.getValue()));
        task.setOnFailed(event -> {
            importLinksButton.setDisable(false); error("Could not read course links CSV", task.getException());
        });
        start(task, "staff-course-csv-preview");
    }

    private void reviewAffiliationImport(AffiliationImportPreview preview) {
        StringBuilder text = new StringBuilder("File: ").append(preview.fileName())
                .append("\nNew links: ").append(preview.count(AffiliationImportPreview.Status.ADD))
                .append("\nExisting links skipped: ").append(preview.count(AffiliationImportPreview.Status.SKIP))
                .append("\nErrors: ").append(preview.count(AffiliationImportPreview.Status.ERROR)).append("\n\n");
        int shown = 0;
        for (AffiliationImportPreview.Row row : preview.rows()) {
            if (row.status() == AffiliationImportPreview.Status.ADD && shown++ >= 15) continue;
            text.append("Row ").append(row.sourceRow()).append(" · ").append(row.status()).append(" · ")
                    .append(row.staffId()).append(" · ").append(row.courseCode());
            if (!row.message().isBlank()) text.append(" · ").append(row.message());
            text.append('\n');
        }
        TextArea details = new TextArea(text.toString()); details.setEditable(false); details.setWrapText(true);
        details.setPrefRowCount(18);
        Alert dialog = new Alert(preview.hasErrors() ? Alert.AlertType.ERROR : Alert.AlertType.CONFIRMATION);
        dialog.setTitle("Review staff course links");
        dialog.setHeaderText(preview.hasErrors() ? "Fix relationship errors before importing."
                : "Review course links before importing.");
        dialog.getDialogPane().setContent(new VBox(8,
                new Label("Course records must exist before staff-course links can be imported."), details));
        dialog.getButtonTypes().setAll(preview.hasErrors() ? ButtonType.CLOSE : ButtonType.OK, ButtonType.CANCEL);
        Optional<ButtonType> result = dialog.showAndWait();
        if (preview.hasErrors() || result.orElse(ButtonType.CANCEL) != ButtonType.OK) {
            importLinksButton.setDisable(false); return;
        }
        Task<Integer> save = new Task<>() {
            @Override protected Integer call() throws Exception { return repository.commitAffiliationImport(preview); }
        };
        save.setOnSucceeded(event -> {
            importLinksButton.setDisable(false);
            try { refresh(); showAffiliations(table.getSelectionModel().getSelectedItem()); }
            catch (SQLException exception) { error("Links saved but dashboard refresh failed", exception); }
            info("Import complete", save.getValue() + " new staff-course links added.");
        });
        save.setOnFailed(event -> {
            importLinksButton.setDisable(false); error("Could not save staff course links", save.getException());
        });
        start(save, "staff-course-csv-import");
    }

    private void exportAffiliations() {
        FileChooser chooser = new FileChooser(); chooser.setTitle("Export staff course links");
        chooser.setInitialFileName("invigilator_course_assignments.csv");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"));
        var file = chooser.showSaveDialog(getScene().getWindow()); if (file == null) return;
        try (CSVPrinter printer = new CSVPrinter(Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8), CSVFormat.DEFAULT)) {
            printer.printRecord("staff_id", "course_code", "data_label");
            List<StaffRepository.AffiliationExportRow> rows = repository.findAllAffiliations();
            for (StaffRepository.AffiliationExportRow row : rows) printer.printRecord(row.staffId(), row.courseCode(),
                    row.dataLabel());
            info("Export complete", rows.size() + " staff-course links exported.");
        } catch (IOException | SQLException exception) { error("Could not export course links", exception); }
    }

    private static void start(Task<?> task, String name) {
        Thread worker = new Thread(task, name);
        worker.setDaemon(true);
        worker.start();
    }

    private void editStaff(StaffMember current) {
        Dialog<StaffMember> dialog = new Dialog<>();
        dialog.setTitle(current == null ? "Add staff" : "Edit staff");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        TextField id = field(current == null ? "" : current.staffId());
        TextField name = field(current == null ? "" : current.fullName());
        TextField department = field(current == null ? "" : text(current.department()));
        TextField designation = field(current == null ? "" : text(current.designation()));
        ChoiceBox<String> status = new ChoiceBox<>(FXCollections.observableArrayList("Active", "Inactive"));
        status.setValue(current == null || current.active() ? "Active" : "Inactive");
        id.setDisable(current != null);
        VBox form = new VBox(8, new Label("Staff ID"), id, new Label("Name"), name,
                new Label("Department"), department, new Label("Designation"), designation,
                new Label("Status"), status);
        form.setPadding(new Insets(12));
        dialog.getDialogPane().setContent(form);
        dialog.setResultConverter(button -> button == ButtonType.OK
                ? new StaffMember(id.getText(), name.getText(), department.getText(), designation.getText(),
                        "Active".equals(status.getValue()), current == null ? "OPERATIONAL" : current.dataLabel())
                : null);
        Optional<StaffMember> result = dialog.showAndWait();
        if (result.isEmpty()) return;
        try {
            if (current == null) repository.insert(result.get());
            else repository.update(result.get());
            refresh();
        } catch (SQLException | IllegalArgumentException exception) {
            error("Could not save staff", exception);
        }
    }

    private void toggleSelected() {
        StaffMember selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        try {
            repository.setActive(selected.staffId(), !selected.active());
            refresh();
        } catch (SQLException | IllegalArgumentException exception) {
            error("Could not change staff status", exception);
        }
    }

    private void importCsv() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose invigilator CSV");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"));
        var file = chooser.showOpenDialog(getScene().getWindow());
        if (file == null) return;
        try {
            StaffImportPreview preview = new StaffCsvImporter().preview(file.toPath(), repository.findAll());
            showPreview(preview);
        } catch (IOException | SQLException exception) {
            error("Could not read invigilator CSV", exception);
        }
    }

    private void showPreview(StaffImportPreview initialPreview) {
        java.util.Set<Integer> excluded = new java.util.HashSet<>();
        TableView<StaffImportPreview.Row> previewTable = new TableView<>(FXCollections.observableArrayList(initialPreview.rows()));
        previewTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        TableColumn<StaffImportPreview.Row, String> rowCol = new TableColumn<>("Row");
        rowCol.setCellValueFactory(cell -> new ReadOnlyStringWrapper(String.valueOf(cell.getValue().sourceRow())));
        rowCol.setPrefWidth(55);

        TableColumn<StaffImportPreview.Row, String> statusCol = new TableColumn<>("Action");
        statusCol.setCellValueFactory(cell -> new ReadOnlyStringWrapper(
                excluded.contains(cell.getValue().sourceRow()) ? "EXCLUDE" : cell.getValue().status().toString()));
        statusCol.setPrefWidth(85);

        TableColumn<StaffImportPreview.Row, String> idCol = new TableColumn<>("Staff ID");
        idCol.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().staff() == null ? "" : cell.getValue().staff().staffId()));
        idCol.setPrefWidth(110);

        TableColumn<StaffImportPreview.Row, String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().staff() == null ? "" : cell.getValue().staff().fullName()));
        nameCol.setPrefWidth(180);

        TableColumn<StaffImportPreview.Row, String> msgCol = new TableColumn<>("Notes / Diffs");
        msgCol.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().message()));
        msgCol.setPrefWidth(260);

        previewTable.getColumns().addAll(List.of(rowCol, statusCol, idCol, nameCol, msgCol));
        previewTable.setPrefHeight(300);

        Label header = new Label();
        Runnable updateHeader = () -> {
            int toAdd = (int) initialPreview.rows().stream()
                    .filter(r -> r.status() == StaffImportPreview.Status.ADD && !excluded.contains(r.sourceRow())).count();
            int skipped = (int) initialPreview.rows().stream()
                    .filter(r -> r.status() == StaffImportPreview.Status.SKIP || (r.status() == StaffImportPreview.Status.ADD && excluded.contains(r.sourceRow()))).count();
            header.setText("File: " + initialPreview.fileName() + " · To Add: " + toAdd + " · Skipped/Excluded: " + skipped + " · Errors: " + initialPreview.count(StaffImportPreview.Status.ERROR));
        };
        updateHeader.run();

        Button toggleBtn = new Button("Exclude / Re-include selected valid row(s)");
        toggleBtn.setOnAction(e -> {
            for (StaffImportPreview.Row row : previewTable.getSelectionModel().getSelectedItems()) {
                if (row.status() == StaffImportPreview.Status.ADD) {
                    if (!excluded.add(row.sourceRow())) excluded.remove(row.sourceRow());
                }
            }
            previewTable.refresh();
            updateHeader.run();
        });

        VBox content = new VBox(8, header, previewTable, toggleBtn);
        content.setPadding(new Insets(10));

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Review invigilator import");
        dialog.setHeaderText(initialPreview.hasErrors() ? "Fix the CSV errors before importing." : "Select rows to include/exclude before importing.");
        dialog.getDialogPane().setContent(content);
        ButtonType importType = new ButtonType("Confirm and Import", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(initialPreview.hasErrors() ? ButtonType.CLOSE : importType, ButtonType.CANCEL);
        if (initialPreview.hasErrors()) {
            dialog.showAndWait();
            return;
        }
        Optional<ButtonType> result = dialog.showAndWait();
        if (result.orElse(ButtonType.CANCEL) != importType) return;
        try {
            StaffImportPreview toCommit = initialPreview.withExcludedRows(excluded);
            int added = repository.commitImport(toCommit);
            refresh();
            info("Import complete", added + " new staff records added. " + (excluded.isEmpty() ? "" : excluded.size() + " rows excluded."));
        } catch (SQLException | IllegalArgumentException exception) {
            error("Could not import staff", exception);
        }
    }

    private void exportCsv(Button exportButton) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export invigilators");
        chooser.setInitialFileName("invigilators.csv");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"));
        var file = chooser.showSaveDialog(getScene().getWindow());
        if (file == null) return;
        List<StaffMember> rows = List.copyOf(visibleStaff);
        exportButton.setDisable(true);
        Task<Integer> task = new Task<>() {
            @Override protected Integer call() throws Exception {
                try (CSVPrinter printer = new CSVPrinter(Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8),
                        CSVFormat.DEFAULT)) {
                    printer.printRecord("staff_id", "full_name", "department", "designation", "active", "data_label");
                    for (StaffMember staff : rows) printer.printRecord(staff.staffId(), staff.fullName(),
                            staff.department(), staff.designation(), staff.active(), staff.dataLabel());
                }
                return rows.size();
            }
        };
        task.setOnSucceeded(event -> {
            exportButton.setDisable(false);
            info("Export complete", task.getValue() + " staff records exported.");
        });
        task.setOnFailed(event -> {
            exportButton.setDisable(false);
            error("Could not export staff", task.getException());
        });
        start(task, "staff-csv-export");
    }

    private static TextField field(String value) {
        TextField field = new TextField(value);
        field.setMaxWidth(Double.MAX_VALUE);
        return field;
    }

    private static String text(String value) { return value == null ? "" : value; }

    private static void error(String title, Throwable exception) {
        UiFeedback.error(title, exception);
    }

    private static void info(String title, String message) {
        UiFeedback.info(title, message);
    }
}
