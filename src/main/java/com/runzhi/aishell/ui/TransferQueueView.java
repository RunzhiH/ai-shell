package com.runzhi.aishell.ui;

import com.runzhi.aishell.common.i18n.I18n;
import com.runzhi.aishell.ssh.TransferQueueStore;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.IntConsumer;

/** One visible queue for persisted jobs. Retry respects the original endpoint and owner. */
public final class TransferQueueView extends VBox implements AutoCloseable {
    private final TransferQueueStore store;
    private final TableView<TransferQueueStore.Job> table=new TableView<>();
    private final Button retry=new Button(I18n.tr("fm.phase.retry"));
    private final Button cancel=new Button(I18n.tr("fm.phase.cancelBatch"));
    private final Button remove=new Button(I18n.tr("fm.phase.remove"));
    private final Label details=new Label();
    private final Predicate<TransferQueueStore.Job> canRetry;
    private final AutoCloseable subscription;
    private final IntConsumer count;
    private boolean closed;
    public TransferQueueView(TransferQueueStore store,Predicate<TransferQueueStore.Job> canRetry,
                             Consumer<TransferQueueStore.Job> retryJob,IntConsumer count) {
        this.store=store; this.canRetry=canRetry; this.count=count;
        setSpacing(6); setMinWidth(0);
        column("fm.phase.direction",job->I18n.tr(job.direction()==TransferQueueStore.Direction.UPLOAD?"fm.upload":"fm.download"));
        column("fm.phase.source",job->source(job));
        column("fm.phase.target",job->destination(job));
        column("fm.phase.progress",job->store.progress(job.id()));
        column("fm.phase.state",job->I18n.tr("fm.phase.state."+job.status().name()));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(I18n.tr("fm.phase.queueEmpty")));
        details.setWrapText(true); details.setMinWidth(0);
        table.getSelectionModel().selectedItemProperty().addListener((_,_,job)->buttons());
        retry.setOnAction(event->{ var job=table.getSelectionModel().getSelectedItem(); if(job!=null && canRetry.test(job)) retryJob.accept(job); });
        cancel.setOnAction(event->{ var job=table.getSelectionModel().getSelectedItem(); if(job!=null) store.cancel(job.id()); });
        remove.setOnAction(event->{ var job=table.getSelectionModel().getSelectedItem(); if(job!=null) store.removeFailed(job.id()); });
        FlowPane actions=new FlowPane(6,6,retry,cancel,remove);
        getChildren().addAll(table,details,actions); VBox.setVgrow(table,Priority.ALWAYS);
        subscription=store.observe(()->Platform.runLater(this::refresh)); refresh();
    }
    private void column(String key,java.util.function.Function<TransferQueueStore.Job,String> text) {
        TableColumn<TransferQueueStore.Job,String> column=new TableColumn<>(I18n.tr(key));
        column.setCellValueFactory(cell->new ReadOnlyStringWrapper(text.apply(cell.getValue()))); column.setPrefWidth(180); table.getColumns().add(column);
    }
    public void refresh() {
        if(closed) return;
        String selected=table.getSelectionModel().getSelectedItem()==null?null:table.getSelectionModel().getSelectedItem().id();
        var snapshot=store.snapshot();
        if(!table.getItems().equals(snapshot)) table.getItems().setAll(snapshot); else table.refresh();
        if(selected!=null) table.getItems().stream().filter(job->job.id().equals(selected)).findFirst().ifPresent(job->table.getSelectionModel().select(job));
        count.accept(table.getItems().size()); buttons();
    }
    private void buttons() {
        var job=table.getSelectionModel().getSelectedItem();
        boolean finished=job!=null && job.status()!=TransferQueueStore.Status.RUNNING && job.status()!=TransferQueueStore.Status.PENDING;
        retry.setDisable(!finished || !canRetry.test(job)); remove.setDisable(!finished);
        cancel.setDisable(job==null || !(job.status()==TransferQueueStore.Status.PENDING || job.status()==TransferQueueStore.Status.RUNNING && store.canCancel(job.id())));
        details.setText(job==null?"":source(job)+"\n→ "+destination(job));
    }
    private String endpoint(TransferQueueStore.Job job) { return job.username()+"@"+job.host()+":"+job.port(); }
    private String source(TransferQueueStore.Job job) {
        return (job.direction()==TransferQueueStore.Direction.UPLOAD?I18n.tr("fm.local.files"):endpoint(job))+" · "+String.join(", ",job.sources());
    }
    private String destination(TransferQueueStore.Job job) {
        return (job.direction()==TransferQueueStore.Direction.DOWNLOAD?I18n.tr("fm.local.files"):endpoint(job))+" · "+job.destination();
    }
    @Override public void close() { closed=true; try { subscription.close(); } catch(Exception ignored) { } }
}
