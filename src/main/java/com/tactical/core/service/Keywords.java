package com.tactical.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Keyword extraction and term cleaning, shared by ingestion and search. */
public final class Keywords {

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "with", "that", "this", "from", "have", "has", "had", "was", "were",
            "are", "is", "but", "not", "you", "your", "our", "its", "their", "they", "them", "there",
            "here", "when", "what", "which", "who", "how", "why", "all", "any", "can", "will", "would",
            "should", "could", "about", "into", "than", "then", "also", "such", "more", "most", "some",
            "only", "over", "under", "very", "just", "like", "been", "being", "does", "did", "doing",
            "each", "other", "these", "those", "use", "used", "using", "via", "one", "two", "new",
            "get", "gets", "out", "off", "per", "may", "might", "must", "itself", "however");

    /** Utility holder: never instantiated. */
    private Keywords() {
    }

    /** The most frequent meaningful words, ties going to the longer word. */
    public static List<String> top(String text, int limit) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (word.length() < 3 || STOP_WORDS.contains(word)) {
                continue;
            }
            counts.merge(word, 1, Integer::sum);
        }
        List<String> words = new ArrayList<>(counts.keySet());
        words.sort((a, b) -> {
            int byCount = counts.get(b) - counts.get(a);
            return byCount != 0 ? byCount : b.length() - a.length();
        });
        return words.size() > limit ? new ArrayList<>(words.subList(0, limit)) : words;
    }

    /** Every term the model produced, wherever it nested it in the JSON. */
    public static List<String> flatten(Object node, List<String> out) {
        if (node == null) {
            return out;
        }
        if (node instanceof String text) {
            out.add(text);
        } else if (node instanceof Iterable<?> items) {
            for (Object item : items) {
                flatten(item, out);
            }
        } else if (node instanceof Map<?, ?> map) {
            for (Object value : map.values()) {
                flatten(value, out);
            }
        }
        return out;
    }

    /** Strips query syntax, so one bad term cannot break a combined query_string. */
    public static String cleanTerm(String term) {
        return term.replaceAll("[^\\p{L}\\p{N}]+", " ").trim().toLowerCase(Locale.ROOT);
    }

    /** Removes duplicates while keeping the order the model produced. */
    public static List<String> dedupe(List<String> terms) {
        return new ArrayList<>(new LinkedHashSet<>(terms));
    }

    /** The first non-blank field among the candidates, or an empty string. */
    public static String firstText(JsonNode source, String... fields) {
        for (String field : fields) {
            String value = source.path(field).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
