package io.showcase.inventory.catalog;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.query.TextCriteria;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

/**
 * Spring Data MONGODB repository: the same programming model as JPA (derived queries, @Query,
 * paging), but queries are MongoDB JSON filters instead of SQL.
 */
public interface ProductRepository extends MongoRepository<Product, String> {

    /** Derived query on an embedded field: category = ? AND price.amount <= ?, served by the compound index. */
    List<Product> findByCategoryAndPriceAmountLessThanEqualOrderByPriceAmountAsc(String category, java.math.BigDecimal max);

    /**
     * Native Mongo JSON query: a dynamic attribute (schemaless!) plus "any of these tags" ($in on the
     * multikey index). The {@code fields} PROJECTION returns only name/price, the Mongo equivalent of
     * listing columns instead of SELECT *.
     */
    @Query(value = "{ 'attributes.color': ?0, 'tags': { $in: ?1 } }", fields = "{ 'name': 1, 'price': 1, 'category': 1 }")
    List<Product> findByColorAndAnyTag(String color, List<String> tags);

    /** Full-text search through the text index, ordered by relevance score. */
    List<Product> findAllBy(TextCriteria criteria, Pageable pageable);
}
