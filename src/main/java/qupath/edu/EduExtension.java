package qupath.edu;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.text.Font;
import org.controlsfx.control.PopOver;
import org.controlsfx.control.action.Action;
import org.controlsfx.glyphfont.FontAwesome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.edu.api.EduAPI;
import qupath.edu.gui.Browser;
import qupath.edu.gui.buttons.IconButtons;
import qupath.edu.gui.dialogs.*;
import qupath.edu.tours.SlideTour;
import qupath.edu.util.EditModeManager;
import qupath.edu.util.ReflectionUtil;
import qupath.lib.common.Version;
import qupath.lib.gui.ActionTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.extensions.GitHubProject;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.panes.PreferencePane;
import qupath.lib.gui.panes.ProjectBrowser;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.tools.PaneTools;
import qupath.lib.gui.viewer.QuPathViewerPlus;
import qupath.lib.gui.viewer.tools.PathTools;
import qupath.lib.projects.Project;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;

import static qupath.lib.gui.ActionTools.createAction;
import static qupath.lib.gui.ActionTools.createMenuItem;

/**
 * TODO:
 *  - ArrowTool and its respective ROI
 *  - Tons of minor changes
 *  - Figure out why "Save as" syncs changes but not "Save"
 *
 */
public class EduExtension implements QuPathExtension, GitHubProject {

    private static final Logger logger = LoggerFactory.getLogger(EduExtension.class);

    private QuPathGUI qupath;

    private static final EditModeManager editModeManager = new EditModeManager();
    private static final SimpleBooleanProperty noWriteAccess = new SimpleBooleanProperty(true);
    private static final Browser projectInformation = new Browser();
    private final TabPane tabbedPanel = new TabPane();

    @Override
    public void installExtension(QuPathGUI qupath) {
        this.qupath = qupath;

        if (QuPathGUI.getVersion().getMinor() < 3) {
            var confirm = Dialogs.showYesNoDialog(
                "Unverified version",
                "QuPath Edu has not been tested with this version of QuPath. Do you wish to proceed?"
            );

            if (!confirm) {
                return;
            }
        }

        initializePreferences();
        notifyWhenEnablingOrDisablingExtension();

        if (!EduOptions.extensionEnabled().get()) {
            return;
        }

        initializeMenus();

        replaceAnnotationsPane();
        replaceViewer();
        replaceProjectBrowserButtons();
        registerSlideTours();

        onProjectChange();
        onSlideChange();
        onImageDataChange();
        onEditModeToggled();

        disableButtons();

        // Run on a separate thread, because extension initialization happens in QuPath's
        // main thread and our dialogs can interfere with its' initialization.
        Platform.runLater(() -> {
            // Perform first time setup if host is undefined.
            if (EduOptions.host().isNull().get()) {
                FirstTimeSetup.showDialog();
            }

            EduAPI.setHost(EduOptions.host().get());
            EduOptions.host().addListener(((obs, oldHost, newHost) -> EduAPI.setHost(newHost)));

            if (EduOptions.showLoginDialogOnStartup().get()) {
                showWorkspaceOrLoginDialog();
            }
        });
    }

    private void onEditModeToggled() {
        editModeManager.editModeEnabledProperty().addListener((observable, oldValue, enabled) -> {
            if (enabled) {
                PathPrefs.imageTypeSettingProperty().set(PathPrefs.ImageTypeSetting.PROMPT);
            } else {
                PathPrefs.imageTypeSettingProperty().set(PathPrefs.ImageTypeSetting.NONE);
                qupath.setSelectedTool(PathTools.MOVE);
                qupath.getViewer().setActiveTool(PathTools.MOVE);
            }

            qupath.setToolSwitchingEnabled(enabled);
        });

        // Toggle edit mode so that tools get disabled / enabled
        editModeManager.editModeEnabledProperty().set(!editModeManager.editModeEnabledProperty().get());
        editModeManager.editModeEnabledProperty().set(!editModeManager.editModeEnabledProperty().get());
    }

    private void onSlideChange() {
        qupath.imageDataProperty().addListener((obs, o, n) -> {
            ReflectionUtil.getAnalysisPanel().getSelectionModel().select(0);
            tabbedPanel.getSelectionModel().select(1);
        });
    }

    private void onProjectChange() {
        qupath.projectProperty().addListener((obs, oldProject, newProject) -> {
            if (newProject == null) {
                projectInformation.setContent("No lesson open");
            } else if (newProject instanceof EduProject project) {
                String projectInformation = project.getProjectInformation();

                if (projectInformation == null) {
                    EduExtension.projectInformation.setContent("No information available for this lesson");
                } else {
                    EduExtension.projectInformation.setContent(projectInformation);
                }
            } else {
                projectInformation.setContent("No information available for this lesson");
            }
        });
    }

    private void onImageDataChange() {
        qupath.imageDataProperty().addListener((observable, oldValue, imageData) -> {
            if (imageData == null || editModeManager.isEditModeDisabled()) {
                return;
            }

            logger.debug("Switching slides with edit mode enabled -- creating a backup");
            editModeManager.backupImageData(imageData);
        });
    }

    @Override
    public String getName() {
        return "QuPath Education";
    }

    @Override
    public String getDescription() {
        return "Use QuPath for studying!";
    }

    @Override
    public GitHubRepo getRepository() {
        return GitHubRepo.create("QuPath Edu Extension", "openmicroanatomy", "qupath-extension");
    }

    @Override
    public Version getQuPathVersion() {
        return Version.parse("0.3.2");
    }

    @Override
    public Version getVersion() {
        return Version.parse("1.0.0");
    }

    public static EditModeManager getEditModeManager() {
        return editModeManager;
    }

    private void initializeMenus() {
        Action action = createAction(ProjectDescriptionEditorCommand::openDescriptionEditor, "Edit lesson information");
        action.disabledProperty().bind(editModeManager.editModeEnabledProperty().not());

        qupath.getMenu("File>Project...", false).getItems().add(7,
            createMenuItem(action)
        );

        qupath.getMenu("QuPath Edu", true).getItems().addAll(
            createMenuItem(createAction(ExternalSlideManager::showExternalSlideManager, "Manage slides")),
            createMenuItem(createAction(BackupManager::showBackupManagerPane, "Manage backups")),
            createMenuItem(createAction(RemoteUserManager::showManagementDialog, "Manage users")),
            createMenuItem(createAction(OrganizationManager::showOrganizationManager, "Manage organizations")),
            createMenuItem(createAction(WorkspacePermissionManager::showDialog, "Manage permissions")),
            createMenuItem(createAction(EduExtension::showWorkspaceOrLoginDialog, "Show workspaces")),
            createMenuItem(createAction(this::checkSaveChanges, "Sync changes"))
        );
    }

    private void initializePreferences() {
        PreferencePane prefs = QuPathGUI.getInstance().getPreferencePane();

        prefs.addPropertyPreference(EduOptions.extensionEnabled(), Boolean.class,
            "Extension Enabled",
            "Edu",
            "Restart needed for changes to take effect");

        prefs.addPropertyPreference(EduOptions.host(), String.class,
            "Edu Host",
            "Edu",
            "Server used with QuPath Education");

        prefs.addPropertyPreference(EduOptions.showLoginDialogOnStartup(), Boolean.class,
            "Show login dialog on startup",
            "Edu",
            "If enabled, opens the login dialog on startup.");

        prefs.addPropertyPreference(EduOptions.checkForUpdatesOnStartup(), Boolean.class,
            "Check for updates on startup",
            "Edu",
            "If enabled, checks for updates on startup.");

        // Hide annotation names in viewer by default for visual reasons
        qupath.getOverlayOptions().showNamesProperty().set(false);
    }

    public static void setWriteAccess(boolean hasWriteAccess) {
        noWriteAccess.set(!hasWriteAccess);
    }

    public static void setProjectInformation(String information) {
        projectInformation.setContent(information);
    }

    private void replaceAnnotationsPane() {
        SimpleAnnotationPane simpleAnnotationPane = new SimpleAnnotationPane(qupath);

        /* Checkbox */

        CheckBox cbUseAdvancedMenu = new CheckBox("Use Advanced mode");
        cbUseAdvancedMenu.setPadding(new Insets(5));
        cbUseAdvancedMenu.setContentDisplay(ContentDisplay.RIGHT);
        cbUseAdvancedMenu.setFont(Font.font(10));
        cbUseAdvancedMenu.setTooltip(new Tooltip("Advanced mode is needed when using QuPath for analysis."));

        /* Annotation Panes */

        Node advancedPane = ReflectionUtil.getAnalysisPanel().getTabs().get(2).getContent();
        Node simplePane   = simpleAnnotationPane.getPane();

        BorderPane pane = new BorderPane(simplePane);
        pane.setTop(cbUseAdvancedMenu);

        ReflectionUtil.getAnalysisPanel().getTabs().get(2).setContent(pane);

        cbUseAdvancedMenu.setOnAction(e -> {
            if (cbUseAdvancedMenu.isSelected()) {
                pane.setCenter(advancedPane);
            } else {
                pane.setCenter(simplePane);
            }
        });
    }

    private void replaceViewer() {
        projectInformation.setOnMouseClicked(event -> {
            Project<BufferedImage> project = qupath.getProject();

            if (event.getClickCount() > 1 && project instanceof EduProject) {
                String projectId = ((EduProject) project).getId();

                if (EduExtension.getEditModeManager().isEditModeEnabled() && EduAPI.hasWritePermission(projectId)) {
                    ProjectDescriptionEditorCommand.openDescriptionEditor();
                }
            }
        });

        tabbedPanel.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabbedPanel.getTabs().add(new Tab("Lesson Information", projectInformation));
        tabbedPanel.getTabs().add(new Tab("Viewer", ReflectionUtil.getMainViewerPane()));

        ReflectionUtil.setMainViewerPane(tabbedPanel);

        // Refreshes the pane and makes the tabs visible
        ReflectionUtil.setAnalysisPaneVisible(false);
        ReflectionUtil.setAnalysisPaneVisible(true);
    }

    private void replaceProjectBrowserButtons() {
        ProjectBrowser projectBrowser = ReflectionUtil.getProjectBrowser();

        Button btnCreate = ActionTools.createButton(
            ActionTools.createAction(EduExtension::showWorkspaceOrLoginDialog, "Create lesson"), false
        );
        btnCreate.disableProperty().bind(editModeManager.editModeEnabledProperty().not());

        Button btnOpen = ActionTools.createButton(
            ActionTools.createAction(EduExtension::showWorkspaceOrLoginDialog, "Open lesson"), false
        );

        Button btnAdd = ActionTools.createButton(
            ActionTools.createAction(ExternalSlideManager::showExternalSlideManager, "Add images"), false
        );
        btnAdd.disableProperty().bind(editModeManager.editModeEnabledProperty().not().or(qupath.projectProperty().isNull()));

        GridPane paneButtons = PaneTools.createColumnGridControls(btnCreate, btnOpen, btnAdd);
        paneButtons.prefWidthProperty().bind(projectBrowser.getPane().widthProperty());
        paneButtons.setPadding(new Insets(5, 5, 5, 5));
        ((BorderPane) projectBrowser.getPane()).setTop(paneButtons);
    }

    private void registerSlideTours() {
        QuPathViewerPlus viewer = qupath.getViewer();

        SlideTour slideTour = new SlideTour(viewer);
        Node slideTourNode = slideTour.getNode();

        ReflectionUtil.getViewerBasePane(viewer).getChildren().add(slideTour.getNode());

        AnchorPane.setTopAnchor(slideTourNode, 10d);
        AnchorPane.setLeftAnchor(slideTourNode, 10d);

        qupath.getViewer().addViewerListener(new SlideTour(qupath.getViewer()));
    }

    private void checkSaveChanges() {
        if (qupath.getProject() instanceof EduProject) {
            try {
                qupath.getProject().syncChanges();
            } catch (IOException e) {
                Dialogs.showErrorMessage("Sync error", "Error while syncing project");
            }
        }
    }

    /**
     * Notify and prompt the user to exit QuPath when enabling or disabling the extension.
     */
    private void notifyWhenEnablingOrDisablingExtension() {
        EduOptions.extensionEnabled().addListener((obs, o, isEnabled) -> {
            var exit = Dialogs.showYesNoDialog(
                "Restart required",
                "QuPath must be restarted after " + (isEnabled ? "enabling" : "disabling") + " the Edu Extension." +
                "\n\n" +
                "Exit QuPath now?"
            );

            if (exit) {
                Platform.exit();
            }
        });
    }

    /**
     * Disable various buttons based on users write access.
     *
     * TODO: Add support when user is not connected to any server
     */
    private void disableButtons() {
        String[] actionsToDisable = { "Create project", "Add images", "Edit project metadata",
                "Check project URIs", "Import images from v.0.1.2" };

        for (String text : actionsToDisable) {
            Action action = qupath.lookupActionByText(text);

            if (action != null) {
                action.disabledProperty().bind(editModeManager.editModeEnabledProperty().not());
            }
        }

        List<String> menuItemsToDisable = List.of("Remove image(s)", "Delete image(s)", "Rename image", "Refresh thumbnail",
                "Edit description", "Add metadata", "Duplicate image(s)");

        TreeView<Object> tree = ReflectionUtil.getProjectBrowserTree();
        List<MenuItem> items = tree.getContextMenu().getItems();

        for (MenuItem item : items) {
            if (item == null || item.getText() == null) {
                continue;
            }

            if (menuItemsToDisable.contains(item.getText())) {
                item.disableProperty().bind(editModeManager.editModeEnabledProperty().not());
            }
        }

        Button btnToggleEditMode = new Button();
        btnToggleEditMode.textProperty().bind(Bindings.when(editModeManager.editModeEnabledProperty()).then("Turn editing off").otherwise("Turn editing on"));
        btnToggleEditMode.disableProperty().bind(EduAPI.connectedToServerProperty().not());
        btnToggleEditMode.setOnAction(a -> editModeManager.toggleEditMode());
        btnToggleEditMode.setFont(Font.font(10));

        PopOver infoPopOver = new PopOver(EditModeInfoPopOverDialog.getPane());
        infoPopOver.setDetachable(false);
        infoPopOver.setArrowLocation(PopOver.ArrowLocation.TOP_CENTER);

        Button btnEditModeInfo = IconButtons.createIconButton(FontAwesome.Glyph.QUESTION, 10);
        btnEditModeInfo.setFont(Font.font(10));
        btnEditModeInfo.setOnAction(a -> infoPopOver.show(btnEditModeInfo));

        qupath.getToolBar().getItems().addAll(new Separator(), btnToggleEditMode, btnEditModeInfo);
    }

    public static void showWorkspaceOrLoginDialog() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(EduExtension::showWorkspaceOrLoginDialog);
            return;
        }

        try {
            if (EduAPI.getAuthType() == EduAPI.AuthType.UNAUTHENTICATED) {
                RemoteServerLoginManager.showLoginDialog();
            } else {
                WorkspaceManager.showWorkspace(QuPathGUI.getInstance());
            }
        } catch (Exception e) {
            logger.error("Error when connecting to server", e);

            String[] choices = { "Cancel", "Change server", "Retry connection" };

            var dialog = Dialogs.builder()
                .title("Error when connecting to " + EduAPI.getHost())
                .contentText("Please check your internet connection and that you're connecting to the correct server.")
                .buttons(choices)
                .build();

            var response = dialog.showAndWait();

            EduAPI.logout();

            if (response.isPresent()) {
                var text = response.orElse(ButtonType.CLOSE).getText();

                if (text.equals(choices[1])) {
                    FirstTimeSetup.showDialog();
                    showWorkspaceOrLoginDialog();
                } else if (text.equals(choices[2])) {
                    showWorkspaceOrLoginDialog();
                }
            }
        }
    }
}
