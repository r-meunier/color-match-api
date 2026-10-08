package com.rmeunier.colormatchapi.config;

import com.rmeunier.colormatchapi.exception.ProductNotFoundException;
import com.rmeunier.colormatchapi.exception.ResourceNotFoundException;
import com.rmeunier.colormatchapi.model.GenderId;
import com.rmeunier.colormatchapi.model.Product;
import com.rmeunier.colormatchapi.service.IProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ItemProcessorsTest {

    @Mock
    private IProductService productService;

    private static Product product(String id, int[] dominantColor) {
        return new Product(id, "Polo", GenderId.MAN, "100% Coton", "Manches courtes",
                "//img/" + id + ".jpg", "https://example.com/" + id, dominantColor);
    }

    @Nested
    class ImportProcessor {

        private ImportProductItemProcessor processor;

        @BeforeEach
        void setUp() {
            processor = new ImportProductItemProcessor();
            ReflectionTestUtils.setField(processor, "productService", productService);
        }

        @Test
        void passesThroughNewProducts() throws Exception {
            Product imported = product("P1", null);
            when(productService.findById("P1")).thenThrow(new ProductNotFoundException("P1"));

            assertThat(processor.process(imported)).isSameAs(imported);
        }

        @Test
        void passesThroughExistingProductsWithoutStoredColor() throws Exception {
            Product imported = product("P1", null);
            when(productService.findById("P1")).thenReturn(product("P1", null));

            assertThat(processor.process(imported)).isSameAs(imported);
        }

        @Test
        void skipsExistingProductsWithStoredColorSoTheColorIsNotOverwritten() throws Exception {
            when(productService.findById("P1")).thenReturn(product("P1", new int[]{1, 2, 3}));

            assertThat(processor.process(product("P1", null))).isNull();
        }
    }

    @Nested
    class DomColorProcessor {

        private DomColorProductItemProcessor processor;

        @BeforeEach
        void setUp() {
            processor = new DomColorProductItemProcessor();
            ReflectionTestUtils.setField(processor, "productService", productService);
        }

        @Test
        void skipsProductsThatAlreadyHaveAColor() {
            Product product = product("P1", new int[]{1, 2, 3});

            assertThat(processor.process(product)).isNull();
            verify(productService, never()).findDominantColor(product);
        }

        @Test
        void setsTheFoundColorOnTheProduct() {
            Product product = product("P1", null);
            when(productService.findDominantColor(product)).thenReturn(new int[]{10, 20, 30});

            Product processed = processor.process(product);

            assertThat(processed).isSameAs(product);
            assertThat(processed.getDominantColor()).containsExactly(10, 20, 30);
        }

        @Test
        void skipsProductsWhoseImageCannotBeLoaded() {
            Product product = product("P1", null);
            when(productService.findDominantColor(product)).thenThrow(new ResourceNotFoundException("//img/P1.jpg"));

            assertThat(processor.process(product)).isNull();
        }

        @Test
        void skipsProductsWhenNoColorIsFound() {
            Product product = product("P1", null);
            when(productService.findDominantColor(product)).thenReturn(null);

            assertThat(processor.process(product)).isNull();
        }
    }

    @Nested
    class ImportParameters {

        private final ImportParamValidator validator = new ImportParamValidator();

        @Test
        void acceptsAFilePath() {
            JobParameters parameters = new JobParametersBuilder().addString("filePath", "/data/products.csv")
                    .toJobParameters();

            assertThatCode(() -> validator.validate(parameters)).doesNotThrowAnyException();
        }

        @Test
        void rejectsMissingFilePath() {
            assertThatThrownBy(() -> validator.validate(new JobParameters()))
                    .isInstanceOf(InvalidJobParametersException.class);
        }

        @Test
        void rejectsEmptyFilePath() {
            JobParameters parameters = new JobParametersBuilder().addString("filePath", "").toJobParameters();

            assertThatThrownBy(() -> validator.validate(parameters))
                    .isInstanceOf(InvalidJobParametersException.class);
        }
    }
}
