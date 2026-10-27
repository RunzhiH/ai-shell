package com.runzhi.aishell.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentActivityBusTest {
    @Test
    void subscriptionReceivesEventsAndCanBeClosed() throws Exception {
        List<AgentActivityBus.Event> received = new ArrayList<>();
        AutoCloseable subscription = AgentActivityBus.subscribe(received::add);

        AgentActivityBus.publish("tab-1", AgentActivityBus.Kind.TOOL, "检查系统");
        subscription.close();
        AgentActivityBus.publish("tab-1", AgentActivityBus.Kind.SUCCESS, "不应收到");

        assertEquals(1, received.size());
        assertEquals("tab-1", received.getFirst().sessionId());
        assertEquals("检查系统", received.getFirst().text());
    }
}
