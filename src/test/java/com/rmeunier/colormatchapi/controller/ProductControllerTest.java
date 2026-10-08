package com.rmeunier.colormatchapi.controller;

import com.rmeunier.colormatchapi.exception.ColorMissingException;
import com.rmeunier.colormatchapi.exception.ProductNotFoundException;
import com.rmeunier.colormatchapi.exception.ResourceNotFoundException;
import com.rmeunier.colormatchapi.model.GenderId;
import com.rmeunier.colormatchapi.model.Product;
import com.rmeunier.colormatchapi.service.IProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.servlet.ServletException;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller tests with a standalone MockMvc and a mocked service, so no Spring context or database is needed.
 */
@ExtendWith(MockitoExtension.class)
class ProductControllerTest {

    @Mock
    private IProductService productService;

    private MockMvc mockMvc;

    private final Product product = new Product("L1212-00-001", "Polo Lacoste L.12.12 uni", GenderId.MAN,
            "100% Coton", "Manches courtes", "//image1.example.com/L1212_001.jpg",
            "https://www.example.com/L1212-00.html", new int[]{10, 20, 30});

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProductController(productService)).build();
    }

    @Test
    void getProductsReturnsAllProductsAsJson() throws Exception {
        when(productService.findAll()).thenReturn(Collections.singletonList(product));

        mockMvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value("L1212-00-001"))
                .andExpect(jsonPath("$[0].title").value("Polo Lacoste L.12.12 uni"))
                .andExpect(jsonPath("$[0].genderId").value("MAN"))
                .andExpect(jsonPath("$[0].composition").value("100% Coton"))
                .andExpect(jsonPath("$[0].sleeve").value("Manches courtes"))
                .andExpect(jsonPath("$[0].photo").value("//image1.example.com/L1212_001.jpg"))
                .andExpect(jsonPath("$[0].url").value("https://www.example.com/L1212-00.html"))
                .andExpect(jsonPath("$[0].dominantColor").value(org.hamcrest.Matchers.contains(10, 20, 30)));
    }

    @Test
    void getProductReturnsSingleProduct() throws Exception {
        when(productService.findById("L1212-00-001")).thenReturn(product);

        mockMvc.perform(get("/products/L1212-00-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("L1212-00-001"));
    }

    /**
     * There is no exception handler yet (see the development notes), so an unknown product id
     * is not turned into a 404 and the exception reaches the servlet container.
     */
    @Test
    void getProductWithUnknownIdIsNotHandled() {
        when(productService.findById("MISSING")).thenThrow(new ProductNotFoundException("MISSING"));

        assertThatThrownBy(() -> mockMvc.perform(get("/products/MISSING")))
                .isInstanceOf(ServletException.class)
                .hasCauseInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void getColorReturnsStoredColor() throws Exception {
        when(productService.getDominantColor("L1212-00-001")).thenReturn(new int[]{10, 20, 30});

        mockMvc.perform(get("/getColor/L1212-00-001"))
                .andExpect(status().isOk())
                .andExpect(content().json("[10,20,30]"));
    }

    @Test
    void importProductsPassesPlainTextFilePathToService() throws Exception {
        mockMvc.perform(post("/importProducts")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("/usr/api-service/res/products_lacoste_sample.csv"))
                .andExpect(status().isOk());

        verify(productService).importProductsFromFilePath("/usr/api-service/res/products_lacoste_sample.csv");
    }

    @Test
    void importProductsRejectsJson() throws Exception {
        mockMvc.perform(post("/importProducts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"/data/products.csv\"}"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void loadColorReturnsTheLoadedColor() throws Exception {
        when(productService.findById("L1212-00-001")).thenReturn(product);
        when(productService.findDominantColorAndSave(product)).thenReturn(new int[]{10, 20, 30});

        mockMvc.perform(post("/loadColor/L1212-00-001"))
                .andExpect(status().isOk())
                .andExpect(content().json("[10,20,30]"));
    }

    @Test
    void loadColorReturnsEmptyBodyWhenImageCannotBeLoaded() throws Exception {
        when(productService.findById("L1212-00-001")).thenReturn(product);
        when(productService.findDominantColorAndSave(product)).thenThrow(new ResourceNotFoundException("//img"));

        mockMvc.perform(post("/loadColor/L1212-00-001"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    void loadColorForAllProductsStartsTheBatchJob() throws Exception {
        mockMvc.perform(post("/loadColorForAllProducts"))
                .andExpect(status().isOk());

        verify(productService).findDominantColorForAllProducts();
    }

    @Test
    void getProductsOfColorReturnsMatchingProducts() throws Exception {
        when(productService.findById("REF")).thenReturn(product);
        when(productService.getProductsOfColorLike(product, 5)).thenReturn(Collections.singletonList(product));

        mockMvc.perform(post("/getProductsOfColor/REF/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value("L1212-00-001"));
    }

    @Test
    void getProductsOfColorReturnsEmptyListForNonPositiveCount() throws Exception {
        when(productService.findById("REF")).thenReturn(product);

        mockMvc.perform(post("/getProductsOfColor/REF/0"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        verify(productService, never()).getProductsOfColorLike(org.mockito.ArgumentMatchers.any(), anyInt());
    }

    @Test
    void getProductsOfColorReturnsEmptyListWhenReferenceHasNoColor() throws Exception {
        when(productService.findById("REF")).thenReturn(product);
        when(productService.getProductsOfColorLike(product, 5)).thenThrow(new ColorMissingException());

        mockMvc.perform(post("/getProductsOfColor/REF/5"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }
}
