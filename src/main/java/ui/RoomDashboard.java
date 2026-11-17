package ui;

import domain.Room;
import importexport.RoomCsvImporter;
import importexport.RoomCsvPreview;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import persistence.RoomRepository;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.SelectionMode;
import javafx.concurrent.Task;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.event.ActionEvent;
import javafx.stage.FileChooser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Room inventory list, filters, and CSV controls. */
public final class RoomDashboard extends BorderPane {
    private final RoomRepository repository;
    private final ObservableList<Room> allRooms = FXCollections.observableArrayList();
    private final FilteredList<Room> visibleRooms = new FilteredList<>(allRooms);
    private Map<String, List<RoomCsvPreview.SeatPosition>> blockedSeats = Map.of();
    private final TableView<Room> table = new TableView<>();
    private final TextField search = new TextField();
    private final TextField floor = new TextField();
    private final ChoiceBox<String> building = new ChoiceBox<>();
    private final ChoiceBox<String> capacityStatus = new ChoiceBox<>(FXCollections.observableArrayList("All", "ESTIMATED", "VERIFIED", "OVERRIDDEN"));
    private final ChoiceBox<String> active = new ChoiceBox<>(FXCollections.observableArrayList("All", "Active", "Inactive"));
    private final ChoiceBox<String> sessionAvailabilityFilter = new ChoiceBox<>(
            FXCollections.observableArrayList("All availability", "Available", "Unavailable"));
    private final ChoiceBox<RoomRepository.SessionOption> sessionSelector = new ChoiceBox<>();
    private final ChoiceBox<String> availabilityAction = new ChoiceBox<>(FXCollections.observableArrayList("Available", "Unavailable", "Default"));
    private final Button allRoomsCard = new Button();
    private final Button activeRoomsCard = new Button();
    private final Button reviewRoomsCard = new Button();
    private final Button unavailableRoomsCard = new Button();
    private final HBox summaryCards = new HBox(10);
    private final Label count = new Label();
    private final Label sessionStatus = new Label("Session controls are available after an exam is imported.");
    private Map<String, Boolean> sessionAvailability = Map.of();

    public RoomDashboard(RoomRepository repository) throws SQLException {
        this.repository = repository;
        buildTable();
        buildControls();
        setCenter(table);
        BorderPane.setMargin(table, new Insets(12, 24, 12, 24));
        refresh();
    }

    private void buildTable() {
        column("Building", Room::buildingCode, 120);
        column("Floor", room -> String.valueOf(room.floor()), 70);
        column("Room", Room::roomCode, 150);
        column("Centre", room -> text(room.centreName()), 150);
        column("Seat grid", room -> room.rows() + " × " + room.columns(), 110);
        column("Configured", room -> String.valueOf(room.usableCapacity()), 100);
        column("Usable seats", room -> String.valueOf(room.rows() * room.columns()
                - blockedSeats.getOrDefault(room.roomId(), List.of()).size()), 110);
        column("Layout", room -> room.usableCapacity() == room.rows() * room.columns()
                - blockedSeats.getOrDefault(room.roomId(), List.of()).size() ? "Matches" : "Review", 90);
        column("Capacity status", Room::capacityStatus, 150);
        column("Session availability", room -> sessionAvailability.getOrDefault(room.roomId(), true) ? "Available" : "Unavailable", 140);
        column("Status", room -> room.active() ? "Active" : "Inactive", 100);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setItems(visibleRooms);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("No rooms match these filters."));
    }

    private void column(String title, java.util.function.Function<Room, String> value, double width) {
        TableColumn<Room, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        column.setPrefWidth(width);
        table.getColumns().add(column);
    }

    private void buildControls() {
        search.setPromptText("Search room, building, or centre");
        floor.setPromptText("Floor");
        search.textProperty().addListener((observable, oldValue, newValue) -> applyFilter());
        floor.textProperty().addListener((observable, oldValue, newValue) -> applyFilter());
        for (ChoiceBox<String> filter : List.of(building, capacityStatus, active)) filter.setOnAction(event -> applyFilter());
        sessionAvailabilityFilter.setValue("All availability");
        sessionAvailabilityFilter.setOnAction(event -> applyFilter());

        for (Button card : List.of(allRoomsCard, activeRoomsCard, reviewRoomsCard, unavailableRoomsCard)) {
            card.getStyleClass().add("summary-card");
            card.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(card, Priority.ALWAYS);
        }
        allRoomsCard.setOnAction(event -> filterFromSummary(null, null));
        activeRoomsCard.setOnAction(event -> filterFromSummary(active, "Active"));
        reviewRoomsCard.setOnAction(event -> filterFromSummary(capacityStatus, "ESTIMATED"));
        unavailableRoomsCard.setOnAction(event -> filterFromSummary(sessionAvailabilityFilter, "Unavailable"));
        summaryCards.getChildren().setAll(allRoomsCard, activeRoomsCard, reviewRoomsCard, unavailableRoomsCard);

        Button add = new Button("Add room"); add.setOnAction(event -> editRoom(null));
        Button edit = new Button("Edit selected"); edit.setOnAction(event -> {
            if (table.getSelectionModel().getSelectedItems().size() != 1) {
                info("Select one room", "Choose exactly one room row to edit."); return;
            }
            editRoom(table.getSelectionModel().getSelectedItem());
        });
        Button status = new Button("Change status"); status.setOnAction(event -> toggleSelected());
        Button importCsv = new Button("Import CSV"); importCsv.setOnAction(event -> importCsv());
        Button exportCsv = new Button("Export CSV"); exportCsv.setOnAction(event -> exportCsv(exportCsv));
        sessionSelector.setOnAction(event -> refreshSessionAvailability());
        availabilityAction.setValue("Unavailable");
        Button selectVisible = new Button("Select visible"); selectVisible.setOnAction(event -> table.getSelectionModel().selectAll());
        Button clearSelection = new Button("Clear selection"); clearSelection.setOnAction(event -> table.getSelectionModel().clearSelection());
        Button applyAvailability = new Button("Apply to selected"); applyAvailability.setOnAction(event -> applySessionAvailability());
        HBox filters = new HBox(10, search, building, floor, capacityStatus, active, sessionAvailabilityFilter, count);
        HBox actions = new HBox(8, add, edit, status, importCsv, exportCsv, selectVisible, clearSelection);
        HBox sessionControls = new HBox(8, new Label("Exam session"), sessionSelector, availabilityAction,
                applyAvailability, sessionStatus);
        VBox controls = new VBox(12, summaryCards, filters, actions, sessionControls);
        controls.setPadding(new Insets(20, 24, 8, 24));
        setTop(controls);
    }

    private void applyFilter() {
        String query = search.getText().trim().toLowerCase();
        String selectedBuilding = building.getValue();
        String selectedStatus = capacityStatus.getValue();
        String selectedActive = active.getValue();
        String selectedAvailability = sessionAvailabilityFilter.getValue();
        String floorText = floor.getText().trim();
        visibleRooms.setPredicate(room -> {
            boolean matchesQuery = query.isEmpty() || room.roomId().toLowerCase().contains(query)
                    || room.roomCode().toLowerCase().contains(query) || room.buildingCode().toLowerCase().contains(query)
                    || text(room.centreName()).toLowerCase().contains(query);
            boolean matchesFloor = floorText.isEmpty() || String.valueOf(room.floor()).equals(floorText);
            boolean matchesBuilding = "All".equals(selectedBuilding) || room.buildingCode().equals(selectedBuilding);
            boolean matchesCapacity = "All".equals(selectedStatus) || room.capacityStatus().equals(selectedStatus);
            boolean matchesActive = "All".equals(selectedActive) || ("Active".equals(selectedActive) == room.active());
            boolean roomAvailable = sessionAvailability.getOrDefault(room.roomId(), true);
            boolean matchesAvailability = "All availability".equals(selectedAvailability)
                    || ("Available".equals(selectedAvailability) == roomAvailable);
            return matchesQuery && matchesFloor && matchesBuilding && matchesCapacity && matchesActive
                    && matchesAvailability;
        });
        count.setText(visibleRooms.size() + " shown · " + allRooms.size() + " total");
    }

    private void refresh() throws SQLException {
        allRooms.setAll(repository.findAll());
        blockedSeats = repository.findBlockedSeats();
        refreshSessions();
        List<String> buildings = new ArrayList<>(List.of("All"));
        allRooms.stream().map(Room::buildingCode).distinct().sorted().forEach(buildings::add);
        String prior = building.getValue();
        building.setItems(FXCollections.observableArrayList(buildings));
        building.setValue(buildings.contains(prior) ? prior : "All");
        if (capacityStatus.getValue() == null) capacityStatus.setValue("All");
        if (active.getValue() == null) active.setValue("All");
        updateSummaryCards();
        table.refresh();
        applyFilter();
    }

    public void refreshData() {
        try { refresh(); } catch (SQLException exception) { error("Could not refresh rooms", exception); }
    }

    private void refreshSessions() throws SQLException {
        String selectedId = sessionSelector.getValue() == null ? null : sessionSelector.getValue().sessionId();
        List<RoomRepository.SessionOption> sessions = repository.findSessions();
        sessionSelector.setItems(FXCollections.observableArrayList(sessions));
        RoomRepository.SessionOption selected = sessions.stream().filter(item -> item.sessionId().equals(selectedId))
                .findFirst().orElse(sessions.isEmpty() ? null : sessions.getFirst());
        sessionSelector.setValue(selected);
        sessionSelector.setDisable(sessions.isEmpty());
        sessionAvailabilityFilter.setDisable(sessions.isEmpty());
        if (sessions.isEmpty()) refreshSessionAvailability();
    }

    private void refreshSessionAvailability() {
        RoomRepository.SessionOption session = sessionSelector.getValue();
        if (session == null) {
            sessionAvailability = Map.of();
            sessionStatus.setText("Session controls are available after an exam is imported.");
        } else {
            try {
                sessionAvailability = repository.findSessionAvailability(session.sessionId());
                long unavailable = sessionAvailability.values().stream().filter(available -> !available).count();
                sessionStatus.setText(unavailable + " unavailable · unlisted rooms are available");
            } catch (SQLException exception) {
                sessionAvailability = Map.of();
                error("Could not load session room availability", exception);
            }
        }
        table.refresh();
        updateSummaryCards();
        applyFilter();
    }

    private void updateSummaryCards() {
        int activeRooms = (int) allRooms.stream().filter(Room::active).count();
        int totalCapacity = allRooms.stream().filter(Room::active).mapToInt(Room::usableCapacity).sum();
        int needsReview = (int) allRooms.stream().filter(room -> "ESTIMATED".equals(room.capacityStatus())).count();
        long unavailable = sessionAvailability.values().stream().filter(available -> !available).count();
        boolean isDemo = allRooms.stream().anyMatch(r -> "DEMO".equalsIgnoreCase(r.dataLabel()));
        allRoomsCard.setText((isDemo ? "[DEMO DATA] " : "") + allRooms.size() + " rooms\n" + totalCapacity + " active seats");
        activeRoomsCard.setText(activeRooms + "\nActive rooms");
        reviewRoomsCard.setText(needsReview + "\nCapacity to verify");
        unavailableRoomsCard.setText(sessionSelector.getValue() == null
                ? "—\nSelect an exam session" : unavailable + "\nUnavailable this session");
        unavailableRoomsCard.setDisable(sessionSelector.getValue() == null);
        sessionAvailabilityFilter.setDisable(sessionSelector.getValue() == null);
    }

    private void filterFromSummary(ChoiceBox<String> filter, String value) {
        search.clear();
        floor.clear();
        building.setValue("All");
        capacityStatus.setValue("All");
        active.setValue("All");
        sessionAvailabilityFilter.setValue("All availability");
        if (filter != null) filter.setValue(value);
    }

    private void applySessionAvailability() {
        RoomRepository.SessionOption session = sessionSelector.getValue();
        List<String> roomIds = table.getSelectionModel().getSelectedItems().stream().map(Room::roomId).toList();
        if (session == null) { info("Choose an exam session", "Import an exam before changing room availability."); return; }
        if (roomIds.isEmpty()) { info("Select rooms", "Select one or more rooms first."); return; }
        Boolean available = switch (availabilityAction.getValue()) {
            case "Available" -> true; case "Unavailable" -> false; default -> null;
        };
        String statusText = available == null ? "return to default availability" : available ? "mark available" : "mark unavailable";
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION, "Confirm " + statusText + " for "
                + roomIds.size() + " selected rooms in " + session + "?", ButtonType.OK, ButtonType.CANCEL);
        confirmation.setTitle("Confirm room availability change");
        confirmation.setHeaderText("Update selected rooms");
        if (confirmation.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        Task<List<RoomRepository.AssignedPlanView>> task = new Task<>() {
            @Override protected List<RoomRepository.AssignedPlanView> call() throws Exception {
                repository.setSessionAvailability(session.sessionId(), roomIds, available);
                return Boolean.FALSE.equals(available) ? repository.findPlansUsingRooms(session.sessionId(), roomIds) : List.of();
            }
        };
        task.setOnSucceeded(event -> {
            refreshSessionAvailability();
            if (Boolean.FALSE.equals(available)) showAffectedPlans(roomIds, task.getValue());
            else info("Room availability updated", roomIds.size() + " rooms updated for " + session + ".");
        });
        task.setOnFailed(event -> error("Could not update room availability", task.getException()));
        Thread worker = new Thread(task, "room-session-availability"); worker.setDaemon(true); worker.start();
    }

    private void showAffectedPlans(List<String> roomIds, List<RoomRepository.AssignedPlanView> plans) {
        StringBuilder message = new StringBuilder(roomIds.size() + " rooms marked unavailable. ");
        if (plans.isEmpty()) message.append("No saved seating plan uses these rooms.");
        else {
            message.append("Saved plans using them need review:\n");
            for (RoomRepository.AssignedPlanView plan : plans) message.append("• ").append(plan.eventCode())
                    .append(" v").append(plan.version()).append(" · ").append(plan.planStatus())
                    .append(plan.stale() ? " · stale" : " · current").append(" · ")
                    .append(plan.buildingCode()).append(" ").append(plan.roomCode()).append('\n');
        }
        info("Room availability updated", message.toString());
    }

    private void editRoom(Room current) {
        Map<String, List<RoomCsvPreview.SeatPosition>> blocked;
        try { blocked = repository.findBlockedSeats(); }
        catch (SQLException exception) { error("Could not read seat layout", exception); return; }

        Dialog<RoomEdit> dialog = new Dialog<>();
        dialog.setTitle(current == null ? "Add room" : "Edit room");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        TextField id = field(current == null ? "" : current.roomId());
        TextField buildingCode = field(current == null ? "" : current.buildingCode());
        TextField roomCode = field(current == null ? "" : current.roomCode());
        TextField floorNumber = field(current == null ? "0" : String.valueOf(current.floor()));
        TextField centre = field(current == null ? "" : text(current.centreName()));
        TextField roomType = field(current == null ? "CLASSROOM" : current.roomType());
        TextField rows = field(current == null ? "5" : String.valueOf(current.rows()));
        TextField columns = field(current == null ? "6" : String.valueOf(current.columns()));
        TextField capacity = field(current == null ? "30" : String.valueOf(current.usableCapacity()));
        TextField blockedSeats = field(current == null ? "" : blockedText(blocked.getOrDefault(current.roomId(), List.of())));
        TextField blockedReason = field("Blocked by administrator");
        ChoiceBox<String> capacityState = new ChoiceBox<>(FXCollections.observableArrayList("ESTIMATED", "VERIFIED", "OVERRIDDEN"));
        capacityState.setValue(current == null ? "ESTIMATED" : current.capacityStatus());
        id.setDisable(current != null);
        VBox form = new VBox(7,
                new Label("Room ID"), id, new Label("Building code"), buildingCode,
                new Label("Room code"), roomCode, new Label("Floor"), floorNumber,
                new Label("Centre (optional)"), centre, new Label("Room type"), roomType,
                new Label("Seat grid rows × columns"), new HBox(8, rows, columns),
                new Label("Usable capacity"), capacity, new Label("Capacity status"), capacityState,
                new Label("Blocked seats (row:column; row:column)"), blockedSeats,
                new Label("Reason for blocked seats (required when any are blocked)"), blockedReason);
        form.setPadding(new Insets(12));
        dialog.getDialogPane().setContent(form);
        Button saveButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        saveButton.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                new Room(id.getText(), buildingCode.getText(), roomCode.getText(), Integer.parseInt(floorNumber.getText()),
                        centre.getText(), roomType.getText(), Integer.parseInt(rows.getText()), Integer.parseInt(columns.getText()),
                        Integer.parseInt(capacity.getText()), capacityState.getValue(), true, "OPERATIONAL");
                List<RoomCsvPreview.SeatPosition> positions = parseBlocked(blockedSeats.getText());
                validateBlocked(positions, Integer.parseInt(rows.getText()), Integer.parseInt(columns.getText()));
                if (!positions.isEmpty() && blockedReason.getText().isBlank()) throw new IllegalArgumentException("Enter a reason for blocked seats.");
            } catch (IllegalArgumentException exception) {
                event.consume(); error("Check the room details", exception);
            }
        });
        dialog.setResultConverter(button -> {
            if (button != ButtonType.OK) return null;
            Room room = new Room(id.getText(), buildingCode.getText(), roomCode.getText(),
                    Integer.parseInt(floorNumber.getText()), centre.getText(), roomType.getText(),
                    Integer.parseInt(rows.getText()), Integer.parseInt(columns.getText()),
                    Integer.parseInt(capacity.getText()), capacityState.getValue(),
                    current == null || current.active(), current == null ? "OPERATIONAL" : current.dataLabel());
            return new RoomEdit(room, parseBlocked(blockedSeats.getText()), blockedReason.getText());
        });
        Optional<RoomEdit> result = dialog.showAndWait();
        if (result.isEmpty()) return;
        try {
            RoomEdit edit = result.get();
            if (current == null) repository.insert(edit.room(), edit.blockedSeats(), edit.reason());
            else repository.update(edit.room(), edit.blockedSeats(), edit.reason());
            refresh();
        } catch (SQLException | IllegalArgumentException exception) {
            error("Could not save room", exception);
        }
    }

    private void toggleSelected() {
        Room selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        try { repository.setActive(selected.roomId(), !selected.active()); refresh(); }
        catch (SQLException | IllegalArgumentException exception) { error("Could not change room status", exception); }
    }

    private void importCsv() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose room inventory CSV");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"));
        var file = chooser.showOpenDialog(getScene().getWindow());
        if (file == null) return;
        try { showPreview(new RoomCsvImporter().preview(file.toPath(), repository.findAll(), repository.findBlockedSeats())); }
        catch (IOException | SQLException exception) { error("Could not read room CSV", exception); }
    }

    private void showPreview(RoomCsvPreview initialPreview) {
        java.util.Set<Integer> excluded = new java.util.HashSet<>();
        TableView<RoomCsvPreview.Row> previewTable = new TableView<>(FXCollections.observableArrayList(initialPreview.rows()));
        previewTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        TableColumn<RoomCsvPreview.Row, String> rowCol = new TableColumn<>("Row");
        rowCol.setCellValueFactory(cell -> new ReadOnlyStringWrapper(String.valueOf(cell.getValue().sourceRow())));
        rowCol.setPrefWidth(55);

        TableColumn<RoomCsvPreview.Row, String> statusCol = new TableColumn<>("Action");
        statusCol.setCellValueFactory(cell -> new ReadOnlyStringWrapper(
                excluded.contains(cell.getValue().sourceRow()) ? "EXCLUDE" : cell.getValue().status().toString()));
        statusCol.setPrefWidth(85);

        TableColumn<RoomCsvPreview.Row, String> roomCol = new TableColumn<>("Room");
        roomCol.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().room() == null ? ""
                : cell.getValue().room().buildingCode() + " " + cell.getValue().room().roomCode()));
        roomCol.setPrefWidth(120);

        TableColumn<RoomCsvPreview.Row, String> capCol = new TableColumn<>("Capacity");
        capCol.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().room() == null ? ""
                : cell.getValue().room().usableCapacity() + " (" + cell.getValue().room().capacityStatus() + ")"));
        capCol.setPrefWidth(120);

        TableColumn<RoomCsvPreview.Row, String> msgCol = new TableColumn<>("Notes / Diffs");
        msgCol.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().message()));
        msgCol.setPrefWidth(280);

        previewTable.getColumns().addAll(List.of(rowCol, statusCol, roomCol, capCol, msgCol));
        previewTable.setPrefHeight(300);

        Label header = new Label();
        Runnable updateHeader = () -> {
            int toAdd = (int) initialPreview.rows().stream()
                    .filter(r -> r.status() == RoomCsvPreview.Status.ADD && !excluded.contains(r.sourceRow())).count();
            int skipped = (int) initialPreview.rows().stream()
                    .filter(r -> r.status() == RoomCsvPreview.Status.SKIP || (r.status() == RoomCsvPreview.Status.ADD && excluded.contains(r.sourceRow()))).count();
            header.setText("File: " + initialPreview.fileName() + " · To Add: " + toAdd + " · Skipped/Excluded: " + skipped + " · Errors: " + initialPreview.count(RoomCsvPreview.Status.ERROR));
        };
        updateHeader.run();

        Button toggleBtn = new Button("Exclude / Re-include selected valid row(s)");
        toggleBtn.setOnAction(e -> {
            for (RoomCsvPreview.Row row : previewTable.getSelectionModel().getSelectedItems()) {
                if (row.status() == RoomCsvPreview.Status.ADD) {
                    if (!excluded.add(row.sourceRow())) excluded.remove(row.sourceRow());
                }
            }
            previewTable.refresh();
            updateHeader.run();
        });

        VBox content = new VBox(8, header, previewTable, toggleBtn);
        if (initialPreview.hasAvailabilityColumn()) {
            content.getChildren().add(new Label("is_available is ignored because no exam session is loaded."));
        }
        content.setPadding(new Insets(10));

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Review room import");
        dialog.setHeaderText(initialPreview.hasErrors() ? "Fix CSV errors before importing." : "Select rooms to include/exclude before importing.");
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
            RoomCsvPreview toCommit = initialPreview.withExcludedRows(excluded);
            int added = repository.commitImport(toCommit);
            refresh();
            info("Import complete", added + " new rooms added. " + (excluded.isEmpty() ? "" : excluded.size() + " rows excluded."));
        } catch (SQLException | IllegalArgumentException exception) {
            error("Could not import rooms", exception);
        }
    }

    private void exportCsv(Button exportButton) {
        FileChooser chooser = new FileChooser(); chooser.setTitle("Export filtered rooms");
        chooser.setInitialFileName("rooms.csv");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"));
        var file = chooser.showSaveDialog(getScene().getWindow());
        if (file == null) return;
        List<Room> rows = List.copyOf(visibleRooms);
        Map<String, List<RoomCsvPreview.SeatPosition>> blocked = new java.util.HashMap<>();
        for (Room room : rows) blocked.put(room.roomId(),
                List.copyOf(blockedSeats.getOrDefault(room.roomId(), List.of())));
        exportButton.setDisable(true);
        Task<Integer> task = new Task<>() {
            @Override protected Integer call() throws Exception {
                try (CSVPrinter printer = new CSVPrinter(Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8), CSVFormat.DEFAULT)) {
                    printer.printRecord("room_id", "building_code", "floor", "centre_name", "room_type", "num_rows",
                            "num_cols", "blocked_seats", "usable_capacity", "capacity_status", "active", "data_label");
                    for (Room room : rows) printer.printRecord(room.roomId(), room.buildingCode(), room.floor(),
                            room.centreName(), room.roomType(), room.rows(), room.columns(),
                            blockedText(blocked.getOrDefault(room.roomId(), List.of())), room.usableCapacity(),
                            room.capacityStatus(), room.active(), room.dataLabel());
                }
                return rows.size();
            }
        };
        task.setOnSucceeded(event -> {
            exportButton.setDisable(false);
            info("Export complete", task.getValue() + " rooms exported.");
        });
        task.setOnFailed(event -> {
            exportButton.setDisable(false);
            error("Could not export rooms", task.getException());
        });
        Thread worker = new Thread(task, "room-csv-export");
        worker.setDaemon(true);
        worker.start();
    }

    private static List<RoomCsvPreview.SeatPosition> parseBlocked(String value) {
        List<RoomCsvPreview.SeatPosition> result = new ArrayList<>();
        if (value.isBlank()) return result;
        for (String part : value.split(";")) {
            String[] coordinate = part.trim().split(":");
            if (coordinate.length != 2) throw new IllegalArgumentException("Blocked seats must look like 1:2; 3:4.");
            result.add(new RoomCsvPreview.SeatPosition(Integer.parseInt(coordinate[0]), Integer.parseInt(coordinate[1])));
        }
        return result;
    }

    private static void validateBlocked(List<RoomCsvPreview.SeatPosition> seats, int rows, int columns) {
        if (seats.stream().distinct().count() != seats.size()) throw new IllegalArgumentException("A blocked seat is listed more than once.");
        for (RoomCsvPreview.SeatPosition seat : seats) {
            if (seat.row() < 1 || seat.row() > rows || seat.column() < 1 || seat.column() > columns) {
                throw new IllegalArgumentException("Blocked seat coordinates must fall inside the room grid.");
            }
        }
    }

    private static String blockedText(List<RoomCsvPreview.SeatPosition> seats) {
        return seats.stream().map(seat -> seat.row() + ":" + seat.column()).collect(java.util.stream.Collectors.joining("; "));
    }

    private record RoomEdit(Room room, List<RoomCsvPreview.SeatPosition> blockedSeats, String reason) {}

    private static TextField field(String value) { TextField field = new TextField(value); field.setMaxWidth(Double.MAX_VALUE); return field; }
    private static String text(String value) { return value == null ? "" : value; }
    private static void error(String title, Throwable exception) {
        UiFeedback.error(title, exception);
    }
    private static void info(String title, String message) {
        UiFeedback.info(title, message);
    }
}
