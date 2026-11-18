package com.tactical.core.web;

import com.tactical.core.api.Dtos.SearchRequest;
import com.tactical.core.api.Dtos.SearchResult;
import com.tactical.core.api.Dtos.TagCount;
import com.tactical.core.service.SearchService;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SearchService search;

    /** Wires the search service. */
    public SearchController(SearchService search) {
        this.search = search;
    }

    /** tags non-empty turns the call into a tag-scoped kNN search, exactly like the UI. */
    @PostMapping
    public SearchResult run(@RequestBody SearchRequest request) {
        if (request.tags() != null && !request.tags().isEmpty()) {
            return search.tagSearch(request.tags(), request.k(), request.cores());
        }
        return search.search(request);
    }

    @PostMapping("/tags")
    /** Tag counts for the named cores, or for every core when none are named. */
    public List<TagCount> tags(@RequestBody(required = false) SearchRequest request) {
        return search.tagCounts(request == null ? null : request.cores());
    }
}
