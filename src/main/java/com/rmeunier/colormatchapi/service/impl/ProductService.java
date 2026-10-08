package com.rmeunier.colormatchapi.service.impl;

import com.rmeunier.colormatchapi.dao.ProductRepository;
import com.rmeunier.colormatchapi.exception.ColorMissingException;
import com.rmeunier.colormatchapi.exception.ProductNotFoundException;
import com.rmeunier.colormatchapi.exception.ResourceNotFoundException;
import com.rmeunier.colormatchapi.model.GenderId;
import com.rmeunier.colormatchapi.model.Product;
import com.rmeunier.colormatchapi.model.Schema;
import com.rmeunier.colormatchapi.service.ColorProximity;
import com.rmeunier.colormatchapi.service.IProductService;
import com.rmeunier.colormatchapi.service.IVisionService;
import org.apache.commons.lang3.EnumUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.JobRestartException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ProductService implements IProductService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProductService.class);

    @Value("${file.delim:,}")
    private String DELIM;

    private ProductRepository productRepository;

    private IVisionService visionService;

    @Autowired
    private ColorProximity colorProximity;

    @Autowired
    private JobOperator jobOperator;

    // Lazy, as the jobs' item processors depend on this service in turn
    @Lazy
    @Autowired
    private Job importProductJob;

    @Lazy
    @Autowired
    private Job domColorJob;

    @Autowired
    public ProductService(ProductRepository productRepository, IVisionService visionService) {
        this.productRepository = productRepository;
        this.visionService = visionService;
    }

    @Override
    public List<Product> findAll() {
        return productRepository.findAll();
    }

    @Override
    public Product findById(String id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
    }

    @Override
    public void saveProduct(Product product) {
        productRepository.save(product);
    }

    @Override
    public boolean saveProduct(String id, String title, String genderId, String composition, String sleeve, String photo, String url) {
        boolean isGenderIdValid = EnumUtils.isValidEnum(GenderId.class, genderId);

        if (!isGenderIdValid) {
            return false;
        }

        Product product = new Product(id, title, GenderId.valueOf(genderId), composition, sleeve, photo, url);
        saveProduct(product);

        return true;
    }

    /**
     * Import products from a CSV file of a given file path.
     * As the processing of the file happens line by line using the Scanner,
     * it does not store the whole file in the memory, therefore memory usage will be limited.
     *
     * @param filePath the path to the products CSV file.
     */
    @Override
    public void importProductsFromFilePath(String filePath) {
        LOGGER.info("Starting import of CSV file from filepath: {}...", filePath);

        try {
            JobParameters jobParameters = new JobParametersBuilder()
                    .addLong("batchJobId", System.currentTimeMillis())
                    .addString("filePath", filePath)
                    .toJobParameters();
            jobOperator.start(importProductJob, jobParameters);
        } catch (JobExecutionAlreadyRunningException | JobRestartException
                | JobInstanceAlreadyCompleteException | InvalidJobParametersException e) {
            LOGGER.error("Batch import could not be started! Error: {}", e.getMessage());
        }
    }

    /**
     * Finds the dominant color of a provided product and parses it into an int array.
     *
     * @param product the Product to find the dominant color of.
     * @return the dominant color's RGB vector
     */
    public int[] getDominantColor(Product product) {
        return product.getDominantColor();
    }

    /**
     * Finds a product based on provided productId and then finds the dominant color of this product.
     *
     * @param productId the ID of the product for finding its dominant color
     * @return the dominant color's RGB vector
     */
    public int[] getDominantColor(String productId) {
        Product product = findById(productId);
        return getDominantColor(product);
    }

    /**
     * Saves the RGB color vector for the dominant color of a product.
     *
     * @param product the product to save
     * @param color   the dominant color vector to save
     */
    private void addDomColorToDb(Product product, int[] color) {
        product.setDominantColor(color);
        productRepository.save(product);
    }

    /**
     * Get dominant color for a single product.
     * Checks first if dominant color exists first for current product.
     *
     * @param product the product to get the color for
     * @return the String value of the product's dominant color
     */
    @Override
    public int[] findDominantColor(Product product) throws ResourceNotFoundException {
        if (domColorExists(product)) {
            LOGGER.info("Dominant color already exists for this product: {}", product.getId());
            return product.getDominantColor();
        } else {
            LOGGER.info("Starting Vision API to find dominant color for product: {}", product.getId());
            String photoPath = product.getPhoto();
            return visionService.loadDominantColorForImage(photoPath, Schema.HTTPS);
        }
    }

    /**
     * Find a single product's dominant color and persist it to database.
     *
     * @param product the product to find the dominant color of
     * @return the dominant color RGB vector
     */
    public int[] findDominantColorAndSave(Product product) {
        int[] domColor = findDominantColor(product);

        if (domColor == null) {
            throw new ColorMissingException("Could not find dominant color for product: " + product.getId());
        }

        addDomColorToDb(product, domColor);
        return domColor;
    }

    /**
     * Calls the Spring Batch job to load all records from database,
     * then call the Vision API on all Product items that have null for the value of dominantColor.
     * Saves the found dominant colors for each product.
     */
    public void findDominantColorForAllProducts() {
        LOGGER.info("Starting to load dominant colors for all products...");

        try {
            JobParameters jobParameters = new JobParametersBuilder()
                    .addLong("domColorBatchJobId", System.currentTimeMillis())
                    .toJobParameters();
            jobOperator.start(domColorJob, jobParameters);
        } catch (JobExecutionAlreadyRunningException | JobRestartException
                | JobInstanceAlreadyCompleteException | InvalidJobParametersException e) {
            LOGGER.error("Batch processing dominant colors could not be started! Error: {}", e.getMessage());
        }
    }

    /**
     * Finds an n-element list of products that have the closest dominant color to reference product's dominant color.
     * It filters the products from the list that have a null element.
     *
     * @param color the reference product's dominant color RGB vector
     * @param n     the number of items to retrieve
     * @return the n-long list of products that are closest in color proximity to the reference color
     */
    private List<Product> findProductsOfClosestColor(String id, int[] color, int n) {
        List<Product> products = this.findAll();

        products = products.stream()
                .filter(product -> product.getDominantColor() != null)
                .sorted((o1, o2) -> {
                    int[] domColorProd1 = o1.getDominantColor();
                    int[] domColorProd2 = o2.getDominantColor();

                    double colorDistance1 = Math.abs(colorProximity.proximity(color, domColorProd1));
                    double colorDistance2 = Math.abs(colorProximity.proximity(color, domColorProd2));

                    return Double.compare(colorDistance1, colorDistance2);
                })
                // filtering out original, reference product from the list
                .filter(product -> !id.equals(product.getId()))
                .limit(n)
                .collect(Collectors.toList());

        return products;
    }

    /**
     * Starts algorithm to find the n-length list of products that have the closest color to the reference
     * product's dominant color. Also checks if the given product's dominant color is not null.
     *
     * @param product the reference product to check the color based on
     * @param n       the number of items to return
     * @return the n-long list of Products containing the results
     */
    public List<Product> getProductsOfColorLike(Product product, int n) {
        int[] domColor = product.getDominantColor();

        if (!domColorExists(product)) {
            throw new ColorMissingException("No dominant color exists for product: " + product.getId());
        }

        return findProductsOfClosestColor(product.getId(), domColor, n);
    }

    /**
     * Checks if dominant color is stored in the database or not.
     * It is null if it does not exist.
     *
     * @param product the product to check for
     * @return true if exists, false if null
     */
    private boolean domColorExists(Product product) {
        return product.getDominantColor() != null;
    }

}
