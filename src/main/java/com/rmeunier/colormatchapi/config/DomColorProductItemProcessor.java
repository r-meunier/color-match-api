package com.rmeunier.colormatchapi.config;

import com.rmeunier.colormatchapi.exception.ResourceNotFoundException;
import com.rmeunier.colormatchapi.model.Product;
import com.rmeunier.colormatchapi.service.IProductService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Autowired;

public class DomColorProductItemProcessor implements ItemProcessor<Product, Product> {

    private static final Logger LOGGER = LoggerFactory.getLogger(DomColorProductItemProcessor.class);

    @Autowired
    private IProductService productService;

    @Override
    public Product process(Product product) {
        // filtering existing dominant color records to be skipped
        if (product.getDominantColor() != null) {
            return null;
        }

        int[] domColor;

        try {
            domColor = productService.findDominantColor(product);
        } catch (ResourceNotFoundException e) {
            LOGGER.error("Could not get resource! Error: {}", e.getMessage());
            return null;
        }

        if (domColor == null) {
            return null;
        }

        product.setDominantColor(domColor);

        return product;
    }
}
