package com.rmeunier.colormatchapi;

import com.rmeunier.colormatchapi.dao.ProductRepository;
import com.rmeunier.colormatchapi.model.GenderId;
import com.rmeunier.colormatchapi.model.Product;
import com.rmeunier.colormatchapi.model.Schema;
import com.rmeunier.colormatchapi.service.IProductService;
import com.rmeunier.colormatchapi.service.IVisionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import com.google.cloud.spring.vision.CloudVisionTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runs the application against a real PostgreSQL database in Docker, using the Lacoste sample catalogue.
 * The Google Vision API is replaced by {@link FakeVisionService}, so no credentials or network access are needed.
 */
@SpringBootTest(properties = {
        "spring.cloud.gcp.core.enabled=false",
        "spring.cloud.gcp.vision.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ColorMatchApiIntegrationTest {

    private static final String SAMPLE_CSV = Paths.get("docker", "res", "products_lacoste_sample.csv")
            .toAbsolutePath().toString();
    private static final int SAMPLE_SIZE = 499;

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:13.4");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @TestConfiguration
    static class FakeVisionConfiguration {

        @Bean
        CloudVisionTemplate cloudVisionTemplate() {
            return Mockito.mock(CloudVisionTemplate.class);
        }

        @Bean
        @Primary
        FakeVisionService fakeVisionService() {
            return new FakeVisionService();
        }
    }

    /**
     * Returns a color derived from the image path, and records which paths were requested.
     */
    static class FakeVisionService implements IVisionService {

        final Set<String> requestedPaths = ConcurrentHashMap.newKeySet();

        @Override
        public int[] loadDominantColorForImage(String filePath, Schema schema) {
            requestedPaths.add(filePath);
            return colorFor(filePath);
        }

        static int[] colorFor(String filePath) {
            int hash = filePath.hashCode();
            return new int[]{hash & 0xFF, (hash >> 8) & 0xFF, (hash >> 16) & 0xFF};
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private IProductService productService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private FakeVisionService fakeVisionService;

    @BeforeEach
    void cleanDatabase() {
        productRepository.deleteAll();
        fakeVisionService.requestedPaths.clear();
    }

    @Test
    void importJobLoadsTheSampleCatalogue() {
        productService.importProductsFromFilePath(SAMPLE_CSV);

        assertThat(productRepository.count()).isEqualTo(SAMPLE_SIZE);

        Product product = productRepository.findById("L1212-00-001").orElseThrow(AssertionError::new);
        assertThat(product.getTitle()).isEqualTo("Polo Lacoste L.12.12 uni");
        assertThat(product.getGenderId()).isEqualTo(GenderId.MAN);
        assertThat(product.getComposition()).isEqualTo("100% Coton");
        assertThat(product.getSleeve()).isEqualTo("Manches courtes");
        assertThat(product.getPhoto()).startsWith("//image1.lacoste.com/").contains("L1212_001_24.jpg");
        assertThat(product.getUrl()).startsWith("https://www.lacoste.com/fr/");
        assertThat(product.getDominantColor()).isNull();
    }

    @Test
    void importEndpointRunsTheImportJob() throws Exception {
        mockMvc.perform(post("/importProducts").contentType("text/plain").content(SAMPLE_CSV))
                .andExpect(status().isOk());

        assertThat(productRepository.count()).isEqualTo(SAMPLE_SIZE);
    }

    @Test
    void reimportDoesNotOverwriteStoredDominantColors() {
        productService.importProductsFromFilePath(SAMPLE_CSV);
        Product product = productRepository.findById("L1212-00-001").orElseThrow(AssertionError::new);
        product.setDominantColor(new int[]{1, 2, 3});
        productRepository.save(product);

        productService.importProductsFromFilePath(SAMPLE_CSV);

        assertThat(productRepository.count()).isEqualTo(SAMPLE_SIZE);
        assertThat(productRepository.findById("L1212-00-001").orElseThrow(AssertionError::new).getDominantColor())
                .containsExactly(1, 2, 3);
    }

    @Test
    void dominantColorJobFillsMissingColorsAndSkipsStoredOnes() {
        productService.importProductsFromFilePath(SAMPLE_CSV);
        Product withColor = productRepository.findById("L1212-00-001").orElseThrow(AssertionError::new);
        withColor.setDominantColor(new int[]{1, 2, 3});
        productRepository.save(withColor);

        productService.findDominantColorForAllProducts();

        List<Product> products = productRepository.findAll();
        assertThat(products).hasSize(SAMPLE_SIZE).allSatisfy(p -> assertThat(p.getDominantColor()).isNotNull());
        assertThat(productRepository.findById("L1212-00-001").orElseThrow(AssertionError::new).getDominantColor())
                .containsExactly(1, 2, 3);
        assertThat(fakeVisionService.requestedPaths)
                .hasSize(SAMPLE_SIZE - 1)
                .doesNotContain(withColor.getPhoto());

        Product other = productRepository.findById("L1212-00-031").orElseThrow(AssertionError::new);
        assertThat(other.getDominantColor()).containsExactly(FakeVisionService.colorFor(other.getPhoto()));
    }

    @Test
    void loadColorEndpointStoresTheColorOfOneProduct() throws Exception {
        productService.importProductsFromFilePath(SAMPLE_CSV);
        String photo = productRepository.findById("L1212-00-001").orElseThrow(AssertionError::new).getPhoto();
        int[] expected = FakeVisionService.colorFor(photo);

        mockMvc.perform(post("/loadColor/L1212-00-001"))
                .andExpect(status().isOk())
                .andExpect(content().json(String.format("[%d,%d,%d]", expected[0], expected[1], expected[2])));

        assertThat(productRepository.findById("L1212-00-001").orElseThrow(AssertionError::new).getDominantColor())
                .containsExactly(expected);
    }

    @Test
    void getProductsOfColorReturnsTheClosestProducts() throws Exception {
        productRepository.save(product("REF", new int[]{255, 0, 0}));
        productRepository.save(product("NEAR_RED", new int[]{250, 5, 5}));
        productRepository.save(product("DARK_RED", new int[]{139, 0, 0}));
        productRepository.save(product("BLUE", new int[]{0, 0, 255}));
        productRepository.save(product("NO_COLOR", null));

        mockMvc.perform(post("/getProductsOfColor/REF/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value("NEAR_RED"))
                .andExpect(jsonPath("$[1].id").value("DARK_RED"));

        mockMvc.perform(get("/getColor/REF"))
                .andExpect(status().isOk())
                .andExpect(content().json("[255,0,0]"));
    }

    private static Product product(String id, int[] dominantColor) {
        return new Product(id, "Polo " + id, GenderId.UNI, "100% Coton", "Manches courtes",
                "//img/" + id + ".jpg", "https://example.com/" + id, dominantColor);
    }
}
