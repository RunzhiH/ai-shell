/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

module com.runzhi.aishell {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.web;
    requires javafx.swing;
    requires javafx.graphics;
    requires javafx.base;

    requires org.controlsfx.controls;
    requires com.dlsc.formsfx;
    requires net.synedra.validatorfx;
    requires org.kordamp.ikonli.javafx;
    requires org.kordamp.bootstrapfx.core;
    requires eu.hansolo.tilesfx;

    requires java.net.http;
    requires java.prefs;
    requires static lombok;
    requires java.desktop;
    requires java.management;
    requires com.google.gson;
    requires org.slf4j;
    requires java.logging;
    requires org.fxmisc.richtext;
    requires com.jcraft.jsch;
    requires com.techsenger.jeditermfx.core;
    requires com.techsenger.jeditermfx.ui;

    // JSch 是自动模块，通过运行时配置访问

    opens com.runzhi.aishell to javafx.fxml, org.fxmisc.richtext;
    opens com.runzhi.aishell.ai to com.google.gson, org.fxmisc.richtext;
    // SSH 相关包需要对 JSch (未命名模块) 开放
    opens com.runzhi.aishell.ssh;
    opens com.runzhi.aishell.ssh.impl;
    opens com.runzhi.aishell.ssh.factory;
    opens com.runzhi.aishell.ssh.domain to com.google.gson, org.fxmisc.richtext;
    // Gson 反射读写书签/模板的私有字段（toJson/fromJson）
    opens com.runzhi.aishell.features.bookmark to com.google.gson;
    exports com.runzhi.aishell to javafx.graphics, org.fxmisc.richtext;
    exports com.runzhi.aishell.controller to org.fxmisc.richtext, javafx.base;
    // PropertyValueFactory 通过反射读取控制器内嵌类的属性，需要 javafx.base 访问权限
    opens com.runzhi.aishell.controller to javafx.fxml, javafx.base, org.fxmisc.richtext;
    opens com.runzhi.aishell.ui to javafx.fxml;
}
