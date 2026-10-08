package com.rmeunier.colormatchapi.config;

import com.rmeunier.colormatchapi.model.Product;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.listener.ChunkListener;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.Chunk;

/**
 * Logs the step's progress summary after each chunk.
 * The step execution is captured before the step starts, as chunk callbacks only receive the chunk itself.
 */
public class ItemCountListener implements ChunkListener<Product, Product>, StepExecutionListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(ItemCountListener.class);

    private volatile StepExecution stepExecution;

    @Override
    public void beforeStep(StepExecution stepExecution) {
        this.stepExecution = stepExecution;
    }

    @Override
    public void afterChunk(Chunk<Product> chunk) {
        StepExecution execution = this.stepExecution;
        if (execution != null) {
            LOGGER.info("Summary: {}", execution.getSummary());
        }
    }
}
