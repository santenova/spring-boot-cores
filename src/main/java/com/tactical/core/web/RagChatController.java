package com.tactical.core.web;

import com.tactical.core.api.Dtos.RagChatRequest;
import com.tactical.core.api.Dtos.RagChatResult;
import com.tactical.core.service.RagChatService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rag/chat")
public class RagChatController {

    private final RagChatService ragChat;

    /** Wires the search-then-synthesise service. */
    public RagChatController(RagChatService ragChat) {
        this.ragChat = ragChat;
    }

    /** One call for the whole turn: search, resolve the originals, answer with citations. */
    @PostMapping
    public RagChatResult chat(@RequestBody RagChatRequest request) {
        return ragChat.chat(request);
    }
}
