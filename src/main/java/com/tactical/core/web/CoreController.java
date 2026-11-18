package com.tactical.core.web;

import com.tactical.core.api.Dtos.CoreInfo;
import com.tactical.core.service.EmbeddingService;
import com.tactical.core.service.IndexService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cores")
public class CoreController {

    private final IndexService index;
    private final EmbeddingService embeddings;

    /** Wires the core metadata and the embedding model used to size the vector field. */
    public CoreController(IndexService index, EmbeddingService embeddings) {
        this.index = index;
        this.embeddings = embeddings;
    }

    @GetMapping
    /** Every knowledge core with its document count and vector readiness. */
    public List<CoreInfo> list() {
        return index.listCores();
    }

    @GetMapping("/{name}/mapping")
    /** The vector field of one core, or 404 when it has none. */
    public ResponseEntity<Map<String, Object>> mapping(@PathVariable String name) {
        IndexService.VectorField field = index.readEmbeddingField(index.resolve(name));
        Map<String, Object> body = new LinkedHashMap<>();
        if (field == null) {
            body.put("error", "No dense_vector field on this core");
            return ResponseEntity.status(404).body(body);
        }
        body.put("type", field.type());
        body.put("dims", field.dims());
        return ResponseEntity.ok(body);
    }

    /** Adds the vector field to an existing core, then it has to be re-ingested to fill it. */
    @PostMapping("/{name}/mapping")
    public Map<String, Object> repair(@PathVariable String name) {
        String indexName = index.resolve(name);
        IndexService.EnsureResult result = index.ensureEmbeddingField(indexName, embeddings.dims());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("index", indexName);
        body.put("ok", result.ok());
        body.put("mode", result.mode());
        body.put("dims", result.dims());
        body.put("error", result.error());
        return body;
    }

    @DeleteMapping("/{name}")
    /** Deletes a core index outright and confirms which one went. */
    public Map<String, Object> delete(@PathVariable String name) {
        String indexName = index.resolve(name);
        index.deleteCore(indexName);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deleted", indexName);
        return body;
    }
}
