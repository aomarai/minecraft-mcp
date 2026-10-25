package dev.aomarai.minecraftmcp.core;

import io.modelcontextprotocol.json.McpJsonMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** How an {@link EventPage} is written for clients: the same for the tool and the resource. */
final class EventJson {

    private EventJson() {
    }

    static String of(EventPage page, McpJsonMapper mapper) throws IOException {
        List<Map<String, Object>> events = new ArrayList<>();
        for (GameEvent event : page.events()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sequence", event.sequence());
            item.put("time", event.time().toString());
            item.put("type", event.type().wire());
            item.put("player", event.player());
            item.put("text", event.text());
            item.put("untrusted", event.untrusted());
            events.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("events", events);
        out.put("latest", page.latest());
        out.put("dropped", page.dropped());
        return mapper.writeValueAsString(out);
    }
}
