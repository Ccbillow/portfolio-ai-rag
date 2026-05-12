package com.simon.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    private List<String> companies = new ArrayList<>();
    private Map<String, List<String>> companySubgroups = new HashMap<>();
    private Upload upload = new Upload();
    private Embedding embedding = new Embedding();
    private Cache cache = new Cache();
    private Reranker reranker = new Reranker();
    private HybridSearch hybridSearch = new HybridSearch();

    @Data
    public static class Upload {
        private String dir = "/tmp/rag-uploads";
    }

    @Data
    public static class Embedding {
        private int chunkSize = 512;
        private int chunkOverlap = 60;
        private int topK = 3;
        private double minScore = 0.3;
    }

    @Data
    public static class Cache {
        /** Disabled by default — enable in production after all algorithm changes are finalized */
        private boolean enabled = false;
        private int ttlHours = 24;
    }

    @Data
    public static class HybridSearch {
        private boolean enabled = true;
    }

    @Data
    public static class Reranker {
        private boolean enabled = true;
        /** Candidates fetched from Qdrant before reranking */
        private int candidateK = 10;
        /** Final chunks kept after reranking, fed to LLM */
        private int topN = 3;
        private String model = "rerank-v3.5";
        /** Drop reranked chunks below this score; always keeps at least 1 */
        private double minRerankScore = 0.1;
    }
}
