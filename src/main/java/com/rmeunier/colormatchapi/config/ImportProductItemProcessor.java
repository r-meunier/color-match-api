package com.rmeunier.colormatchapi.config;

import com.rmeunier.colormatchapi.exception.ProductNotFoundException;
import com.rmeunier.colormatchapi.model.Product;
import com.rmeunier.colormatchapi.service.IProductService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Autowired;

public class ImportProductItemProcessor implements ItemProcessor<Product, Product> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ImportProductItemProcessor.class);

    @Autowired
    private IProductService productService;

    @Override
    public Product process(Product product) throws Exception {
        Product dbProd = null;

        try {
            dbProd = productService.findById(product.getId());
        } catch (ProductNotFoundException e) {
            // empty
        }

        if (dbProd == null) {
            return product;
        }

        if (dbProd.getDominantColor() != null && product.getDominantColor() == null) {
            return null;
        }

        return product;
    }
}
