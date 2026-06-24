package com.example.versionsandbox.web;

import java.math.BigDecimal;
import java.util.List;

import com.example.versionsandbox.domain.Product;
import com.example.versionsandbox.service.MongoTemplateService;
import com.example.versionsandbox.service.ProductService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CRUD over the {@code MongoRepository} (the "normal app" path) plus a couple of read endpoints
 * that drop down to {@code MongoTemplate} so you can compare the two APIs.
 */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;
    private final MongoTemplateService mongoTemplateService;

    public ProductController(ProductService productService, MongoTemplateService mongoTemplateService) {
        this.productService = productService;
        this.mongoTemplateService = mongoTemplateService;
    }

    @PostMapping
    public Product create(@RequestBody Product product) {
        return productService.create(product);
    }

    @GetMapping
    public List<Product> list(@RequestParam(required = false) String category) {
        return category == null ? productService.findAll() : productService.findByCategory(category);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Product> get(@PathVariable String id) {
        return productService.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** The document exactly as stored — handy for checking whether the {@code version} field exists. */
    @GetMapping("/{id}/raw")
    public ResponseEntity<Object> raw(@PathVariable String id) {
        Object doc = mongoTemplateService.findRawById(id);
        return doc == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(doc);
    }

    @PutMapping("/{id}")
    public Product update(@PathVariable String id, @RequestBody Product changes) {
        return productService.update(id, changes);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        productService.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /** Criteria-based search via {@code MongoTemplate}. */
    @GetMapping("/search")
    public List<Product> search(@RequestParam(required = false) String category,
                                @RequestParam(required = false) BigDecimal minPrice) {
        return mongoTemplateService.findByCategoryAndMinPrice(category, minPrice);
    }
}
