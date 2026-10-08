package com.rmeunier.colormatchapi.service.impl;

import com.rmeunier.colormatchapi.dao.ProductRepository;
import com.rmeunier.colormatchapi.exception.ColorMissingException;
import com.rmeunier.colormatchapi.exception.ProductNotFoundException;
import com.rmeunier.colormatchapi.model.GenderId;
import com.rmeunier.colormatchapi.model.Product;
import com.rmeunier.colormatchapi.model.Schema;
import com.rmeunier.colormatchapi.service.ColorProximity;
import com.rmeunier.colormatchapi.service.IVisionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private IVisionService visionService;

    @Mock
    private JobOperator jobOperator;

    @Mock
    private Job importProductJob;

    @Mock
    private Job domColorJob;

    private ProductService productService;

    @BeforeEach
    void setUp() {
        productService = new ProductService(productRepository, visionService);
        ReflectionTestUtils.setField(productService, "colorProximity", new ColorProximity());
        ReflectionTestUtils.setField(productService, "jobOperator", jobOperator);
        ReflectionTestUtils.setField(productService, "importProductJob", importProductJob);
        ReflectionTestUtils.setField(productService, "domColorJob", domColorJob);
    }

    private static Product product(String id, int[] dominantColor) {
        return new Product(id, "Polo " + id, GenderId.MAN, "100% Coton", "Manches courtes",
                "//images.example.com/" + id + ".jpg", "https://www.example.com/" + id, dominantColor);
    }

    @Test
    void findByIdReturnsProduct() {
        Product product = product("P1", null);
        when(productRepository.findById("P1")).thenReturn(Optional.of(product));

        assertThat(productService.findById("P1")).isSameAs(product);
    }

    @Test
    void findByIdThrowsWhenProductDoesNotExist() {
        when(productRepository.findById("MISSING")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.findById("MISSING"))
                .isInstanceOf(ProductNotFoundException.class)
                .hasMessageContaining("MISSING");
    }

    @Test
    void saveProductFromFieldsSavesValidProduct() {
        boolean saved = productService.saveProduct("P1", "Polo", "WOM", "100% Coton", "Manches courtes",
                "//img/p1.jpg", "https://example.com/p1");

        assertThat(saved).isTrue();
        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo("P1");
        assertThat(captor.getValue().getGenderId()).isEqualTo(GenderId.WOM);
    }

    @Test
    void saveProductFromFieldsRejectsUnknownGender() {
        boolean saved = productService.saveProduct("P1", "Polo", "XYZ", "100% Coton", "Manches courtes",
                "//img/p1.jpg", "https://example.com/p1");

        assertThat(saved).isFalse();
        verify(productRepository, never()).save(any());
    }

    @Test
    void getDominantColorByIdReturnsStoredColor() {
        when(productRepository.findById("P1")).thenReturn(Optional.of(product("P1", new int[]{1, 2, 3})));

        assertThat(productService.getDominantColor("P1")).containsExactly(1, 2, 3);
    }

    @Test
    void findDominantColorReturnsStoredColorWithoutCallingVisionApi() {
        Product product = product("P1", new int[]{1, 2, 3});

        assertThat(productService.findDominantColor(product)).containsExactly(1, 2, 3);
        verify(visionService, never()).loadDominantColorForImage(anyString(), any());
    }

    @Test
    void findDominantColorCallsVisionApiOverHttpsWhenColorIsMissing() {
        Product product = product("P1", null);
        when(visionService.loadDominantColorForImage(product.getPhoto(), Schema.HTTPS))
                .thenReturn(new int[]{10, 20, 30});

        assertThat(productService.findDominantColor(product)).containsExactly(10, 20, 30);
    }

    @Test
    void findDominantColorAndSavePersistsTheColor() {
        Product product = product("P1", null);
        when(visionService.loadDominantColorForImage(anyString(), any())).thenReturn(new int[]{10, 20, 30});

        assertThat(productService.findDominantColorAndSave(product)).containsExactly(10, 20, 30);
        assertThat(product.getDominantColor()).containsExactly(10, 20, 30);
        verify(productRepository).save(product);
    }

    @Test
    void findDominantColorAndSaveThrowsWhenNoColorIsFound() {
        Product product = product("P1", null);
        when(visionService.loadDominantColorForImage(anyString(), any())).thenReturn(null);

        assertThatThrownBy(() -> productService.findDominantColorAndSave(product))
                .isInstanceOf(ColorMissingException.class);
        verify(productRepository, never()).save(any());
    }

    @Test
    void getProductsOfColorLikeReturnsClosestColorsFirst() {
        Product reference = product("REF", new int[]{255, 0, 0});
        Product nearRed = product("NEAR_RED", new int[]{250, 5, 5});
        Product darkRed = product("DARK_RED", new int[]{139, 0, 0});
        Product blue = product("BLUE", new int[]{0, 0, 255});
        Product noColor = product("NO_COLOR", null);
        when(productRepository.findAll()).thenReturn(Arrays.asList(blue, noColor, darkRed, reference, nearRed));

        List<Product> result = productService.getProductsOfColorLike(reference, 2);

        assertThat(result).extracting(Product::getId).containsExactly("NEAR_RED", "DARK_RED");
    }

    @Test
    void getProductsOfColorLikeExcludesReferenceAndProductsWithoutColor() {
        Product reference = product("REF", new int[]{255, 0, 0});
        Product sameColor = product("SAME", new int[]{255, 0, 0});
        Product blue = product("BLUE", new int[]{0, 0, 255});
        Product noColor = product("NO_COLOR", null);
        when(productRepository.findAll()).thenReturn(Arrays.asList(reference, sameColor, blue, noColor));

        List<Product> result = productService.getProductsOfColorLike(reference, 10);

        assertThat(result).extracting(Product::getId).containsExactly("SAME", "BLUE");
    }

    @Test
    void getProductsOfColorLikeThrowsWhenReferenceHasNoColor() {
        assertThatThrownBy(() -> productService.getProductsOfColorLike(product("REF", null), 5))
                .isInstanceOf(ColorMissingException.class);
    }

    @Test
    void importProductsStartsImportJobWithFilePath() throws Exception {
        productService.importProductsFromFilePath("/data/products.csv");

        ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobOperator).start(eq(importProductJob), captor.capture());
        assertThat(captor.getValue().getString("filePath")).isEqualTo("/data/products.csv");
        assertThat(captor.getValue().getLong("batchJobId")).isPositive();
    }

    @Test
    void importProductsSwallowsJobLaunchErrors() throws Exception {
        when(jobOperator.start(eq(importProductJob), any()))
                .thenThrow(new JobInstanceAlreadyCompleteException("already done"));

        assertThatCode(() -> productService.importProductsFromFilePath("/data/products.csv"))
                .doesNotThrowAnyException();
    }

    @Test
    void findDominantColorForAllProductsStartsDomColorJob() throws Exception {
        productService.findDominantColorForAllProducts();

        verify(jobOperator).start(eq(domColorJob), any(JobParameters.class));
    }
}
