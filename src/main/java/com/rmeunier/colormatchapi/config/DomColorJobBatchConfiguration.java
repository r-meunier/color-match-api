package com.rmeunier.colormatchapi.config;

import com.rmeunier.colormatchapi.dao.ProductRepository;
import com.rmeunier.colormatchapi.model.Product;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.data.RepositoryItemReader;
import org.springframework.batch.infrastructure.item.data.RepositoryItemWriter;
import org.springframework.batch.infrastructure.item.data.builder.RepositoryItemReaderBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class DomColorJobBatchConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(DomColorJobBatchConfiguration.class);

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    @Qualifier("taskExecutor")
    private ThreadPoolTaskExecutor taskExecutor;

    @Value("${chunk-size}")
    private int chunkSize;

    @Bean
    public Job domColorJob(DomColorJobCompletionNotificationListener listener, Step domColorStep1) {
        return new JobBuilder("domColorJob", jobRepository)
                .listener(listener)
                .start(domColorStep1)
                .build();
    }

    @Bean
    public DomColorJobCompletionNotificationListener domColorJobExecutionListener() {
        return new DomColorJobCompletionNotificationListener();
    }

    /**
     * The RepositoryItemReader to read Product records from database using Spring JPA.
     * The page size can be set to reasonably large for better performance,
     * but a cursor-based reader could be a higher performing option instead.
     * @return the RepositoryItemReader object
     */
    @Bean
    @StepScope
    public RepositoryItemReader<Product> databaseReader() {
        Map<String, Sort.Direction> sorts = new HashMap<>();
        sorts.put("id", Sort.Direction.ASC);
        return new RepositoryItemReaderBuilder<Product>()
                .repository(productRepository)
                .methodName("findAll")
                .pageSize(100)
                .sorts(sorts)
                .saveState(false)
                .name("dominantColorReader")
                .build();
    }

    /**
     * The ItemProcessor for getting each read record
     * and retrieving the dominant colors for the not null objects in db.
     * @return the ProductItemProcessor bean
     */
    @Bean
    public DomColorProductItemProcessor processor() {
        return new DomColorProductItemProcessor();
    }

    /**
     *
     * Spring Batch Step for reading, processing and writing Product elements.
     * It reads the Products from the database, then processes them (sets Dominant Colors)
     * and then persists them to the database.
     *
     * @param databaseWriter the RepositoryItemWriter bean
     * @return the Step object
     */
    @Bean
    public Step domColorStep1(RepositoryItemWriter<Product> databaseWriter) {
        return new StepBuilder("domColorStep1", jobRepository)
                .<Product, Product> chunk(chunkSize)
                .transactionManager(transactionManager)
                .reader(databaseReader())
                .processor(processor())
                .writer(databaseWriter)
                .listener(itemCountListener())
                // Multi-threaded execution
                .taskExecutor(taskExecutor)
                .build();
    }

    /**
     * This is just a listener interface for counting processed items in chunks.
     * @return the ItemCountListener
     */
    @Bean
    public ItemCountListener itemCountListener() {
        return new ItemCountListener();
    }
}
