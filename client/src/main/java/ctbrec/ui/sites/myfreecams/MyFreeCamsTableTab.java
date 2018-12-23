package ctbrec.ui.sites.myfreecams;

import static java.nio.file.StandardOpenOption.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ctbrec.Config;
import ctbrec.Model;
import ctbrec.StringUtil;
import ctbrec.sites.mfc.MyFreeCams;
import ctbrec.sites.mfc.MyFreeCamsModel;
import ctbrec.sites.mfc.SessionState;
import ctbrec.ui.DesktopIntegration;
import ctbrec.ui.TabSelectionListener;
import ctbrec.ui.action.FollowAction;
import ctbrec.ui.action.PlayAction;
import ctbrec.ui.action.StartRecordingAction;
import ctbrec.ui.controls.SearchBox;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.Property;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.concurrent.Worker.State;
import javafx.concurrent.WorkerStateEvent;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Tab;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableColumn.SortType;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.FileChooser;
import javafx.util.Duration;

public class MyFreeCamsTableTab extends Tab implements TabSelectionListener {
    private static final transient Logger LOG = LoggerFactory.getLogger(MyFreeCamsTableTab.class);
    private ScrollPane scrollPane = new ScrollPane();
    private TableView<ModelTableRow> table = new TableView<ModelTableRow>();
    private ObservableList<ModelTableRow> filteredModels = FXCollections.observableArrayList();
    private ObservableList<ModelTableRow> observableModels = FXCollections.observableArrayList();
    private TableUpdateService updateService;
    private MyFreeCams mfc;
    private ReentrantLock lock = new ReentrantLock();
    private SearchBox filterInput;
    private Label count = new Label("models");
    private List<TableColumn<ModelTableRow, ?>> columns = new ArrayList<>();
    private ContextMenu popup;
    private long lastJsonWrite = 0;

    public MyFreeCamsTableTab(MyFreeCams mfc) {
        this.mfc = mfc;
        setText("Tabular");
        setClosable(false);
        createGui();
        loadData();
        initUpdateService();
        restoreState();
        filter(filterInput.getText());
    }

    private void initUpdateService() {
        updateService = new TableUpdateService(mfc);
        updateService.setPeriod(new Duration(TimeUnit.SECONDS.toMillis(1)));
        updateService.setOnSucceeded(this::onSuccess);
        updateService.setOnFailed((event) -> {
            LOG.info("Couldn't update MyFreeCams model table", event.getSource().getException());
        });
    }

    private void onSuccess(WorkerStateEvent evt) {
        Collection<SessionState> sessionStates = updateService.getValue();
        if (sessionStates == null) {
            return;
        }

        lock.lock();
        try {
            for (SessionState updatedModel : sessionStates) {
                ModelTableRow row = new ModelTableRow(updatedModel);
                int index = observableModels.indexOf(row);
                if (index == -1) {
                    observableModels.add(row);
                } else {
                    observableModels.get(index).update(updatedModel);
                }
            }

            for (Iterator<ModelTableRow> iterator = observableModels.iterator(); iterator.hasNext();) {
                ModelTableRow model = iterator.next();
                boolean found = false;
                for (SessionState sessionState : sessionStates) {
                    if(Objects.equals(sessionState.getUid(), model.uid)) {
                        found = true;
                        break;
                    }
                }
                if(!found) {
                    iterator.remove();
                }
            }
        } finally {
            lock.unlock();
        }

        filteredModels.clear();
        filter(filterInput.getText());
        table.sort();

        long now = System.currentTimeMillis();
        if( (now - lastJsonWrite) > TimeUnit.SECONDS.toMillis(30)) {
            lastJsonWrite = now;
            saveData();
        }
    }

    private void createGui() {
        BorderPane layout = new BorderPane();
        layout.setPadding(new Insets(5, 10, 10, 10));

        filterInput = new SearchBox(false);
        filterInput.setPromptText("Filter");
        filterInput.textProperty().addListener( (observableValue, oldValue, newValue) -> {
            String filter = filterInput.getText();
            Config.getInstance().getSettings().mfcModelsTableFilter = filter;
            lock.lock();
            try {
                filter(filter);
            } finally {
                lock.unlock();
            }
        });
        filterInput.getStyleClass().remove("search-box-icon");
        HBox.setHgrow(filterInput, Priority.ALWAYS);
        Button export = new Button("⬇");
        export.setOnAction(this::export);
        export.setTooltip(new Tooltip("Export data"));

        Button columnSelection = new Button("⚙");
        columnSelection.setOnAction(this::showColumnSelection);
        columnSelection.setTooltip(new Tooltip("Select columns"));
        HBox topBar = new HBox(5);
        topBar.getChildren().addAll(filterInput, count, export, columnSelection);
        count.prefHeightProperty().bind(filterInput.heightProperty());
        count.setAlignment(Pos.CENTER);
        layout.setTop(topBar);
        BorderPane.setMargin(topBar, new Insets(0, 0, 5, 0));

        table.setItems(observableModels);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.getSortOrder().addListener(createSortOrderChangedListener());
        table.addEventHandler(ContextMenuEvent.CONTEXT_MENU_REQUESTED, event -> {
            popup = createContextMenu();
            if (popup != null) {
                popup.show(table, event.getScreenX(), event.getScreenY());
            }
            event.consume();
        });
        table.addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
            if (popup != null) {
                popup.hide();
            }
        });

        int idx = 0;
        TableColumn<ModelTableRow, String> name = createTableColumn("Name", 200, idx++);
        name.setCellValueFactory(cdf -> cdf.getValue().nameProperty());
        addTableColumnIfEnabled(name);

        TableColumn<ModelTableRow, String> state = createTableColumn("State", 130, idx++);
        state.setCellValueFactory(cdf -> cdf.getValue().stateProperty());
        addTableColumnIfEnabled(state);

        TableColumn<ModelTableRow, Number> camscore = createTableColumn("Score", 75, idx++);
        camscore.setCellValueFactory(cdf -> cdf.getValue().camScoreProperty());
        addTableColumnIfEnabled(camscore);

        // this is always 0, use https://api.myfreecams.com/missmfc and https://api.myfreecams.com/missmfc/online
        //        TableColumn<SessionState, Number> missMfc = createTableColumn("Miss MFC", 75, idx++);
        //        missMfc.setCellValueFactory(cdf -> {
        //            Integer mmfc = Optional.ofNullable(cdf.getValue().getM()).map(m -> m.getMissmfc()).orElse(-1);
        //            return new SimpleIntegerProperty(mmfc);
        //        });
        //        addTableColumnIfEnabled(missMfc);

        TableColumn<ModelTableRow, String> newModel = createTableColumn("New", 60, idx++);
        newModel.setCellValueFactory(cdf -> cdf.getValue().newModelProperty());
        addTableColumnIfEnabled(newModel);

        TableColumn<ModelTableRow, String> ethnic = createTableColumn("Ethnicity", 130, idx++);
        ethnic.setCellValueFactory(cdf -> cdf.getValue().ethnicityProperty());
        addTableColumnIfEnabled(ethnic);

        TableColumn<ModelTableRow, String> country = createTableColumn("Country", 160, idx++);
        country.setCellValueFactory(cdf -> cdf.getValue().countryProperty());
        addTableColumnIfEnabled(country);

        TableColumn<ModelTableRow, String> continent = createTableColumn("Continent", 100, idx++);
        continent.setCellValueFactory(cdf -> cdf.getValue().continentProperty());
        addTableColumnIfEnabled(continent);

        TableColumn<ModelTableRow, String> occupation = createTableColumn("Occupation", 160, idx++);
        occupation.setCellValueFactory(cdf -> cdf.getValue().occupationProperty());
        addTableColumnIfEnabled(occupation);

        TableColumn<ModelTableRow, String> tags = createTableColumn("Tags", 300, idx++);
        tags.setCellValueFactory(cdf -> cdf.getValue().tagsProperty());
        addTableColumnIfEnabled(tags);

        TableColumn<ModelTableRow, String> blurp = createTableColumn("Blurp", 300, idx++);
        blurp.setCellValueFactory(cdf -> cdf.getValue().blurpProperty());
        addTableColumnIfEnabled(blurp);

        TableColumn<ModelTableRow, String> topic = createTableColumn("Topic", 600, idx++);
        topic.setCellValueFactory(cdf -> cdf.getValue().topicProperty());
        addTableColumnIfEnabled(topic);

        scrollPane.setFitToHeight(true);
        scrollPane.setFitToWidth(true);
        scrollPane.setContent(table);
        scrollPane.setStyle("-fx-background-color: -fx-background");
        layout.setCenter(scrollPane);
        setContent(layout);
    }

    private ContextMenu createContextMenu() {
        ObservableList<ModelTableRow> selectedStates = table.getSelectionModel().getSelectedItems();
        if (selectedStates.isEmpty()) {
            return null;
        }

        List<Model> selectedModels = new ArrayList<>();
        for (ModelTableRow sessionState : selectedStates) {
            if(sessionState.name.get() != null) {
                MyFreeCamsModel model = mfc.createModel(sessionState.name.get());
                mfc.getClient().update(model);
                selectedModels.add(model);
            }
        }

        MenuItem copyUrl = new MenuItem("Copy URL");
        copyUrl.setOnAction((e) -> {
            Model selected = selectedModels.get(0);
            final Clipboard clipboard = Clipboard.getSystemClipboard();
            final ClipboardContent content = new ClipboardContent();
            content.putString(selected.getUrl());
            clipboard.setContent(content);
        });

        MenuItem startRecording = new MenuItem("Start Recording");
        startRecording.setOnAction((e) -> startRecording(selectedModels));
        MenuItem openInBrowser = new MenuItem("Open in Browser");
        openInBrowser.setOnAction((e) -> DesktopIntegration.open(selectedModels.get(0).getUrl()));
        MenuItem openInPlayer = new MenuItem("Open in Player");
        openInPlayer.setOnAction((e) -> openInPlayer(selectedModels.get(0)));
        MenuItem follow = new MenuItem("Follow");
        follow.setOnAction((e) -> new FollowAction(getTabPane(), selectedModels).execute());

        ContextMenu menu = new ContextMenu();
        menu.getItems().addAll(startRecording, copyUrl, openInPlayer, openInBrowser, follow);

        if (selectedModels.size() > 1) {
            copyUrl.setDisable(true);
            openInPlayer.setDisable(true);
            openInBrowser.setDisable(true);
        }

        return menu;
    }

    private void startRecording(List<Model> selectedModels) {
        new StartRecordingAction(getTabPane(), selectedModels, mfc.getRecorder()).execute();
    }

    private void openInPlayer(Model selectedModel) {
        new PlayAction(getTabPane(), selectedModel).execute();
    }

    private void addTableColumnIfEnabled(TableColumn<ModelTableRow, ?> tc) {
        if(isColumnEnabled(tc)) {
            table.getColumns().add(tc);
        }
    }

    private void filter(String filter) {
        lock.lock();
        try {
            if (StringUtil.isBlank(filter)) {
                observableModels.addAll(filteredModels);
                filteredModels.clear();
                return;
            }

            String[] tokens = filter.split(" ");
            observableModels.addAll(filteredModels);
            filteredModels.clear();
            for (int i = 0; i < table.getItems().size(); i++) {
                StringBuilder sb = new StringBuilder();
                for (TableColumn<ModelTableRow, ?> tc : table.getColumns()) {
                    Object cellData = tc.getCellData(i);
                    if(cellData != null) {
                        String content = cellData.toString();
                        sb.append(content).append(' ');
                    }
                }
                String searchText = sb.toString();

                boolean tokensMissing = false;
                for (String token : tokens) {
                    if(!searchText.toLowerCase().contains(token.toLowerCase())) {
                        tokensMissing = true;
                        break;
                    }
                }
                if(tokensMissing) {
                    ModelTableRow sessionState = table.getItems().get(i);
                    filteredModels.add(sessionState);
                }
            }
            observableModels.removeAll(filteredModels);
        } finally {
            lock.unlock();
            int filtered = filteredModels.size();
            int showing = observableModels.size();
            int total = showing + filtered;
            count.setText(showing + "/" + total);
        }
    }

    private void export(ActionEvent evt) {
        FileChooser chooser = new FileChooser();
        File file = chooser.showSaveDialog(getTabPane().getScene().getWindow());
        if(file != null) {
            try(FileOutputStream fout = new FileOutputStream(file)) {
                PrintStream ps = new PrintStream(fout);
                List<ModelTableRow> union = new ArrayList<>();
                union.addAll(filteredModels);
                union.addAll(observableModels);
                ps.println("\"uid\",\"blurp\",\"camScore\",\"continent\",\"country\",\"ethnic\",\"name\",\"new\",\"occupation\",\"state\",\"tags\",\"topic\"");
                for (ModelTableRow row : union) {
                    ps.print("\"" + row.uid + "\""); ps.print(',');
                    ps.print(escape(row.blurp)); ps.print(',');
                    ps.print(escape(row.camScore)); ps.print(',');
                    ps.print(escape(row.continent)); ps.print(',');
                    ps.print(escape(row.country)); ps.print(',');
                    ps.print(escape(row.ethnic)); ps.print(',');
                    ps.print(escape(row.name)); ps.print(',');
                    ps.print(escape(row.newModel)); ps.print(',');
                    ps.print(escape(row.occupation)); ps.print(',');
                    ps.print(escape(row.state)); ps.print(',');
                    ps.print(escape(row.tags)); ps.print(',');
                    ps.print(escape(row.topic));
                    ps.println();
                }
            } catch (Exception e) {
                LOG.debug("Couldn't write mfc models table data: {}", e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private String escape(Property<?> prop) {
        String value = prop.getValue() != null ? prop.getValue().toString() : "";
        return "\"" + value.replaceAll("\"", "\"\"") + "\"";
    }

    private void showColumnSelection(ActionEvent evt) {
        ContextMenu menu = new ContextMenu();
        for (TableColumn<ModelTableRow, ?> tc : columns) {
            CheckMenuItem item = new CheckMenuItem(tc.getText());
            item.setSelected(isColumnEnabled(tc));
            menu.getItems().add(item);
            item.setOnAction(e -> {
                if(item.isSelected()) {
                    Config.getInstance().getSettings().mfcDisabledModelsTableColumns.remove(tc.getText());
                    for (int i = table.getColumns().size()-1; i>=0; i--) {
                        TableColumn<ModelTableRow, ?> other = table.getColumns().get(i);
                        int idx = (int) tc.getUserData();
                        int otherIdx = (int) other.getUserData();
                        if(otherIdx < idx) {
                            table.getColumns().add(i+1, tc);
                            break;
                        }
                    }
                } else {
                    Config.getInstance().getSettings().mfcDisabledModelsTableColumns.add(tc.getText());
                    table.getColumns().remove(tc);
                }
            });
        }
        Button src = (Button) evt.getSource();
        Point2D location = src.localToScreen(src.getTranslateX(), src.getTranslateY());
        menu.show(getTabPane().getScene().getWindow(), location.getX(), location.getY() + src.getHeight() + 5);
    }

    private boolean isColumnEnabled(TableColumn<ModelTableRow, ?> tc) {
        return !Config.getInstance().getSettings().mfcDisabledModelsTableColumns.contains(tc.getText());
    }

    private <T> TableColumn<ModelTableRow, T> createTableColumn(String text, int width, int idx) {
        TableColumn<ModelTableRow, T> tc = new TableColumn<>(text);
        tc.setPrefWidth(width);
        tc.sortTypeProperty().addListener((obs, o, n) -> saveState());
        tc.widthProperty().addListener((obs, o, n) -> saveState());
        tc.setUserData(idx);
        columns.add(tc);
        return tc;
    }

    @Override
    public void selected() {
        if(updateService != null) {
            State s = updateService.getState();
            if (s != State.SCHEDULED && s != State.RUNNING) {
                updateService.reset();
                updateService.restart();
            }
        }
    }

    @Override
    public void deselected() {
        if(updateService != null) {
            updateService.cancel();
        }
        saveData();
    }

    private void saveData() {
        try {
            List<ModelTableRow> union = new ArrayList<>();
            union.addAll(filteredModels);
            union.addAll(observableModels);
            JSONArray data = new JSONArray();
            for (ModelTableRow row : union) {
                JSONObject model = new JSONObject();
                model.put("uid", row.uid);
                model.put("blurp", row.blurp.get());
                model.put("camScore", row.camScore.get());
                model.put("continent", row.continent.get());
                model.put("country", row.country.get());
                model.put("ethnic", row.ethnic.get());
                model.put("name", row.name.get());
                model.put("newModel", row.newModel.get());
                model.put("occupation", row.occupation.get());
                model.put("state", row.state.get());
                model.put("tags", row.tags.get());
                model.put("topic", row.topic.get());
                data.put(model);
            }
            File file = new File(Config.getInstance().getConfigDir(), "mfc-models.json");
            Files.write(file.toPath(), data.toString(2).getBytes("utf-8"), CREATE, WRITE);
        } catch (Exception e) {
            LOG.debug("Couldn't write mfc models table data: {}", e.getMessage());
        }
    }

    private void loadData() {
        try {
            File file = new File(Config.getInstance().getConfigDir(), "mfc-models.json");
            if(!file.exists()) {
                return;
            }
            String json = new String(Files.readAllBytes(file.toPath()), "utf-8");
            JSONArray data = new JSONArray(json);
            for (int i = 0; i < data.length(); i++) {
                try {
                    ModelTableRow row = new ModelTableRow();
                    JSONObject model = data.getJSONObject(i);
                    row.uid = model.getInt("uid");
                    row.blurp.set(model.optString("blurp"));
                    row.camScore.set(model.optDouble("camScore"));
                    row.continent.set(model.optString("continent"));
                    row.country.set(model.optString("country"));
                    row.ethnic.set(model.optString("ethnic"));
                    row.name.set(model.optString("name"));
                    row.newModel.set(model.optString("newModel"));
                    row.occupation.set(model.optString("occupation"));
                    row.state.set(model.optString("state"));
                    row.tags.set(model.optString("tags"));
                    row.topic.set(model.optString("topic"));
                    observableModels.add(row);
                } catch (Exception e) {}
            }
        } catch (Exception e) {
            LOG.debug("Couldn't read mfc models table data: {}", e.getMessage());
        }
    }

    private void saveState() {
        if (!table.getSortOrder().isEmpty()) {
            TableColumn<ModelTableRow, ?> col = table.getSortOrder().get(0);
            Config.getInstance().getSettings().mfcModelsTableSortColumn = col.getText();
            Config.getInstance().getSettings().mfcModelsTableSortType = col.getSortType().toString();
        }
        double[] columnWidths = new double[table.getColumns().size()];
        for (int i = 0; i < columnWidths.length; i++) {
            columnWidths[i] = table.getColumns().get(i).getWidth();
        }
        Config.getInstance().getSettings().mfcModelsTableColumnWidths = columnWidths;
    };

    private void restoreState() {
        String sortCol = Config.getInstance().getSettings().mfcModelsTableSortColumn;
        if (StringUtil.isNotBlank(sortCol)) {
            for (TableColumn<ModelTableRow, ?> col : table.getColumns()) {
                if (Objects.equals(sortCol, col.getText())) {
                    col.setSortType(SortType.valueOf(Config.getInstance().getSettings().mfcModelsTableSortType));
                    table.getSortOrder().clear();
                    table.getSortOrder().add(col);
                    break;
                }
            }
        }

        double[] columnWidths = Config.getInstance().getSettings().mfcModelsTableColumnWidths;
        if (columnWidths != null && columnWidths.length == table.getColumns().size()) {
            for (int i = 0; i < columnWidths.length; i++) {
                table.getColumns().get(i).setPrefWidth(columnWidths[i]);
            }
        }

        filterInput.setText(Config.getInstance().getSettings().mfcModelsTableFilter);
    }

    private ListChangeListener<TableColumn<ModelTableRow, ?>> createSortOrderChangedListener() {
        return new ListChangeListener<TableColumn<ModelTableRow, ?>>() {
            @Override
            public void onChanged(Change<? extends TableColumn<ModelTableRow, ?>> c) {
                saveState();
            }
        };
    }

    private static class ModelTableRow {
        private Integer uid;
        private StringProperty name = new SimpleStringProperty();
        private StringProperty state = new SimpleStringProperty();
        private DoubleProperty camScore = new SimpleDoubleProperty();
        private StringProperty newModel = new SimpleStringProperty();
        private StringProperty ethnic = new SimpleStringProperty();
        private StringProperty country = new SimpleStringProperty();
        private StringProperty continent = new SimpleStringProperty();
        private StringProperty occupation = new SimpleStringProperty();
        private StringProperty tags = new SimpleStringProperty();
        private StringProperty blurp = new SimpleStringProperty();
        private StringProperty topic = new SimpleStringProperty();

        public ModelTableRow(SessionState st) {
            update(st);
        }

        private ModelTableRow() {
        }

        public void update(SessionState st) {
            uid = st.getUid();
            setProperty(name, Optional.ofNullable(st.getNm()));
            setProperty(state, Optional.ofNullable(st.getVs()).map(vs -> ctbrec.sites.mfc.State.of(vs).toString()));
            setProperty(camScore, Optional.ofNullable(st.getM()).map(m -> m.getCamscore()));
            Optional<Integer> isNew = Optional.ofNullable(st.getM()).map(m -> m.getNewModel());
            if(isNew.isPresent()) {
                newModel.set(isNew.get() == 1 ? "new" : "");
            }
            setProperty(ethnic, Optional.ofNullable(st.getU()).map(u -> u.getEthnic()));
            setProperty(country, Optional.ofNullable(st.getU()).map(u -> u.getCountry()));
            setProperty(continent, Optional.ofNullable(st.getM()).map(m -> m.getContinent()));
            setProperty(occupation, Optional.ofNullable(st.getU()).map(u -> u.getOccupation()));
            Set<String> tagSet = Optional.ofNullable(st.getM()).map(m -> m.getTags()).orElse(Collections.emptySet());
            if(!tagSet.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (String t : tagSet) {
                    sb.append(t).append(',').append(' ');
                }
                tags.set(sb.substring(0, sb.length()-2));
            }
            setProperty(blurp, Optional.ofNullable(st.getU()).map(u -> u.getBlurb()));
            String tpc = Optional.ofNullable(st.getM()).map(m -> m.getTopic()).orElse("n/a");
            try {
                tpc = URLDecoder.decode(tpc, "utf-8");
            } catch (UnsupportedEncodingException e) {
                LOG.warn("Couldn't url decode topic", e);
            }
            topic.set(tpc);
        }

        private <T> void setProperty(Property<T> prop, Optional<T> value) {
            if(value.isPresent() && !Objects.equals(value.get(), prop.getValue())) {
                prop.setValue(value.get());
            }
        }

        public StringProperty nameProperty() {
            return name;
        };

        public StringProperty stateProperty() {
            return state;
        };

        public DoubleProperty camScoreProperty() {
            return camScore;
        };

        public StringProperty newModelProperty() {
            return newModel;
        };

        public StringProperty ethnicityProperty() {
            return ethnic;
        };

        public StringProperty countryProperty() {
            return country;
        };

        public StringProperty continentProperty() {
            return continent;
        };

        public StringProperty occupationProperty() {
            return occupation;
        };

        public StringProperty tagsProperty() {
            return tags;
        };

        public StringProperty blurpProperty() {
            return blurp;
        };

        public StringProperty topicProperty() {
            return topic;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((uid == null) ? 0 : uid.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            ModelTableRow other = (ModelTableRow) obj;
            if (uid == null) {
                if (other.uid != null)
                    return false;
            } else if (!uid.equals(other.uid))
                return false;
            return true;
        };


    }
}