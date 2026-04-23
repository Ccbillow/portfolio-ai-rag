package com.simon.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    private Upload upload = new Upload();
    private Embedding embedding = new Embedding();
    private Cache cache = new Cache();

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
}
