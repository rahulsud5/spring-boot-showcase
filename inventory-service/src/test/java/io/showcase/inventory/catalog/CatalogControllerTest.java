package io.showcase.inventory.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@WebMvcTest(CatalogController.class)
class CatalogControllerTest {

    @Autowired
    MockMvcTester mvc;

    @MockitoBean
    ProductRepository products;
    @MockitoBean
    CatalogAnalytics analytics;

    @Test
    void returnsPriceWithHttpCachingHeaders() {
        given(products.findById("SKU-0001")).willReturn(Optional.of(new Product("SKU-0001", "Shoe", "desc", "shoes",
                new Product.Price(new BigDecimal("89.90"), "EUR"), List.of("running"), Map.of("color", "red"))));

        var result = mvc.get().uri("/api/products/SKU-0001/price").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).headers().hasValue("Cache-Control", "max-age=30");
        assertThat(result).bodyJson().extractingPath("$.amount").isEqualTo(89.90);
    }

    @Test
    void unknownSkuIs404() {
        given(products.findById("SKU-NOPE")).willReturn(Optional.empty());
        assertThat(mvc.get().uri("/api/products/SKU-NOPE/price")).hasStatus(404);
    }
}
