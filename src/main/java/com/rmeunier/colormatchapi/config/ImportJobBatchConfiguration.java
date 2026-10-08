package com.rmeunier.colormatchapi.config;

import com.rmeunier.colormatchapi.dao.ProductRepository;
import com.rmeunier.colormatchapi.model.Product;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.data.RepositoryItemWriter;
import org.springframework.batch.infrastructure.item.data.builder.RepositoryItemWriterBuilder;
import org.springframework.batch.infrastructure.item.file.FlatFileItemReader;
import org.springframework.batch.infrastructure.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class ImportJobBatchConfiguration {

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ProductRepository productRepository;

    @Value("${chunk-size}")
    private int chunkSize;

    @Bean
    public Job importProductJob(ImportJobCompletionNotificationListener listener, Step step1) {
        return new JobBuilder("importProductJob", jobRepository)
                .listener(listener)
                .validator(validator())
                .start(step1)
                .build();
    }

    @Bean
    public ImportJobCompletionNotificationListener importJobExecutionListener() {
        return new ImportJobCompletionNotificationListener();
    }

    /**
     * Responsible for reading and parsing the Products from a CSV file of given filePath.
     * filePath is received from JobParameters upon starting the job in ProductService.
     * @param filePath the CSV file's path
     * @return the FlatFileItemReader object to read from CSV file
     */
    @StepScope
    @Bean
    public FlatFileItemReader<Product> fileReader(@Value("#{jobParameters['filePath']}") String filePath) {
        return new FlatFileItemReaderBuilder<Product>().name("productItemReader")
                .resource(new FileSystemResource(filePath))
                // Skip header line of file
                .linesToSkip(1)
                .delimited()
                .names("id", "title", "gender_id", "composition", "sleeve", "photo", "url")
                .targetType(Product.class)
                .build();
    }

    @Bean
    public ImportParamValidator validator() {
        return new ImportParamValidator();
    }

    /**
     * Creates the writer that writes the parsed Products into the database using the Repository.
     * @return the RepositoryItemWriter object to write to db
     */
    @Bean(name = "databaseWriter")
    public RepositoryItemWriter<Product> databaseWriter() {
        return new RepositoryItemWriterBuilder<Product>()
                .repository(productRepository)
                .build();
    }

    /**
     * This is the step for the import job.
     * It reads the CSV file and writes the records to the database, mapped by the Product entity.
     * It sets the chunk size received from the application.properties file, sets the reader and writer,
     * as well as the taskExecutor for processing the items of a chunk concurrently.
     * @param writer the RepositoryWriter bean
     * @return the batch Step
     */
    @Bean
    public Step step1(RepositoryItemWriter<Product> writer) {
        return new StepBuilder("step1", jobRepository)
                .<Product, Product> chunk(chunkSize)
                .transactionManager(transactionManager)
                .reader(fileReader(null))
                .processor(importProcessor())
                .writer(writer)
                // Multi-threaded execution
                .taskExecutor(taskExecutor())
                .build();
    }

    @Bean
    public ImportProductItemProcessor importProcessor() {
        return new ImportProductItemProcessor();
    }

    /**
     * Creates the Task Executor to use multi-threaded execution in the file processing.
     * @return the ThreadPoolTaskExecutor object
     */
    @Bean(name = "taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(11);
        executor.setQueueCapacity(6);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setThreadNamePrefix("ProductThread-");
        return executor;
    }
}
