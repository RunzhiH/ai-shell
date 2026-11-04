package com.runzhi.aishell.ui;

import javafx.application.Platform;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Lazy directory-only navigation. Expansion reads one level; context epochs reject stale responses. */
public final class DirectoryTree extends TreeView<String> implements AutoCloseable {
    private static final class Directory extends TreeItem<String> {
        boolean loaded,loading; int sequence;
        Directory(String path) { super(path); getChildren().add(new TreeItem<>()); }
    }
    private final Executor executor;
    private final Supplier<Function<String,List<String>>> readers;
    private final Consumer<String> navigate;
    private final Map<String,TreeItem<String>> contexts=new HashMap<>();
    private long epoch;
    private boolean selecting,closed;
    public DirectoryTree(Executor executor,Supplier<Function<String,List<String>>> readers,Consumer<String> navigate) {
        this.executor=executor; this.readers=readers; this.navigate=navigate;
        setCellFactory(tree->new javafx.scene.control.TreeCell<>() {
            @Override protected void updateItem(String path,boolean empty) {
                super.updateItem(path,empty);
                if(empty || path==null) { setText(null); setTooltip(null); return; }
                String normalized=path.replace('\\','/');
                if(normalized.endsWith("/") && normalized.length()>1) normalized=normalized.substring(0,normalized.length()-1);
                int slash=normalized.lastIndexOf('/');
                setText(slash>=0 && slash<normalized.length()-1?normalized.substring(slash+1):normalized);
                setTooltip(new javafx.scene.control.Tooltip(path));
            }
        });
        setShowRoot(false); setMinWidth(100); setPrefWidth(170); getStyleClass().add("directory-tree");
        getSelectionModel().selectedItemProperty().addListener((_,_,item)->{
            if(!selecting && item instanceof Directory && item.getValue()!=null) navigate.accept(item.getValue());
        });
    }
    /** Keep each session's expansion state without sharing in-flight results between sessions. */
    public void useContext(String key,List<String> roots) {
        epoch++; closed=false; selecting=true;
        try {
            TreeItem<String> root=contexts.computeIfAbsent(key,ignored->{
                TreeItem<String> result=new TreeItem<>("");
                result.addEventHandler(TreeItem.<String>branchExpandedEvent(),event->{
                    if(event.getTreeItem() instanceof Directory directory) expand(directory);
                });
                roots.forEach(path->result.getChildren().add(new Directory(path))); return result;
            });
            resetLoading(root); setRoot(root);
        } finally { selecting=false; }
    }
    private void resetLoading(TreeItem<String> node) {
        if(node instanceof Directory directory) directory.loading=false;
        node.getChildren().forEach(this::resetLoading);
    }
    private void expand(Directory directory) {
        if(closed || directory.loaded || directory.loading) return;
        directory.loading=true; long request=epoch; int sequence=++directory.sequence;
        Function<String,List<String>> read=readers.get();
        executor.execute(()->{
            try {
                List<String> paths=read.apply(directory.getValue());
                Platform.runLater(()->{
                    if(closed || request!=epoch || sequence!=directory.sequence) return;
                    Map<String,TreeItem<String>> old=new HashMap<>();
                    directory.getChildren().forEach(child->old.put(child.getValue(),child));
                    List<TreeItem<String>> children=paths.stream().distinct().sorted(String.CASE_INSENSITIVE_ORDER)
                            .map(path->old.getOrDefault(path,new Directory(path))).toList();
                    selecting=true;
                    try { directory.getChildren().setAll(children); directory.loaded=true; directory.loading=false; }
                    finally { selecting=false; }
                });
            } catch(RuntimeException failure) {
                Platform.runLater(()->{ if(request==epoch && !closed) directory.loading=false; });
            }
        });
    }
    /** Select a successful destination; never trigger another navigation from synchronization. */
    public void reveal(String path) {
        if(path==null || getRoot()==null) return;
        selecting=true;
        try {
            TreeItem<String> found=find(getRoot(),path);
            if(found==null) { found=new Directory(path); getRoot().getChildren().add(found); }
            getSelectionModel().select(found);
            if(found instanceof Directory directory) { directory.loaded=false; directory.loading=false; }
            found.setExpanded(true);
            if(found instanceof Directory directory) expand(directory);
        } finally { selecting=false; }
    }
    private TreeItem<String> find(TreeItem<String> node,String path) {
        if(path.equals(node.getValue())) return node;
        for(TreeItem<String> child:node.getChildren()) { TreeItem<String> found=find(child,path); if(found!=null) return found; }
        return null;
    }
    @Override public void close() { closed=true; epoch++; }
}
