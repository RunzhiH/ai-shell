package com.runzhi.aishell.controller;

import org.junit.jupiter.api.Test;
import org.w3c.dom.*;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class FxmlContractTest {
    @Test
    void allFxmlControllersAndEventHandlersExist() throws Exception {
        Path root = Path.of("src", "main", "resources");
        List<String> errors = new ArrayList<>();
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".fxml")).toList()) {
                validate(file, errors);
            }
        }
        assertTrue(errors.isEmpty(), String.join("\n", errors));
    }

    private void validate(Path file, List<String> errors) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document document = factory.newDocumentBuilder().parse(file.toFile());
            String controllerName = document.getDocumentElement().getAttribute("fx:controller");
            if (controllerName.isBlank()) return;
            Class<?> controller = Class.forName(controllerName);
            walk(file, document.getDocumentElement(), controller, errors);
        } catch (Exception e) {
            errors.add(file + ": " + e.getMessage());
        }
    }

    private void walk(Path file, Element element, Class<?> controller, List<String> errors) {
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            String value = attribute.getNodeValue();
            if (attribute.getNodeName().startsWith("on") && value.length() > 1 && value.startsWith("#")
                    && !hasMethod(controller, value.substring(1))) {
                errors.add(file + ": missing " + controller.getName() + "#" + value.substring(1));
            }
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child) walk(file, child, controller, errors);
        }
    }

    private boolean hasMethod(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (Arrays.stream(current.getDeclaredMethods()).anyMatch(method -> method.getName().equals(name))) return true;
        }
        return false;
    }
}
