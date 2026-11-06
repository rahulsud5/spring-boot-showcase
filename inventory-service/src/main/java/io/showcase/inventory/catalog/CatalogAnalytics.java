package io.showcase.inventory.catalog;

import static org.springframework.data.mongodb.core.aggregation.Aggregation.group;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.lookup;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.match;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.project;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.sort;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.unwind;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

/**
 * The MONGODB AGGREGATION PIPELINE: documents flow through stages, like Unix pipes. It's the
 * NoSQL counterpart of SQL's GROUP BY / JOIN / window functions (compare with
 * OrderReportingRepository in order-service).
 *
 * <pre>
 *  SQL                                         Aggregation stage
 *  ─────────────────────────────────────────   ───────────────────
 *  WHERE price > 0                             $match
 *  LEFT JOIN stock s ON s._id = p._id          $lookup + $unwind (preserveNull = LEFT join)
 *  GROUP BY category, COUNT, AVG, SUM          $group
 *  ORDER BY products DESC                      $sort
 *  SELECT category, ...                        $project
 * </pre>
 *
 * Put {@code $match} FIRST, so it can use indexes and shrink the stream early. A {@code $lookup} is a
 * join: fine for analytics, but a sign of a modeling problem if hot paths need it.
 */
@Component
public class CatalogAnalytics {

    private final MongoTemplate mongo;

    public CatalogAnalytics(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public record CategoryStats(String category, long products, BigDecimal avgPrice, long unitsAvailable) {}

    public List<CategoryStats> categoryStats() {
        Aggregation pipeline = newAggregation(
                match(Criteria.where("price.amount").gt(BigDecimal.ZERO)),
                lookup("stock", "_id", "_id", "stock"),
                unwind("stock", true),
                group("category")
                        .count().as("products")
                        .avg("price.amount").as("avgPrice")
                        .sum("stock.available").as("unitsAvailable"),
                sort(Sort.Direction.DESC, "products"),
                project("products", "avgPrice", "unitsAvailable").and("_id").as("category"));

        return mongo.aggregate(pipeline, "products", CategoryStats.class).getMappedResults();
    }
}
