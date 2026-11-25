package com.pulsedesk.analytics;

import com.pulsedesk.common.cache.CacheProperties;
import com.pulsedesk.common.cache.RedisCache;
import com.pulsedesk.incident.IncidentPriority;
import com.pulsedesk.incident.IncidentSource;
import com.pulsedesk.incident.IncidentStatus;
import com.pulsedesk.monitoring.HealthStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AnalyticsService {

    public record SlaCompliance(int responseEvaluated, int responseMet, Double responseMetPercent,
                                int resolutionEvaluated, int resolutionMet, Double resolutionMetPercent) {
    }

    public record ServiceIncidentCount(UUID serviceId, String serviceName, long incidents) {
    }

    public record Summary(int periodDays, Instant generatedAt, long totalIncidents, long activeIncidents,
                          Map<String, Long> byStatus, Map<String, Long> byPriority, Map<String, Long> bySource,
                          Double mttaMinutes, Double mttrMinutes, SlaCompliance sla,
                          List<ServiceIncidentCount> topServices, Map<String, Long> serviceHealth) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final RedisCache cache;
    private final CacheProperties cacheProps;

    public AnalyticsService(NamedParameterJdbcTemplate jdbc, RedisCache cache, CacheProperties cacheProps) {
        this.jdbc = jdbc;
        this.cache = cache;
        this.cacheProps = cacheProps;
    }

    public Summary summary(UUID orgId, int days) {
        return cache.getOrCompute(cacheKey(orgId, days), Summary.class,
                Duration.ofSeconds(cacheProps.analyticsTtlSeconds()), () -> compute(orgId, days));
    }

    public static String cacheKey(UUID orgId, int days) {
        return "pulsedesk:analytics:" + orgId + ":" + days;
    }

    private Summary compute(UUID orgId, int days) {
        Instant now = Instant.now();
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("org", orgId)
                .addValue("since", Timestamp.from(now.minus(Duration.ofDays(days))))
                .addValue("now", Timestamp.from(now));

        Map<String, Long> byStatus = counts("status", IncidentStatus.values(), p);
        Map<String, Long> byPriority = counts("priority", IncidentPriority.values(), p);
        Map<String, Long> bySource = counts("source", IncidentSource.values(), p);
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long active = byStatus.entrySet().stream()
                .filter(e -> IncidentStatus.valueOf(e.getKey()).isActive()).mapToLong(Map.Entry::getValue).sum();

        Summary partial = jdbc.queryForObject("""
                select
                  avg(extract(epoch from (acknowledged_at - created_at))) / 60 as mtta,
                  avg(extract(epoch from (resolved_at - created_at))) / 60 as mttr,
                  count(*) filter (where acknowledged_at is not null or sla_response_due_at < :now) as resp_eval,
                  count(*) filter (where acknowledged_at is not null
                                   and acknowledged_at <= sla_response_due_at) as resp_met,
                  count(*) filter (where resolved_at is not null or sla_resolution_due_at < :now) as res_eval,
                  count(*) filter (where resolved_at is not null
                                   and resolved_at <= sla_resolution_due_at) as res_met
                from incidents
                where organization_id = :org and created_at >= :since
                """, p, (rs, i) -> {
            int respEval = rs.getInt("resp_eval");
            int respMet = rs.getInt("resp_met");
            int resEval = rs.getInt("res_eval");
            int resMet = rs.getInt("res_met");
            SlaCompliance sla = new SlaCompliance(respEval, respMet, percent(respMet, respEval),
                    resEval, resMet, percent(resMet, resEval));
            return new Summary(days, now, total, active, byStatus, byPriority, bySource,
                    round(rs.getBigDecimal("mtta")), round(rs.getBigDecimal("mttr")), sla, List.of(), Map.of());
        });

        List<ServiceIncidentCount> top = jdbc.query("""
                select s.id, s.name, count(*) as incidents
                from incidents i
                join services s on s.id = i.service_id and s.organization_id = i.organization_id
                where i.organization_id = :org and i.created_at >= :since
                group by s.id, s.name
                order by incidents desc, s.name
                limit 5
                """, p, (rs, i) -> new ServiceIncidentCount(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getLong("incidents")));

        Map<String, Long> health = zeroed(HealthStatus.values());
        jdbc.query("""
                select h.status, count(*) as n
                from health_check_configs h
                join services s on s.id = h.service_id and s.organization_id = h.organization_id
                where h.organization_id = :org and s.active and h.enabled
                group by h.status
                """, p, rs -> {
            health.put(rs.getString("status"), rs.getLong("n"));
        });

        return new Summary(partial.periodDays(), partial.generatedAt(), partial.totalIncidents(),
                partial.activeIncidents(), partial.byStatus(), partial.byPriority(), partial.bySource(),
                partial.mttaMinutes(), partial.mttrMinutes(), partial.sla(), top, health);
    }

    /** Column is always one of three constants below, never user input. */
    private Map<String, Long> counts(String column, Enum<?>[] values, MapSqlParameterSource p) {
        Map<String, Long> result = zeroed(values);
        jdbc.query("select " + column + " as k, count(*) as n from incidents "
                + "where organization_id = :org and created_at >= :since group by " + column, p, rs -> {
            result.put(rs.getString("k"), rs.getLong("n"));
        });
        return result;
    }

    private static Map<String, Long> zeroed(Enum<?>[] values) {
        return Arrays.stream(values).collect(java.util.stream.Collectors.toMap(
                Enum::name, v -> 0L, (a, b) -> a, LinkedHashMap::new));
    }

    private static Double percent(int met, int evaluated) {
        return evaluated == 0 ? null : round(BigDecimal.valueOf(met * 100.0 / evaluated));
    }

    private static Double round(BigDecimal value) {
        return value == null ? null : value.setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
