/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.terminal;

import com.techsenger.jeditermfx.ui.TerminalActionPresentation;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 终端复制/粘贴快捷键回归：主快捷键不得依赖 Shift。 */
class InteractiveTerminalViewShortcutTest {

    @Test
    void copyAndPasteUseUnshiftedPrimaryCombination() {
        InteractiveTerminalView.DarkTerminalSettings settings = new InteractiveTerminalView.DarkTerminalSettings();

        assertUnshifted(settings.getCopyActionPresentation(), KeyCode.C);
        assertUnshifted(settings.getPasteActionPresentation(), KeyCode.V);
    }

    @Test
    void shiftCombinationsRemainCompatibilityAliases() {
        if (isMacOS()) return;
        InteractiveTerminalView.DarkTerminalSettings settings = new InteractiveTerminalView.DarkTerminalSettings();

        assertTrue(settings.getCopyActionPresentation().getKeyCombinations().contains(
                new KeyCodeCombination(KeyCode.C, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN)));
        assertTrue(settings.getPasteActionPresentation().getKeyCombinations().contains(
                new KeyCodeCombination(KeyCode.V, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN)));
    }

    private static void assertUnshifted(TerminalActionPresentation presentation, KeyCode expectedCode) {
        List<KeyCombination> combinations = presentation.getKeyCombinations();
        assertFalse(combinations.isEmpty(), "missing key binding for " + presentation.getName());
        KeyCodeCombination primary = (KeyCodeCombination) combinations.get(0);
        assertEquals(expectedCode, primary.getCode());
        KeyCodeCombination expected = isMacOS()
                ? new KeyCodeCombination(expectedCode, KeyCombination.META_DOWN)
                : new KeyCodeCombination(expectedCode, KeyCombination.CONTROL_DOWN);
        assertEquals(expected, primary, presentation.getName() + " must not require Shift");
    }

    private static boolean isMacOS() {
        return com.techsenger.jeditermfx.core.util.Platform.isMacOS();
    }
}
