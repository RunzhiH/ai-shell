package com.runzhi.aishell.controller;

import com.runzhi.aishell.common.i18n.I18n;
import com.runzhi.aishell.ui.CommandPalette;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Palette actions must use explicit entry points and only run on intentional input. */
class CommandPaletteBehaviorTest {
    private Locale original;
    private final List<Stage> windows=new ArrayList<>();
    private final List<CommandPalette> palettes=new ArrayList<>();
    private static class RoutingMain extends MainViewController {
        String opened;
        @Override public void initialize(URL location,ResourceBundle resources) { }
        @Override public void openConfig() { opened="config"; }
        @Override public void openSettings() { opened="settings"; }
        @Override public void openAgentWorkspace() { opened="agent"; }
        @Override public void openAIAssistant() { opened="chat"; }
    }
    @BeforeAll static void startFx() throws Exception {
        CountDownLatch ready=new CountDownLatch(1);
        try { Platform.startup(ready::countDown); } catch(IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(10,TimeUnit.SECONDS));
        Platform.runLater(() -> Platform.setImplicitExit(false));
    }
    private void fx(Runnable action) throws Exception {
        CountDownLatch done=new CountDownLatch(1); AtomicReference<Throwable> error=new AtomicReference<>();
        Platform.runLater(() -> { try { action.run(); } catch(Throwable failure) { error.set(failure); } finally { done.countDown(); } });
        assertTrue(done.await(30,TimeUnit.SECONDS)); if(error.get()!=null) throw new AssertionError(error.get());
    }
    @BeforeEach void language() throws Exception { original=I18n.current(); fx(()->I18n.setLocale(Locale.ENGLISH,false)); }
    @AfterEach void close() throws Exception { fx(()-> { palettes.forEach(CommandPalette::hide); windows.forEach(Stage::hide); I18n.setLocale(original,false); }); }
    private Object field(Object owner,Class<?> type,String name) {
        try { Field field=type.getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
        catch(Exception failure) { throw new AssertionError(failure); }
    }
    @SuppressWarnings("unchecked") private List<CommandPalette.Item> catalog(RoutingMain main) {
        try { Method method=MainViewController.class.getDeclaredMethod("buildPaletteItems"); method.setAccessible(true); return (List<CommandPalette.Item>)method.invoke(main); }
        catch(Exception failure) { throw new AssertionError(failure); }
    }
    private Stage show(Parent root,int width) {
        Stage stage=new Stage(); windows.add(stage);
        Scene scene=new Scene(root,width,600);
        scene.getStylesheets().add(getClass().getResource("/com/runzhi/aishell/light-theme.css").toExternalForm());
        var screen=Screen.getPrimary().getVisualBounds(); stage.setX(screen.getMinX()+20); stage.setY(screen.getMinY()+20);
        stage.setScene(scene); stage.show(); root.applyCss(); root.layout(); return stage;
    }
    private CommandPalette palette(List<CommandPalette.Item> items) { CommandPalette palette=new CommandPalette(items); palettes.add(palette); return palette; }
    private void layout(CommandPalette palette) { palette.getScene().getRoot().applyCss(); palette.getScene().getRoot().layout(); }
    private void key(Node target,KeyCode key) { target.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED,"","",key,false,false,false,false)); }
    private void doubleClick(Node target,MouseButton button) {
        target.fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED,5,5,5,5,button,2,false,false,false,false,false,false,false,false,false,true,null));
    }
    @Test void catalogKeepsConfigurationSettingsAgentAndChatEntryPointsDistinct() throws Exception {
        fx(()-> {
            RoutingMain main=new RoutingMain(); var items=catalog(main);
            for(String[] route:new String[][]{{"main.openConfig","config"},{"main.settings","settings"},{"workbench.agent","agent"},{"workbench.aiAssistant","chat"}}) {
                items.stream().filter(item->item.title().equals(I18n.tr(route[0]))).findFirst().orElseThrow().action().run();
                assertEquals(route[1],main.opened);
            }
        });
    }
    @Test void detachedUnownedAndHiddenAnchorsDoNotOpenPopup() throws Exception {
        fx(()-> {
            CommandPalette palette=palette(List.of()); Button anchor=new Button("Commands");
            palette.showBelow(anchor); assertFalse(palette.isShowing());
            VBox root=new VBox(anchor); new Scene(root); palette.showBelow(anchor); assertFalse(palette.isShowing());
            Stage hidden=new Stage(); windows.add(hidden); hidden.setScene(root.getScene());
            palette.showBelow(anchor); assertFalse(palette.isShowing());
        });
    }
    @Test void mainUsesCommandButtonAndPopupTracksOwnerThemeWithinScreen() throws Exception {
        fx(()-> { try {
            FXMLLoader loader=new FXMLLoader(getClass().getResource("/com/runzhi/aishell/main-view.fxml"));
            loader.setResources(I18n.bundle()); loader.setControllerFactory(type->new RoutingMain()); Parent root=loader.load();
            RoutingMain main=loader.getController(); Stage owner=show(root,1280);
            main.openCommandPalette();
            CommandPalette palette=(CommandPalette)field(main,MainViewController.class,"commandPalette"); palettes.add(palette); layout(palette);
            assertTrue(palette.isShowing()); assertSame(owner,palette.getOwnerWindow());
            assertSame(loader.getNamespace().get("btnCommandPalette"),field(palette,CommandPalette.class,"returnFocusAnchor"));
            TextField input=(TextField)field(palette,CommandPalette.class,"field");
            assertEquals(Color.WHITE,input.getBackground().getFills().getFirst().getFill());
            var screen=Screen.getScreensForRectangle(palette.getX(),palette.getY(),palette.getWidth(),palette.getHeight()).getFirst().getVisualBounds();
            assertTrue(palette.getX()>=screen.getMinX()-1 && palette.getX()+palette.getWidth()<=screen.getMaxX()+1);
            assertTrue(palette.getY()>=screen.getMinY()-1 && palette.getY()+palette.getHeight()<=screen.getMaxY()+1);
            palette.hide(); owner.getScene().getStylesheets().setAll(getClass().getResource("/com/runzhi/aishell/style.css").toExternalForm());
            main.openCommandPalette(); layout(palette);
            assertEquals(Color.web("#2D2D2D"),input.getBackground().getFills().getFirst().getFill());
        } catch(Exception failure) { throw new AssertionError(failure); } });
    }
    @Test @SuppressWarnings("unchecked") void groupingEmptyResultsKeyboardAndFreshQueryAreConsistent() throws Exception {
        fx(()-> {
            AtomicInteger actions=new AtomicInteger(); Button anchor=new Button("Commands"); show(new VBox(anchor),500);
            CommandPalette palette=palette(List.of(new CommandPalette.Item("Beta","AI",actions::incrementAndGet),new CommandPalette.Item("Alpha","Operations",actions::incrementAndGet)));
            palette.showBelow(anchor); layout(palette);
            TextField input=(TextField)field(palette,CommandPalette.class,"field");
            ListView<CommandPalette.Item> list=(ListView<CommandPalette.Item>)field(palette,CommandPalette.class,"list");
            input.setText("AI Beta"); assertEquals(1,list.getItems().size());
            key(input,KeyCode.ENTER); assertEquals(1,actions.get()); assertFalse(palette.isShowing());
            palette.showBelow(anchor); assertEquals("",input.getText()); assertEquals(2,list.getItems().size());
            input.setText("no such command"); assertTrue(list.getItems().isEmpty());
            assertEquals("No matching commands",((Label)list.getPlaceholder()).getText());
            key(input,KeyCode.ENTER); assertEquals(1,actions.get()); assertTrue(palette.isShowing());
            key(input,KeyCode.ESCAPE); assertFalse(palette.isShowing());
        });
    }
    @Test @SuppressWarnings("unchecked") void blankAreaAndRightDoubleClickCannotRunSelectedCommand() throws Exception {
        fx(()-> {
            AtomicInteger actions=new AtomicInteger(); Button anchor=new Button("Commands"); show(new VBox(anchor),500);
            CommandPalette palette=palette(List.of(new CommandPalette.Item("Alpha","Operations",actions::incrementAndGet)));
            palette.showBelow(anchor); layout(palette);
            ListView<CommandPalette.Item> list=(ListView<CommandPalette.Item>)field(palette,CommandPalette.class,"list");
            doubleClick(list,MouseButton.PRIMARY); assertEquals(0,actions.get()); assertTrue(palette.isShowing());
            ListCell<?> cell=(ListCell<?>)list.lookupAll(".list-cell").stream().filter(node->node instanceof ListCell<?> row && !row.isEmpty()).findFirst().orElseThrow();
            doubleClick(cell,MouseButton.SECONDARY); assertEquals(0,actions.get());
            doubleClick(cell,MouseButton.PRIMARY); assertEquals(1,actions.get()); assertFalse(palette.isShowing());
        });
    }
}
