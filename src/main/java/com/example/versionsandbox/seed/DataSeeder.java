package com.example.versionsandbox.seed;

import java.math.BigDecimal;

import com.example.versionsandbox.domain.Product;
import com.example.versionsandbox.service.ProductService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Seeds a few products on startup so there is something to poke at immediately. Runs only when the
 * collection is empty, and can be turned off entirely with {@code app.seed.enabled=false} (useful
 * when you want to drive the whole dataset by hand for a migration experiment).
 */
@Component
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true", matchIfMissing = true)
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final ProductService productService;

    public DataSeeder(ProductService productService) {
        this.productService = productService;
    }

    @Override
    public void run(String... args) {
        if (productService.count() > 0) {
            log.info("Seed skipped: {} products already present", productService.count());
            return;
        }
        productService.create(new Product(null, "Mechanical Keyboard", "electronics", new BigDecimal("129.00"), 25));
        productService.create(new Product(null, "USB-C Cable", "electronics", new BigDecimal("12.50"), 200));
        productService.create(new Product(null, "Espresso Beans 1kg", "grocery", new BigDecimal("28.90"), 60));
        productService.create(new Product(null, "Notebook A5", "stationery", new BigDecimal("6.40"), 150));
        log.info("Seeded {} products (each has version=0 after insert)", productService.count());
    }
}
