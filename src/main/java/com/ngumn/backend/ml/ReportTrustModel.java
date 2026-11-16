package com.ngumn.backend.ml;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Machine learning: how likely is a new road report to be true?
 *
 * An online logistic-regression model. Every time the community decides a
 * report - trusted (3 likes) or taken down (3 dislikes) - that outcome is a
 * labelled example (1 = true, 0 = false), and the model takes one
 * stochastic-gradient step on it. So it keeps learning from real use, and
 * its weights are saved in the database (ml_models).
 *
 * Features (all known the moment a report is made, never the votes that
 * decide it - that would give the answer away):
 *   1. reporter's trust score, centred: (score - 50) / 50
 *   2. log(1 + reporter's trusted reports)
 *   3. log(1 + reporter's removed reports)
 *   4. log(1 + other people who reported the same spot)
 *   5-6. time of day as sin / cos of the hour (night reports differ)
 *   7-11. kind: accident, pothole, jam, other road hazard, rule-breaker
 *   plus a bias term.
 *
 * Before it has data it starts from sensible weights (a trusted reporter
 * and several people reporting the same spot make a report more likely to
 * be true), so it is useful on day one and becomes data-driven as votes
 * come in. Its accuracy is measured honestly: each new outcome is first
 * predicted, scored, and only then learned from (prequential evaluation).
 *
 * The probability is shown on each report ("Likely true - 82%"); it does
 * not replace the community's vote, which still decides.
 */
@Service
public class ReportTrustModel {

    private static final Logger log = LoggerFactory.getLogger(ReportTrustModel.class);
    public static final String NAME = "report-trust";
    static final String[] FEATURES = {"bias", "trust", "trusted_reports", "removed_reports", "same_spot_reports",
            "hour_sin", "hour_cos", "kind_accident", "kind_pothole", "kind_jam", "kind_hazard", "kind_rule_breaker"};
    private static final double LEARNING_RATE = 0.08;
    private static final double L2 = 0.001;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The kinds of report the model tells apart. */
    public enum Kind { ACCIDENT, POTHOLE, JAM, HAZARD, RULE_BREAKER }

    /** What is known about a report when it is made. */
    public record Report(int reporterTrust, long trustedReports, long removedReports, int sameSpotReports,
                         int hourOfDay, Kind kind) {
    }

    private final MlModelStateRepository repository;
    private double[] w = initialWeights();
    private long samples;
    private long correct;
    private double logLossSum;

    public ReportTrustModel(MlModelStateRepository repository) {
        this.repository = repository;
    }

    static double[] initialWeights() {
        double[] w = new double[FEATURES.length];
        w[0] = 0.4;  // most reports turn out true
        w[1] = 1.2;  // a trusted reporter
        w[2] = 0.3;
        w[3] = -0.6; // past removed reports
        w[4] = 0.8;  // several people saw the same thing
        return w;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void load() {
        try {
            repository.findById(NAME).ifPresent(st -> {
                try {
                    double[] saved = JSON.readValue(st.getWeightsJson(), double[].class);
                    if (saved.length == FEATURES.length) {
                        synchronized (this) {
                            w = saved;
                            samples = nz(st.getSamples());
                            correct = nz(st.getCorrect());
                            logLossSum = st.getLogLossSum() == null ? 0 : st.getLogLossSum();
                        }
                    }
                } catch (Exception e) {
                    log.warn("Report-trust model weights unreadable, starting fresh: {}", e.getMessage());
                }
            });
        } catch (RuntimeException e) {
            log.warn("Could not load the report-trust model: {}", e.getMessage());
        }
    }

    static double[] features(Report r) {
        double[] x = new double[FEATURES.length];
        x[0] = 1;
        x[1] = (Math.max(0, Math.min(100, r.reporterTrust())) - 50) / 50.0;
        x[2] = Math.log1p(Math.max(0, r.trustedReports()));
        x[3] = Math.log1p(Math.max(0, r.removedReports()));
        x[4] = Math.log1p(Math.max(0, r.sameSpotReports()));
        double angle = 2 * Math.PI * (r.hourOfDay() % 24) / 24.0;
        x[5] = Math.sin(angle);
        x[6] = Math.cos(angle);
        x[7 + (r.kind() == null ? Kind.HAZARD : r.kind()).ordinal()] = 1;
        return x;
    }

    /** Probability (0..1) that the report is true. */
    public synchronized double probability(Report r) {
        return sigmoid(dot(w, features(r)));
    }

    /** Probability as a whole percent, 1..99 (never claims certainty). */
    public int percent(Report r) {
        return (int) Math.max(1, Math.min(99, Math.round(probability(r) * 100)));
    }

    /** One decided report: score the prediction first, then learn from it. */
    public void learn(Report r, boolean wasTrue) {
        double[] x = features(r);
        synchronized (this) {
            double p = sigmoid(dot(w, x));
            if ((p >= 0.5) == wasTrue) correct++;
            double clipped = Math.max(1e-6, Math.min(1 - 1e-6, p));
            logLossSum += wasTrue ? -Math.log(clipped) : -Math.log(1 - clipped);
            double err = (wasTrue ? 1 : 0) - p;
            for (int i = 0; i < w.length; i++) {
                double reg = i == 0 ? 0 : L2 * w[i];
                w[i] += LEARNING_RATE * (err * x[i] - reg);
            }
            samples++;
        }
        save();
    }

    private void save() {
        try {
            MlModelState st;
            synchronized (this) {
                st = MlModelState.builder().name(NAME).weightsJson(JSON.writeValueAsString(w))
                        .samples(samples).correct(correct).logLossSum(logLossSum)
                        .updatedAt(LocalDateTime.now()).build();
            }
            repository.save(st);
        } catch (Exception e) {
            log.warn("Could not save the report-trust model: {}", e.getMessage());
        }
    }

    /** For the Control Centre: what the model has learned and how well it does. */
    public synchronized Map<String, Object> summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "Report trust (online logistic regression)");
        m.put("samples", samples);
        m.put("accuracyPercent", samples == 0 ? null : Math.round(1000.0 * correct / samples) / 10.0);
        m.put("logLoss", samples == 0 ? null : Math.round(1000.0 * logLossSum / samples) / 1000.0);
        Map<String, Double> weights = new LinkedHashMap<>();
        for (int i = 0; i < FEATURES.length; i++) weights.put(FEATURES[i], Math.round(w[i] * 1000) / 1000.0);
        m.put("weights", weights);
        m.put("learnsFrom", "Every report the community trusts (true) or takes down (false)");
        return m;
    }

    synchronized double[] weights() {
        return Arrays.copyOf(w, w.length);
    }

    public synchronized long samples() {
        return samples;
    }

    private static double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }

    private static double sigmoid(double z) {
        return 1 / (1 + Math.exp(-z));
    }

    private static long nz(Long v) {
        return v == null ? 0 : v;
    }
}
