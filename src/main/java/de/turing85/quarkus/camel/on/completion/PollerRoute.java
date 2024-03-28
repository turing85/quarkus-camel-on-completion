package de.turing85.quarkus.camel.on.completion;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.sql.SqlConstants;

import static org.apache.camel.builder.endpoint.StaticEndpointBuilders.*;

@ApplicationScoped
public class PollerRoute extends RouteBuilder {
  public static final String RESULT_SET = "resultSet";
  private final AgroalDataSource source;
  private final AgroalDataSource target;

  public PollerRoute(
      @SuppressWarnings("CdiInjectionPointsInspection")
      @DataSource("source") AgroalDataSource source,

      @SuppressWarnings("CdiInjectionPointsInspection")
      @DataSource("target") AgroalDataSource target) {
    this.source = source;
    this.target = target;
  }

  @Override
  public void configure() {
    // @formatter:off
    from(
        scheduler("source-reader-scheduler")
            .delay(Duration.ofSeconds(10).toMillis())
            .greedy(true)
            .advanced()
                .synchronous(true))
        .id("source-reader-scheduler")
        .transacted()
        .to(sql("SELECT * FROM data WHERE processed = false LIMIT 2")
            .outputHeader(RESULT_SET)
            .dataSource(source))
        .choice()
            .when(header(SqlConstants.SQL_ROW_COUNT).isGreaterThan(0))
                .setProperty(Exchange.SCHEDULER_POLLED_MESSAGES, constant(true))
                .log("${header.%s} entries to process".formatted(SqlConstants.SQL_ROW_COUNT))
                .log("Transferring ${header.%s} entries...".formatted(SqlConstants.SQL_ROW_COUNT))
                .process(PollerRoute::resultSetToInsertStatement)
                .to(sql("sql-in-body")
                    .useMessageBodyForSql(true)
                    .dataSource(target))
                .log("done")
                .log("Marking ${header.%s} entries as processed..."
                    .formatted(SqlConstants.SQL_ROW_COUNT))
                .process(PollerRoute::resultSetToUpdateStatement)
                .to(sql("sql-in-body")
                    .useMessageBodyForSql(true)
                    .dataSource(source))
                .log("done")
            .otherwise()
                .setProperty(Exchange.SCHEDULER_POLLED_MESSAGES, constant(false))
                .log("Nothing to do")
        .end()
        .removeHeader(RESULT_SET);
    // @formatter:on
  }

  private static void resultSetToInsertStatement(Exchange exchange) {
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> resultSet = exchange.getIn().getHeader(RESULT_SET, List.class);
    // @formatter:off
    exchange.getIn().setBody("INSERT INTO data(id, data) VALUES " +
        resultSet.stream()
            .map(map -> "(%s, '%s')".formatted(map.get("id"), map.get("data")))
            .collect(Collectors.joining(", ")));
    // @formatter:on
  }

  private static void resultSetToUpdateStatement(Exchange exchange) {
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> resultSet = exchange.getIn().getHeader(RESULT_SET, List.class);
    // @formatter:off
    exchange.getIn().setBody("UPDATE data SET processed = true WHERE ID IN (" +
        resultSet.stream()
            .map(map -> map.get("id"))
            .map(Object::toString)
            .collect(Collectors.joining(", ")) +
        ")");
    // @formatter:on
  }
}
