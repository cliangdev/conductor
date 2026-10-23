package com.conductor.integration.ingest;

import com.conductor.entity.ChannelMetricDefinitionRow;
import com.conductor.integration.MetricDefinition;
import com.conductor.repository.ChannelMetricDefinitionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Keeps a connection's {@code channel_metric_definition} rows equal to what its feed declared on the latest
 * pull: new metrics are inserted, changed ones updated in place, and metrics the feed stopped sending removed.
 */
@Component
public class ChannelMetricDefinitionStore {

    private final ChannelMetricDefinitionRepository repository;

    public ChannelMetricDefinitionStore(ChannelMetricDefinitionRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void replace(String projectId, String connectionId, List<MetricDefinition> definitions) {
        Map<String, ChannelMetricDefinitionRow> existing = new HashMap<>();
        repository.findByConnectionId(connectionId).forEach(r -> existing.put(r.getMetric(), r));
        Set<String> declared = definitions.stream().map(MetricDefinition::metric).collect(Collectors.toSet());

        List<ChannelMetricDefinitionRow> gone = existing.values().stream()
                .filter(r -> !declared.contains(r.getMetric())).toList();
        repository.deleteAll(gone);
        repository.flush();

        for (MetricDefinition d : definitions) {
            ChannelMetricDefinitionRow row = existing.get(d.metric());
            if (row == null) {
                row = new ChannelMetricDefinitionRow();
                row.setConnectionId(connectionId);
                row.setProjectId(projectId);
                row.setMetric(d.metric());
            }
            row.setLabel(d.label());
            row.setUnit(d.unit());
            row.setAggregation(d.aggregation());
            row.setNumerator(d.numerator());
            row.setDenominator(d.denominator());
            row.setGood(d.good());
            row.setFunnelOrder(d.funnelOrder());
            row.setTargetValue(d.targetValue());
            row.setTargetDirection(d.targetDirection());
            row.setTargetWindow(d.targetWindow());
            row.setBasis(d.basis());
            row.setArea(d.area());
            row.setDescription(d.description());
            row.setHeadline(d.headline());
            row.setWatchBandPct(d.watchBandPct());
            repository.save(row);
        }
    }
}
