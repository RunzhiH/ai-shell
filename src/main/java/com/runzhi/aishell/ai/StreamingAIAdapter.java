/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.ai;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Adapter capable of delivering provider-native response chunks. */
public interface StreamingAIAdapter extends AIAdapter {
    void streamQuery(String query, Consumer<String> onDelta, BooleanSupplier cancelled) throws Exception;
}
